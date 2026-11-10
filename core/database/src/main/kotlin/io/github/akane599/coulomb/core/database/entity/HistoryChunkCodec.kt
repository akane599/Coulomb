// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

/**
 * One history event as a chunk holds it: every field as the number it is stored as. [typeCode] and
 * [changeCode] are `HistoryCodes` positions; the codec carries them without reading the enums, so
 * a migration can pack rows whose codes it does not know.
 */
internal data class PackedEvent(
    val bootSeq: Int,
    val elapsedMs: Long,
    val typeCode: Int,
    val changeCode: Int,
    val baseline: Boolean,
    val uid: Int?,
    val tagId: Long?,
    val value: Long?,
)

/**
 * The byte form of a [HistoryChunkEntity] (ADR-0051). A row of the old `history_event` table took
 * 24.9 bytes for about 7 of content; the ring is the largest thing in the database.
 *
 * Format 1 is one byte holding [FORMAT], then each event in order:
 * 1. a head: `typeCode << 7 | changeCode << 5 | flags`, with the flags [BASELINE], [HAS_UID],
 *    [HAS_TAG], [HAS_VALUE] and [NEW_BOOT];
 * 2. with [NEW_BOOT], the boot and the elapsed time in full; otherwise the time since the event
 *    before, which may be negative. A chunk's first event always has [NEW_BOOT];
 * 3. the UID, the tag id and the value, each only if its flag is set.
 *
 * Numbers are base-128 varints, low bits first. The head, the boot and the tag id are never
 * negative and are written as they are; the others are zigzag-coded, so any `Long` fits.
 *
 * Decoding checks the format, every code's range and that the bytes end exactly after the last
 * event. A chunk that fails is corrupt and throws: a wrong event must never be read as a right
 * one. Never change format 1; a new form gets a new format byte.
 */
internal object HistoryChunkCodec {
    const val FORMAT: Int = 1

    /** Events in one chunk at most: a row then stays far below a cursor window's 2 MB. */
    const val MAX_EVENTS: Int = 4_096

    /** Change codes fit two bits. */
    const val MAX_CHANGE_CODE: Int = 3

    /** Type codes fit 24 bits, so a head stays within 31. */
    const val MAX_TYPE_CODE: Int = (1 shl 24) - 1

    private const val BASELINE = 1
    private const val HAS_UID = 2
    private const val HAS_TAG = 4
    private const val HAS_VALUE = 8
    private const val NEW_BOOT = 16
    private const val FLAG_BITS = 5
    private const val FLAG_MASK = 31L
    private const val CHANGE_BITS = 2
    private const val CHANGE_MASK = 3L
    private const val INITIAL_BYTES_PER_EVENT = 8

    /** [events] in order as one chunk's bytes. At most [MAX_EVENTS], and at least one. */
    fun encode(events: List<PackedEvent>): ByteArray {
        require(events.isNotEmpty() && events.size <= MAX_EVENTS) { "A chunk holds 1 to $MAX_EVENTS events, not ${events.size}" }
        val out = Writer(1 + events.size * INITIAL_BYTES_PER_EVENT)
        out.byte(FORMAT)
        var previous: PackedEvent? = null
        for (event in events) {
            require(event.typeCode in 0..MAX_TYPE_CODE) { "History type code ${event.typeCode} out of range" }
            require(event.changeCode in 0..MAX_CHANGE_CODE) { "History change code ${event.changeCode} out of range" }
            require(event.bootSeq >= 0) { "Boot ${event.bootSeq} is negative" }
            require(event.tagId == null || event.tagId >= 0) { "Tag id ${event.tagId} is negative" }
            val newBoot = previous == null || previous.bootSeq != event.bootSeq
            val flags =
                (if (event.baseline) BASELINE else 0) or
                    (if (event.uid != null) HAS_UID else 0) or
                    (if (event.tagId != null) HAS_TAG else 0) or
                    (if (event.value != null) HAS_VALUE else 0) or
                    (if (newBoot) NEW_BOOT else 0)
            out.unsigned(
                (event.typeCode.toLong() shl (CHANGE_BITS + FLAG_BITS)) or (event.changeCode.toLong() shl FLAG_BITS) or flags.toLong(),
            )
            if (newBoot) {
                out.unsigned(event.bootSeq.toLong())
                out.signed(event.elapsedMs)
            } else {
                // Wraps for times further apart than a Long holds, and wraps back the same way on reading.
                out.signed(event.elapsedMs - checkNotNull(previous).elapsedMs)
            }
            event.uid?.let { out.signed(it.toLong()) }
            event.tagId?.let(out::unsigned)
            event.value?.let(out::signed)
            previous = event
        }
        return out.bytes()
    }

    /** The [count] events of a chunk's [data], in order. Throws [CorruptChunkException] on anything but a whole format-1 chunk. */
    fun decode(
        count: Int,
        data: ByteArray,
    ): List<PackedEvent> {
        whole(count in 1..MAX_EVENTS) { "event count $count" }
        val input = Reader(data)
        input.byte().let { format -> whole(format == FORMAT) { "format $format" } }
        val events = ArrayList<PackedEvent>(count)
        var bootSeq = 0
        var elapsedMs = 0L
        repeat(count) { index ->
            val head = input.unsigned()
            val flags = (head and FLAG_MASK).toInt()
            val typeCode = head ushr (CHANGE_BITS + FLAG_BITS)
            whole(typeCode <= MAX_TYPE_CODE) { "head $head" }
            if (flags and NEW_BOOT != 0) {
                bootSeq = input.boot()
                elapsedMs = input.signed()
            } else {
                whole(index > 0) { "first event without a boot" }
                elapsedMs += input.signed()
            }
            events +=
                PackedEvent(
                    bootSeq = bootSeq,
                    elapsedMs = elapsedMs,
                    typeCode = typeCode.toInt(),
                    changeCode = ((head ushr FLAG_BITS) and CHANGE_MASK).toInt(),
                    baseline = flags and BASELINE != 0,
                    uid = if (flags and HAS_UID != 0) input.uid() else null,
                    tagId = if (flags and HAS_TAG != 0) input.tagId() else null,
                    value = if (flags and HAS_VALUE != 0) input.signed() else null,
                )
        }
        whole(input.atEnd()) { "bytes after the last event" }
        return events
    }

    private inline fun whole(
        condition: Boolean,
        what: () -> String,
    ) {
        if (!condition) throw CorruptChunkException(what())
    }

    private class Writer(
        capacity: Int,
    ) {
        private var buffer = ByteArray(capacity)
        private var size = 0

        fun byte(value: Int) {
            if (size == buffer.size) buffer = buffer.copyOf(size * 2 + VARINT_MAX_BYTES)
            buffer[size++] = value.toByte()
        }

        /** [value]'s 64 bits, seven at a time from the low end. */
        fun unsigned(value: Long) {
            var rest = value
            while (rest and LOW_BITS.inv() != 0L) {
                byte(((rest and LOW_BITS) or CONTINUE).toInt())
                rest = rest ushr VARINT_BITS
            }
            byte(rest.toInt())
        }

        fun signed(value: Long) = unsigned((value shl 1) xor (value shr SIGN_SHIFT))

        fun bytes(): ByteArray = buffer.copyOf(size)
    }

    private class Reader(
        private val data: ByteArray,
    ) {
        private var position = 0

        fun atEnd(): Boolean = position == data.size

        fun byte(): Int {
            whole(position < data.size) { "ends early" }
            return data[position++].toInt() and BYTE_MASK
        }

        fun unsigned(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val next = byte().toLong()
                // The tenth byte carries the last bit of 64: anything more is not a number we wrote.
                whole(shift < LAST_SHIFT || next <= 1) { "number too long" }
                result = result or ((next and LOW_BITS) shl shift)
                if (next and CONTINUE == 0L) return result
                shift += VARINT_BITS
            }
        }

        fun signed(): Long = unsigned().let { (it ushr 1) xor -(it and 1) }

        fun boot(): Int = unsigned().also { whole(it in 0..Int.MAX_VALUE) { "boot $it" } }.toInt()

        fun uid(): Int = signed().also { whole(it in Int.MIN_VALUE..Int.MAX_VALUE) { "uid $it" } }.toInt()

        fun tagId(): Long = unsigned().also { whole(it >= 0) { "tag id $it" } }
    }

    private const val VARINT_BITS = 7
    private const val VARINT_MAX_BYTES = 10
    private const val LAST_SHIFT = 63
    private const val LOW_BITS = 0x7FL
    private const val CONTINUE = 0x80L
    private const val BYTE_MASK = 0xFF
    private const val SIGN_SHIFT = 63
}

/** A history chunk whose bytes are not a whole format-1 chunk. */
public class CorruptChunkException(
    what: String,
) : IllegalStateException("Corrupt history chunk: $what")

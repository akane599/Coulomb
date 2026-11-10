// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.CorruptChunkException
import io.github.akane599.coulomb.core.database.entity.HistoryChunkCodec
import io.github.akane599.coulomb.core.database.entity.HistoryCodes
import io.github.akane599.coulomb.core.database.entity.PackedEvent
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

/** The history chunk's byte form (ADR-0051): whatever goes in comes out, and nothing else is read. */
class HistoryChunkCodecTest {
    private fun event(
        elapsedMs: Long,
        bootSeq: Int = 1,
        typeCode: Int = 5,
        changeCode: Int = 2,
        baseline: Boolean = false,
        uid: Int? = null,
        tagId: Long? = null,
        value: Long? = null,
    ) = PackedEvent(bootSeq, elapsedMs, typeCode, changeCode, baseline, uid, tagId, value)

    private fun roundTrip(events: List<PackedEvent>): List<PackedEvent> =
        HistoryChunkCodec.decode(events.size, HistoryChunkCodec.encode(events))

    @Test
    fun formatOneIsFrozen() {
        // A level of 80 at 100 ms, then a restated wakelock 250 ms later. Written out by hand from
        // the format's description; a change here breaks every stored chunk.
        val bytes =
            HistoryChunkCodec.encode(
                listOf(
                    event(100, value = 80),
                    event(350, typeCode = 13, changeCode = 0, baseline = true, uid = 10_123, tagId = 5),
                ),
            )
        assertThat(bytes.joinToString(" ") { "%02X".format(it) })
            .isEqualTo("01 D8 05 01 C8 01 A0 01 87 0D F4 03 96 9E 01 05")
    }

    @Test
    fun everyStoredCodeFitsTheFormat() {
        // A new kind or change must still fit the head's bits; past them, format 1 cannot hold it.
        assertThat(HistoryCodes.CHANGES.size - 1).isAtMost(HistoryChunkCodec.MAX_CHANGE_CODE)
        assertThat(HistoryCodes.KINDS.size - 1).isAtMost(HistoryChunkCodec.MAX_TYPE_CODE)
        assertThat(HistoryCodes.KINDS.toSet()).hasSize(HistoryCodes.KINDS.size)
    }

    @Test
    fun everyFieldSurvivesAtItsExtremes() {
        val events =
            listOf(
                event(Long.MIN_VALUE, bootSeq = 0, typeCode = 0, changeCode = 0, value = Long.MIN_VALUE),
                event(
                    Long.MAX_VALUE,
                    bootSeq = 0,
                    typeCode = 67,
                    changeCode = 3,
                    baseline = true,
                    uid = Int.MIN_VALUE,
                    value = Long.MAX_VALUE,
                ),
                event(0, bootSeq = 0, uid = Int.MAX_VALUE, tagId = Long.MAX_VALUE, value = -1),
                event(-1, bootSeq = Int.MAX_VALUE, typeCode = HistoryChunkCodec.MAX_TYPE_CODE, uid = -1, tagId = 0, value = 0),
                event(5, bootSeq = Int.MAX_VALUE, uid = 0),
            )
        assertThat(roundTrip(events)).containsExactlyElementsIn(events).inOrder()
    }

    @Test
    fun bootsChangeAndTimeMayStepBack() {
        val events =
            listOf(
                event(86_400_000L * 40, bootSeq = 7),
                event(86_400_000L * 40 - 3, bootSeq = 7),
                event(12, bootSeq = 8),
                event(12, bootSeq = 8),
                // A read can restate an older boot's event after a newer one.
                event(900, bootSeq = 7),
            )
        assertThat(roundTrip(events)).containsExactlyElementsIn(events).inOrder()
    }

    @Test
    fun randomReadsComeBackUnchanged() {
        val random = Random(20_261_110)
        repeat(RANDOM_READS) {
            var boot = random.nextInt(0, 400)
            var time = random.nextLong(0, 3_000_000_000)
            val events =
                List(random.nextInt(1, 700)) {
                    if (random.nextInt(200) == 0) {
                        boot += 1
                        time = random.nextLong(0, 60_000)
                    }
                    time += if (random.nextInt(50) == 0) -random.nextLong(0, 2_000) else random.nextLong(0, 40_000)
                    event(
                        time,
                        bootSeq = boot,
                        typeCode = random.nextInt(0, 80),
                        changeCode = random.nextInt(0, 4),
                        baseline = random.nextBoolean(),
                        uid = if (random.nextBoolean()) random.nextInt(-2, 10_999_999) else null,
                        tagId = if (random.nextBoolean()) random.nextLong(0, 5_000_000) else null,
                        value = if (random.nextInt(3) == 0) random.nextLong() else null,
                    )
                }
            assertThat(roundTrip(events)).isEqualTo(events)
        }
    }

    @Test
    fun aChunkHoldsOneToMaxEvents() {
        val full = List(HistoryChunkCodec.MAX_EVENTS) { event(it.toLong()) }
        assertThat(roundTrip(full)).isEqualTo(full)
        assertThrows(IllegalArgumentException::class.java) { HistoryChunkCodec.encode(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { HistoryChunkCodec.encode(full + event(0)) }
    }

    @Test
    fun whatTheFormatCannotHoldIsRefused() {
        for (bad in listOf(
            event(1, changeCode = 4),
            event(1, changeCode = -1),
            event(1, typeCode = -1),
            event(1, bootSeq = -1),
            event(1, tagId = -1),
        )) {
            assertThrows(IllegalArgumentException::class.java) { HistoryChunkCodec.encode(listOf(bad)) }
        }
    }

    @Test
    fun anythingButAWholeChunkIsCorrupt() {
        val events = listOf(event(100, value = 80), event(350, uid = 10_123, tagId = 5))
        val bytes = HistoryChunkCodec.encode(events)

        fun corrupt(
            count: Int,
            data: ByteArray,
        ) = assertThrows(CorruptChunkException::class.java) { HistoryChunkCodec.decode(count, data) }

        for (cut in 0 until bytes.size) corrupt(2, bytes.copyOf(cut))
        corrupt(2, bytes + 0.toByte())
        corrupt(1, bytes)
        corrupt(3, bytes)
        corrupt(0, bytes)
        corrupt(HistoryChunkCodec.MAX_EVENTS + 1, bytes)
        corrupt(2, bytes.copyOf().also { it[0] = 2 })
        // A first event that claims to continue a boot.
        corrupt(1, byteArrayOf(1, 0x80.toByte(), 0x05, 0x02))
        // A number that runs past 64 bits.
        corrupt(1, byteArrayOf(1) + ByteArray(11) { 0xFF.toByte() })
        // A boot beyond an Int.
        corrupt(1, byteArrayOf(1, 0x90.toByte(), 0x05) + ByteArray(5) { 0xFF.toByte() } + byteArrayOf(0x00))
    }

    private companion object {
        const val RANDOM_READS = 200
    }
}

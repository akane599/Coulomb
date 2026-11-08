// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.kernel

import io.github.akane599.coulomb.core.model.kernel.KernelBoot
import io.github.akane599.coulomb.core.model.kernel.KernelLogAllowlist
import io.github.akane599.coulomb.core.model.kernel.KernelLogEvent
import io.github.akane599.coulomb.core.model.kernel.KernelLogRecord
import io.github.akane599.coulomb.core.model.kernel.SeqGap
import io.github.akane599.coulomb.core.model.kernel.WakeupIrq
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.parsing.ParseContext
import io.github.akane599.coulomb.core.parsing.io.Line
import io.github.akane599.coulomb.core.parsing.text.TextDump
import java.io.InputStream

/** One S27 read: the records the worker let through and the sequence span it vouches for. */
public data class KernelLogRead(
    /** [KernelBoot.key] of the header's `boot_id`; null when the header is missing or unreadable. */
    val bootKey: Long?,
    /**
     * The cursor the worker applied: records up to it were skipped. Null when it read from the
     * ring's start, because it had no cursor or the cursor belonged to another boot.
     */
    val afterSeq: Long?,
    val records: List<KernelLogRecord>,
    /** The first record past the cursor the worker read, kept or not; null when it read none. */
    val firstSeq: Long?,
    /**
     * The last record the read is known to have covered: the trailer's, or for a cut read the last
     * kept record. Null when it covered none.
     */
    val lastSeq: Long?,
    /** Records the ring overwrote before the worker saw them, up to [lastSeq]. */
    val gaps: List<SeqGap>,
) {
    val lost: Long get() = gaps.sumOf { it.size }
}

/**
 * S27: the worker's filtered `/dev/kmsg` stream into a [KernelLogRead]. Never throws.
 *
 * The worker reads from the ring's oldest record and skips those at or before the cursor it was
 * given, because `/dev/kmsg` cannot seek to a sequence number (ADR-0029 §2). The stream is:
 * - one header, `#begin boot=<boot_id> after=<seq> first=<seq>`: the kernel boot id, the cursor the
 *   worker applied (`-` for none) and the first record past it, kept or not (`-` when the ring held
 *   none);
 * - each kept record as `/dev/kmsg` prints its first line, `<prio>,<seq>,<µs>,<flags>[,…];<text>`
 *   (Documentation/ABI/testing/dev-kmsg, kernel/printk/printk.c `info_print_ext_header`),
 *   unchanged;
 * - `#gap from=<seq> to=<seq>` wherever records `from..to` were overwritten before the worker
 *   read them, in sequence order with the records, including one before the first record when the
 *   ring no longer reached the record after the cursor;
 * - one trailer, `#end last=<seq> lost=<n>`: the last record past the cursor, kept or not (`-`
 *   when none), and the sum of the gaps as a check.
 *
 * Without the trailer the read was cut short and [Parsed.report] says incomplete. The records
 * before the cut are still exact, since the kernel numbers them in order: [KernelLogRead.lastSeq] is
 * then the last kept record, and gaps after it are dropped so the next read reports them once.
 */
public object KernelLogParser {
    public const val SOURCE: String = "kmsg"

    public fun parse(input: InputStream): Parsed<KernelLogRead> {
        val ctx = ParseContext(SOURCE)
        val walk = Walk(ctx)
        val dump = TextDump(input, ctx, SOURCE)
        val clean = dump.forEachLine(walk::line)
        // A record whose header cannot be read, or that is out of order, leaves the read incomplete:
        // its event would otherwise be missing from a window that claims to be whole. A whole record
        // whose text this side does not classify is only counted (SPEC 5.1).
        val complete = walk.begun && walk.ended && clean && dump.lostLines == 0 && ctx.issueCountIsZero()
        val lastSeq = if (walk.ended) walk.last else walk.records.lastOrNull()?.seq
        val read =
            KernelLogRead(
                bootKey = walk.bootKey,
                afterSeq = walk.after,
                records = walk.records,
                firstSeq = walk.first,
                lastSeq = lastSeq,
                gaps = if (lastSeq == null) emptyList() else walk.gaps.filter { it.to <= lastSeq },
            )
        return Parsed(read, ctx.report(dump.bytesRead, complete))
    }

    private class Walk(
        private val ctx: ParseContext,
    ) {
        val records = ArrayList<KernelLogRecord>()
        val gaps = ArrayList<SeqGap>()
        var begun = false
        var ended = false
        var bootKey: Long? = null
        var after: Long? = null
        var first: Long? = null
        var last: Long? = null

        /** The highest sequence number a record or gap accounted for so far. */
        private var cursor = -1L

        fun line(line: Line) {
            val text = line.text
            when {
                ended -> ctx.issue(line.offset, "end", "data after the trailer")
                text.startsWith(BEGIN) -> begin(text, line.offset)
                text.startsWith(GAP) -> gap(text, line.offset)
                text.startsWith(END) -> end(text, line.offset)
                text.isNotEmpty() -> record(text, line.offset)
            }
        }

        private fun begin(
            text: String,
            offset: Long,
        ) {
            if (begun || records.isNotEmpty() || gaps.isNotEmpty()) {
                ctx.issue(offset, "begin", "header out of place")
                return
            }
            val fields = fields(text.removePrefix(BEGIN))
            val key = fields["boot"]?.let(KernelBoot::key)
            val afterValue = fields["after"]?.let(::seqOrNone)
            val firstValue = fields["first"]?.let(::seqOrNone)
            if (key == null || afterValue == null || firstValue == null) {
                ctx.issue(offset, "begin", "unreadable")
                return
            }
            if (afterValue.value != null && firstValue.value != null && firstValue.value <= afterValue.value) {
                ctx.issue(offset, "begin", "first not past the cursor")
                return
            }
            begun = true
            bootKey = key
            after = afterValue.value
            first = firstValue.value
            cursor = afterValue.value ?: -1L
        }

        private fun gap(
            text: String,
            offset: Long,
        ) {
            val fields = fields(text.removePrefix(GAP))
            val from = fields["from"]?.toLongOrNull()
            val to = fields["to"]?.toLongOrNull()
            when {
                !begun -> {
                    ctx.issue(offset, "gap", "before the header")
                }

                from == null || to == null || from < 0 || to < from -> {
                    ctx.issue(offset, "gap", "unreadable")
                }

                from <= cursor -> {
                    ctx.issue(offset, "gap", "sequence went back")
                }

                first.let { it != null && from < it && to >= it } -> {
                    ctx.issue(offset, "gap", "overlaps the first record")
                }

                else -> {
                    gaps += SeqGap(from, to)
                    cursor = to
                }
            }
        }

        private fun end(
            text: String,
            offset: Long,
        ) {
            val fields = fields(text.removePrefix(END))
            val lastValue = fields["last"]?.let(::seqOrNone)
            val lostValue = fields["lost"]?.toLongOrNull()
            when {
                !begun || lastValue == null || lostValue == null -> {
                    ctx.issue(offset, "end", "unreadable or before the header")
                }

                lostValue != gaps.sumOf { it.size } -> {
                    ctx.issue(offset, "end", "lost does not match the gaps")
                }

                // With no record past the cursor, nothing may have moved the walk past the header's
                // own cursor either: a read that found nothing new since `after` is whole.
                (lastValue.value == null) != (first == null) || (lastValue.value == null && cursor != (after ?: -1L)) -> {
                    ctx.issue(offset, "end", "first and last disagree")
                }

                lastValue.value != null && lastValue.value < maxOf(cursor, first ?: -1L) -> {
                    ctx.issue(offset, "end", "last before a record or gap")
                }

                else -> {
                    ended = true
                    last = lastValue.value
                }
            }
        }

        private fun record(
            text: String,
            offset: Long,
        ) {
            if (!begun) ctx.issue(offset, "record", "before the header")
            val record = parseRecord(text, offset, ctx) ?: return
            if (record.seq <= cursor || record.seq < (first ?: Long.MAX_VALUE)) {
                ctx.issue(offset, "record", "sequence went back")
                return
            }
            cursor = record.seq
            if (record.event != null) records += KernelLogRecord(record.seq, record.monotonicUs, record.event)
        }
    }

    /** A sequence number, or `-` for none; null when the text is neither. */
    private class SeqOrNone(
        val value: Long?,
    )

    private fun seqOrNone(text: String): SeqOrNone? =
        if (text == NONE) SeqOrNone(null) else text.toLongOrNull()?.takeIf { it >= 0 }?.let(::SeqOrNone)

    /** `key=value` pairs separated by spaces. */
    private fun fields(text: String): Map<String, String> =
        text
            .trim()
            .split(' ')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()

    private class RawRecord(
        val seq: Long,
        val monotonicUs: Long,
        val event: KernelLogEvent?,
    )

    private fun parseRecord(
        text: String,
        offset: Long,
        ctx: ParseContext,
    ): RawRecord? {
        val semi = text.indexOf(';')
        if (semi < 0) return null.also { ctx.issue(offset, "record", "no header") }
        val header = text.substring(0, semi).split(',')
        val seq = header.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }
        val us = header.getOrNull(2)?.toLongOrNull()?.takeIf { it >= 0 }
        if (header.size < MIN_HEADER_FIELDS || seq == null || us == null) {
            return null.also { ctx.issue(offset, "record", "bad header") }
        }
        // A record the worker kept but this side does not classify still moves the cursor: its
        // sequence number is real, only its text is new to this version.
        val event = KernelLogLines.classify(text.substring(semi + 1))
        if (event == null) ctx.unknown("unclassified line")
        return RawRecord(seq, us, event)
    }

    private const val BEGIN = "#begin"
    private const val GAP = "#gap"
    private const val END = "#end"
    private const val NONE = "-"
    private const val MIN_HEADER_FIELDS = 4
}

/** The suspend and resume lines of S27, by the kernel line that prints each (see [KernelLogEvent]). */
public object KernelLogLines {
    /** The event [text] reports, or null when it is not one of the kept lines. */
    public fun classify(text: String): KernelLogEvent? {
        val t = text.trimEnd()
        return when {
            t.startsWith(SUSPEND_ENTRY) && t.endsWith(")") -> {
                KernelLogEvent.SuspendEntry(t.substring(SUSPEND_ENTRY.length, t.length - 1))
            }

            t == SUSPEND_EXIT -> {
                KernelLogEvent.SuspendExit
            }

            t.startsWith(RESUME_IRQ) -> {
                resumeIrq(t.removePrefix(RESUME_IRQ))
            }

            t.startsWith(RESUME_BY) -> {
                KernelLogEvent.ResumeOther(t.removePrefix(RESUME_BY))
            }

            t == RESUME_UNKNOWN -> {
                KernelLogEvent.ResumeUnknown
            }

            t.startsWith(ABORT) -> {
                KernelLogEvent.Abort(t.removePrefix(ABORT))
            }

            t == DEVICES_FAILED -> {
                KernelLogEvent.DevicesFailed
            }

            t.startsWith(FREEZING) -> {
                freeze(t.removePrefix(FREEZING))
            }

            DEVICE_FAILED in t -> {
                deviceFailed(t)
            }

            else -> {
                null
            }
        }
    }

    /** `<n>, <name>`. */
    private fun resumeIrq(rest: String): KernelLogEvent? {
        val comma = rest.indexOf(", ")
        if (comma <= 0) return null
        val irq = rest.substring(0, comma).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val name = rest.substring(comma + 2).trim()
        return if (name.isEmpty()) null else KernelLogEvent.ResumeIrq(WakeupIrq(irq, name))
    }

    /** `<what> failed after …` or `<what> aborted after …`; the completed form is not kept. */
    private fun freeze(rest: String): KernelLogEvent? {
        val failed = rest.indexOf(KernelLogAllowlist.FREEZE_FAILED)
        val aborted = rest.indexOf(KernelLogAllowlist.FREEZE_ABORTED)
        return when {
            failed > 0 -> KernelLogEvent.FreezeFailed(rest.substring(0, failed), aborted = false)
            aborted > 0 -> KernelLogEvent.FreezeFailed(rest.substring(0, aborted), aborted = true)
            else -> null
        }
    }

    /** `<driver> <device>: PM: failed to <step>: error <n>`. */
    private fun deviceFailed(t: String): KernelLogEvent? {
        val at = t.indexOf(DEVICE_FAILED)
        val device = t.substring(0, at).trim()
        val rest = t.substring(at + DEVICE_FAILED.length)
        val colon = rest.lastIndexOf(ERROR)
        if (device.isEmpty() || colon <= 0) return null
        val errno = rest.substring(colon + ERROR.length).trim().toIntOrNull() ?: return null
        return KernelLogEvent.DeviceFailed(device, rest.substring(0, colon), errno)
    }

    private const val SUSPEND_ENTRY = KernelLogAllowlist.SUSPEND_ENTRY
    private const val SUSPEND_EXIT = KernelLogAllowlist.SUSPEND_EXIT
    private const val RESUME_IRQ = KernelLogAllowlist.RESUME_IRQ
    private const val RESUME_BY = KernelLogAllowlist.RESUME_BY
    private const val RESUME_UNKNOWN = KernelLogAllowlist.RESUME_UNKNOWN
    private const val ABORT = KernelLogAllowlist.ABORT
    private const val DEVICES_FAILED = KernelLogAllowlist.DEVICES_FAILED
    private const val FREEZING = KernelLogAllowlist.FREEZING
    private const val DEVICE_FAILED = KernelLogAllowlist.DEVICE_FAILED
    private const val ERROR = ": error "
}

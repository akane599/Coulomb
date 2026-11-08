// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import io.github.akane599.coulomb.core.database.dao.WindowStat
import io.github.akane599.coulomb.core.database.dao.WindowWake
import io.github.akane599.coulomb.core.database.entity.KernelReadEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.WakeupSourceStatEntity
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import io.github.akane599.coulomb.core.model.kernel.KernelLogAllowlist
import io.github.akane599.coulomb.core.model.kernel.KernelLogEvent
import io.github.akane599.coulomb.core.model.kernel.KernelLogRecord

/** One end of a window as its kernel rows see it. */
internal data class KernelEnd(
    val snapshot: SnapshotEntity,
    /** The end's kernel read; null when it read no kernel source. */
    val read: KernelReadEntity?,
    /** Its S9 sources; empty unless its S9 status is `ok`. */
    val sources: List<WakeupSourceStatEntity>,
) {
    val sourcesOk: Boolean get() = sourceStatus(snapshot.sourceStatusJson, KernelReader.S9) == SourceStatus.OK

    /** Its S27 read reached the ring's end, so its last record is where the log stood at this end. */
    val logOk: Boolean get() = sourceStatus(snapshot.sourceStatusJson, KernelReader.S27) == SourceStatus.OK
}

/**
 * A window's S27 range: the records of [kernelBoot] with `afterSeq < seq <= lastSeq`, and how much
 * of it the stored reads cover. Never [Coverage.NONE]: then there is no range.
 */
internal data class LogRange(
    val kernelBoot: Long,
    val afterSeq: Long,
    val lastSeq: Long,
    val coverage: Coverage,
)

/** A window's root kernel rows; [coverage] is null when no kernel source was collected for it. */
internal data class KernelWindowRows(
    val coverage: KernelCoverage?,
    val wakes: List<WindowWake>,
    val stats: List<WindowStat>,
)

/**
 * The root kernel part of a window (ADR-0029 §4). Pure. A source not collected at both ends gets
 * coverage NONE and no rows, never zeros. A missing row means zero only where its source is WHOLE;
 * PARTIAL counts are "at least".
 */
internal object KernelWindow {
    /** Rows kept per kind; past it the source is PARTIAL, since a missing row would read as zero. */
    const val MAX_ROWS_PER_KIND: Int = 100

    const val S25_KEY_PREFIX: String = "s25."
    const val S25_SUCCESS: String = "s25.success"
    const val S25_FAIL: String = "s25.fail"

    /** [between]: the heavy snapshots with counters inside the window, oldest first; none for a consecutive one. */
    fun build(
        from: KernelEnd,
        to: KernelEnd,
        range: LogRange?,
        events: List<KernelLogRecord>,
        between: List<KernelEnd> = emptyList(),
    ): KernelWindowRows {
        val (s25, stats) = suspendStats(from, to)
        val (s9, sourceWakes) = wakeupSources(from, to, between)
        val (s27, logWakes) = range?.let { logRows(it, events) } ?: (Coverage.NONE to emptyList())
        val coverage = KernelCoverage(s25, s9, s27).takeUnless { s25 == Coverage.NONE && s9 == Coverage.NONE && s27 == Coverage.NONE }
        return KernelWindowRows(coverage, sourceWakes + logWakes, stats)
    }

    /**
     * S25: the change of each counter, when both ends read it under one kernel boot and none fell.
     * Device rows, Measured; zero changes are left out like every other counter's.
     */
    fun suspendStats(
        from: KernelEnd,
        to: KernelEnd,
    ): Pair<Coverage, List<WindowStat>> {
        val a = from.read
        val b = to.read
        val start = a?.suspendStats
        val end = b?.suspendStats
        if (start == null || end == null || a.kernelBoot != b.kernelBoot) return Coverage.NONE to emptyList()
        val changes = linkedMapOf(S25_SUCCESS to end.success - start.success, S25_FAIL to end.fail - start.fail)
        val steps = start.failedSteps.keys + end.failedSteps.keys
        for (step in steps) {
            val before = start.failedSteps[step]
            val after = end.failedSteps[step]
            if (before != null && after != null) changes[S25_KEY_PREFIX + step.file] = after - before
        }
        if (changes.values.any { it < 0 }) return Coverage.NONE to emptyList()
        // A step the kernel reported at one end only has no change to give.
        val coverage = if (steps.size == changes.size - 2) Coverage.WHOLE else Coverage.PARTIAL
        val rows =
            changes.filterValues { it > 0 }.map { (key, value) ->
                WindowStat(UidPowerEntity.DEVICE_UID, key, value, Provenance.MEASURED)
            }
        return coverage to rows
    }

    /**
     * S9 per instance, step by step from [from] through [between] to [to], summed by name only
     * here. In each step an instance at both snapshots gives its change; one whose cumulative
     * counter fell, or that another source took over, was reset and gives none; one present only at
     * the later snapshot was created in the step and gives its whole value. A reset or a vanished
     * instance makes the window PARTIAL. So does a snapshot in between without an `ok` S9 read under
     * the window's boot, since a source reset in its stretch would go unseen: the kernel gives a
     * recreated source the lowest free number, often its old one. Only sources whose wakeup count
     * rose get a row; wakelock time on its own is S5's.
     */
    fun wakeupSources(
        from: KernelEnd,
        to: KernelEnd,
        between: List<KernelEnd> = emptyList(),
    ): Pair<Coverage, List<WindowWake>> {
        if (!sameBoot(from, to) || !(from.sourcesOk && to.sourcesOk)) return Coverage.NONE to emptyList()
        val byName = LinkedHashMap<String, SourceChange>()
        var partial = false
        var previous = from
        for (next in between + to) {
            if (next.sourcesOk && sameBoot(from, next)) {
                partial = step(previous.sources, next.sources, byName) || partial
                previous = next
            } else {
                partial = true
            }
        }
        val woke = byName.entries.filter { it.value.wakeups > 0 }.sortedByDescending { it.value.wakeups }
        if (woke.size > MAX_ROWS_PER_KIND) partial = true
        val rows =
            woke.take(MAX_ROWS_PER_KIND).map { (name, c) ->
                WindowWake(null, name, WakeKind.WAKEUP_SOURCE, c.totalMs, c.count.toIntCapped(), null, wakeups = c.wakeups)
            }
        return (if (partial) Coverage.PARTIAL else Coverage.WHOLE) to rows
    }

    /**
     * The S27 range (A's last, B's last] of a window (A, B), from A's read, the reads in (A, B] and
     * B's read. WHOLE only when every one of those reads ran under A's kernel boot, B read the log
     * to the ring's end, and together, minus their gaps, they cover the range. Null when nothing of
     * it is covered, or when A's read stopped short of the ring's end: records past its last may
     * then be older than A, and an earlier window may have counted them.
     */
    fun logRange(
        from: KernelEnd,
        reads: List<KernelReadEntity>,
        to: KernelEnd,
    ): LogRange? {
        val a = from.read?.takeIf { it.kmsgGaps != null && from.logOk } ?: return null
        val start = a.kmsgLastSeq ?: return null
        val sameBoot = reads.filter { it.kernelBoot == a.kernelBoot }
        val end = to.read?.takeIf { it.kmsgGaps != null && it.kernelBoot == a.kernelBoot }?.kmsgLastSeq
        val last = end ?: sameBoot.mapNotNull { it.kmsgLastSeq }.maxOrNull() ?: return null
        val whole = end != null && to.logOk && sameBoot.size == reads.size
        return coverage(start, last, sameBoot, whole)?.let { LogRange(a.kernelBoot, start, last, it) }
    }

    /**
     * How much of `(start, last]` [reads] cover, minus their gaps: WHOLE only when [whole] allows it,
     * null when they cover none of it or the range runs backwards.
     */
    private fun coverage(
        start: Long,
        last: Long,
        reads: List<KernelReadEntity>,
        whole: Boolean,
    ): Coverage? {
        if (last < start) return null
        val covered = merged(reads.flatMap(::covered))
        val target = start + 1..last
        return when {
            whole && covers(covered, target) -> Coverage.WHOLE
            covered.any { it.first <= target.last && target.first <= it.last } -> Coverage.PARTIAL
            else -> null
        }
    }

    /**
     * Counts of the range's records: `RESUME_CAUSE` one per leaf interrupt line, so a resume with
     * two interrupts counts twice, and `SUSPEND_ABORT` one per `Abort:` line. Device and freeze
     * failure lines are printed alongside an abort and never add to a count.
     */
    fun logRows(
        range: LogRange,
        events: List<KernelLogRecord>,
    ): Pair<Coverage, List<WindowWake>> {
        val causes = LinkedHashMap<String, Int>()
        val aborts = LinkedHashMap<String, Int>()
        for (record in events) {
            when (val event = record.event) {
                is KernelLogEvent.ResumeIrq -> causes.merge(event.irq.name, 1, Int::plus)
                is KernelLogEvent.ResumeOther -> causes.merge(event.reason, 1, Int::plus)
                KernelLogEvent.ResumeUnknown -> causes.merge(KernelLogAllowlist.RESUME_UNKNOWN, 1, Int::plus)
                is KernelLogEvent.Abort -> aborts.merge(event.reason, 1, Int::plus)
                else -> Unit
            }
        }
        var coverage = range.coverage
        val rows =
            listOf(WakeKind.RESUME_CAUSE to causes, WakeKind.SUSPEND_ABORT to aborts).flatMap { (kind, counts) ->
                if (counts.size > MAX_ROWS_PER_KIND) coverage = Coverage.PARTIAL
                counts.entries
                    .sortedByDescending { it.value }
                    .take(MAX_ROWS_PER_KIND)
                    .map { (tag, n) -> WindowWake(null, tag, kind, 0, n, null) }
            }
        return coverage to rows
    }

    /** Adds one step's changes to [byName]; true when the step lost track of a source. */
    private fun step(
        from: List<WakeupSourceStatEntity>,
        to: List<WakeupSourceStatEntity>,
        byName: MutableMap<String, SourceChange>,
    ): Boolean {
        val start = from.associateBy { it.instance }
        val end = to.associateBy { it.instance }
        var partial = start.values.any { s -> end[s.instance]?.name != s.name }
        for (e in end.values) {
            val s = start[e.instance]
            val change =
                when {
                    s == null || s.name != e.name -> SourceChange(e.totalTimeMs, e.activeCount, e.wakeupCount)
                    fell(s, e) -> null
                    else -> SourceChange(e.totalTimeMs - s.totalTimeMs, e.activeCount - s.activeCount, e.wakeupCount - s.wakeupCount)
                }
            if (change == null) partial = true else byName.merge(e.name, change, SourceChange::plus)
        }
        return partial
    }

    private fun sameBoot(
        from: KernelEnd,
        to: KernelEnd,
    ): Boolean {
        val a = from.read ?: return false
        val b = to.read ?: return false
        return a.kernelBoot == b.kernelBoot
    }

    /** The cumulative fields; `activeTimeMs` (the current stretch) and `lastChangeMs` (a time stamp) are not. */
    private fun fell(
        s: WakeupSourceStatEntity,
        e: WakeupSourceStatEntity,
    ): Boolean =
        e.activeCount < s.activeCount ||
            e.eventCount < s.eventCount ||
            e.wakeupCount < s.wakeupCount ||
            e.expireCount < s.expireCount ||
            e.totalTimeMs < s.totalTimeMs ||
            e.maxTimeMs < s.maxTimeMs ||
            e.preventSuspendTimeMs < s.preventSuspendTimeMs

    /** The records one read vouches for: after its cursor, or from its first record, to its last, minus its gaps. */
    private fun covered(read: KernelReadEntity): List<LongRange> {
        val last = read.kmsgLastSeq ?: return emptyList()
        val first = read.kmsgAfterSeq?.plus(1) ?: read.kmsgFirstSeq ?: return emptyList()
        if (last < first) return emptyList()
        val out = ArrayList<LongRange>()
        var next = first
        for (gap in read.kmsgGapList.orEmpty().sortedBy { it.from }) {
            if (gap.from > next) out += next..minOf(gap.from - 1, last)
            next = maxOf(next, gap.to + 1)
        }
        if (next <= last) out += next..last
        return out
    }

    private fun merged(ranges: List<LongRange>): List<LongRange> {
        val out = ArrayList<LongRange>()
        for (r in ranges.sortedBy { it.first }) {
            val tail = out.lastOrNull()
            if (tail != null && r.first <= tail.last + 1) out[out.lastIndex] = tail.first..maxOf(tail.last, r.last) else out += r
        }
        return out
    }

    private fun covers(
        merged: List<LongRange>,
        target: LongRange,
    ): Boolean = target.isEmpty() || merged.any { it.first <= target.first && target.last <= it.last }

    private fun Long.toIntCapped(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private data class SourceChange(
        val totalMs: Long,
        val count: Long,
        val wakeups: Long,
    ) {
        operator fun plus(other: SourceChange) = SourceChange(totalMs + other.totalMs, count + other.count, wakeups + other.wakeups)
    }
}

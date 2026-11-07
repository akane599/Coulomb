// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.history

import io.github.akane599.coulomb.core.database.dao.NewHistoryEvent
import io.github.akane599.coulomb.core.database.entity.HistoryGapEntity
import io.github.akane599.coulomb.core.model.HistoryGapReason
import io.github.akane599.coulomb.core.model.history.BatteryHistory
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import io.github.akane599.coulomb.core.model.history.HistoryRecord

/**
 * The compared part of the history state at the end of a read: state flags that are on (value 1)
 * and the compared values. A kind that is absent is off or 0, as in a restated record.
 */
internal data class HistoryState(
    val values: Map<HistoryKind, Long>,
)

/** Where the previous stored read ended. */
internal data class ReadEnd(
    val bootSeq: Int,
    /** That boot's history offset: monotonic = elapsed + offset, in ms. */
    val offsetMs: Long,
    /** The last record's monotonic time (`NEXT − 1`). */
    val lastMonotonicMs: Long,
    val lastElapsedNs: Long,
    /** Null when not known, as on the first read after Coulomb's process started. */
    val state: HistoryState?,
)

/** One history read as the collector hands it over (ADR-0018). */
internal data class ReadInput(
    val records: List<HistoryRecord>,
    val history: BatteryHistory,
    /** The `--history-start` value the read used. */
    val cursorMs: Long,
    /** Null on the first read ever. */
    val previous: ReadEnd?,
    val bootSeq: Int,
    /** The current boot's stored offset, or null when none is known yet. */
    val storedOffsetMs: Long?,
    /** `wallMs − elapsedMs`, read right after the dump. */
    val wallMinusElapsedMs: Long,
    val nowElapsedMs: Long,
    /** `ACTION_TIME_CHANGED` arrived while the read ran: no pairing can be trusted. */
    val clockChanged: Boolean,
)

internal data class ReadResult(
    val events: List<NewHistoryEvent>,
    val gaps: List<HistoryGapEntity>,
    /** The cursor to store; null keeps the old one, so the next read starts at the same place. */
    val nextCursorMs: Long?,
    /** The current boot's offset when this read found it, or found a new one after a `START`. */
    val newOffsetMs: Long?,
    /** Where this read ended, or the previous end when it stored nothing. */
    val end: ReadEnd?,
    /** Kinds that turned on or off in the read (not restated): unseen plug and screen changes. */
    val transitions: Set<HistoryKind>,
)

/**
 * Turns a parsed history read into events and gaps on Coulomb's clock (ADR-0018). Pure.
 *
 * A read is cut into segments at each `START` (a boot). The part before the first `START` belongs
 * to the boot the previous read ended in; the part after the last belongs to the current boot;
 * segments in between belong to boots Coulomb did not run in and become a gap. Each boot's offset
 * comes from a wall-clock pairing (`TIME` or `RESET:TIME`) against `wall − elapsed` now.
 */
internal object HistoryIngest {
    /** Kinds not stored (ADR-0018): 41% of the phone fixture's changes, all duplicated elsewhere. */
    val DROPPED: Set<HistoryKind> =
        setOf(
            HistoryKind.CELLULAR_HIGH_TX_POWER,
            HistoryKind.STATE_CHANGE,
            HistoryKind.COLLECT_EXTERNAL_STATS,
            HistoryKind.DISPLAY_STATE,
            HistoryKind.BATTERY_CHARGE,
        )

    /** Kinds whose restated value is compared with the stored state (ADR-0018). */
    val COMPARED_FLAGS: Set<HistoryKind> =
        setOf(
            HistoryKind.SCREEN,
            HistoryKind.PLUGGED,
            HistoryKind.CHARGING,
            HistoryKind.SCREEN_DOZE,
            HistoryKind.POWER_SAVE,
            HistoryKind.GPS,
        )
    val COMPARED_VALUES: Set<HistoryKind> =
        setOf(
            HistoryKind.BATTERY_LEVEL,
            HistoryKind.BATTERY_PLUG,
            HistoryKind.BATTERY_STATUS,
            HistoryKind.DEVICE_IDLE,
        )

    /** How far a pairing may place the last record after now: the pairing's own rounding. */
    private const val PAIRING_SLACK_MS = 1_000L
    private const val NS_PER_MS = 1_000_000L

    fun ingest(input: ReadInput): ReadResult {
        val history = input.history
        val next = history.nextCursorMs
        val first = history.firstRecordMs
        // A cursor above NEXT is kept (ADR-0014).
        return when {
            next == null -> unchanged(input)
            history.recordCount == 0L -> unchanged(input).copy(nextCursorMs = maxOf(input.cursorMs, next))
            first == null -> unplaced(input, next, maxOf(input.cursorMs, next))
            else -> placed(input, first, next)
        }
    }

    private fun placed(
        input: ReadInput,
        first: Long,
        next: Long,
    ): ReadResult {
        val segments = segments(input.records)
        // The last segment is the current boot's. Without a START in the read, all of it is: a
        // reboot always writes one, so the records cannot belong to an earlier boot.
        val current = segments.last()
        val startedHere = current.firstOrNull()?.kind == HistoryKind.BOOT
        // Without the current boot's offset nothing can be placed: keep the cursor and read again.
        val offset = currentOffset(input, current, first, next, startedHere) ?: return unchanged(input)
        val newOffset = offset.takeIf { input.storedOffsetMs == null || startedHere }
        return Placer(input, first, next, maxOf(input.cursorMs, next), segments, offset).place(newOffset)
    }

    /** Nothing usable: keep everything as it was. */
    private fun unchanged(input: ReadInput) = ReadResult(emptyList(), emptyList(), null, null, input.previous, emptySet())

    /**
     * Times were not placed (a record failed to print, or a record's end was lost). Nothing is
     * stored; the read's span becomes a gap and the next read goes on from its `NEXT:`.
     */
    private fun unplaced(
        input: ReadInput,
        next: Long,
        cursor: Long,
    ): ReadResult {
        val previous = input.previous
        val gap =
            HistoryGapEntity(
                startMonotonicMs = previous?.lastMonotonicMs ?: input.cursorMs,
                endMonotonicMs = next - 1,
                startBootSeq = previous?.bootSeq ?: input.bootSeq,
                startElapsedNs = previous?.lastElapsedNs ?: 0,
                endBootSeq = input.bootSeq,
                endElapsedNs = input.nowElapsedMs * NS_PER_MS,
                reason = HistoryGapReason.READ_DESYNC,
            )
        return ReadResult(emptyList(), listOf(gap), cursor, null, previous?.copy(state = null), emptySet())
    }

    /** The records cut at each `BOOT`; a `BOOT` record opens its segment. Never empty. */
    private fun segments(records: List<HistoryRecord>): List<List<HistoryRecord>> {
        val segments = mutableListOf(mutableListOf<HistoryRecord>())
        for (record in records) {
            if (record.kind == HistoryKind.BOOT) segments += mutableListOf<HistoryRecord>()
            segments.last() += record
        }
        if (segments.size > 1 && segments.first().isEmpty()) segments.removeAt(0)
        return segments
    }

    /**
     * The current boot's offset: from the segment's latest wall-clock pairing when it checks out,
     * else the stored one, unless the boot started in this read (a `START` means a new timeshift).
     */
    private fun currentOffset(
        input: ReadInput,
        segment: List<HistoryRecord>,
        first: Long,
        next: Long,
        startedHere: Boolean,
    ): Long? {
        val paired =
            segment
                .lastOrNull { (it.kind == HistoryKind.WALL_CLOCK || it.kind == HistoryKind.RESET) && it.value != null }
                ?.let { pairing ->
                    val monotonic = first + pairing.offsetMs
                    monotonic - (checkNotNull(pairing.value) - input.wallMinusElapsedMs)
                }?.takeIf { offset ->
                    val firstInSegment = first + (segment.firstOrNull()?.offsetMs ?: 0)
                    !input.clockChanged &&
                        next - 1 - offset <= input.nowElapsedMs + PAIRING_SLACK_MS &&
                        firstInSegment - offset >= -PAIRING_SLACK_MS
                }
        // A START means a new timeshift, so a stored offset from before it no longer holds.
        return if (startedHere) paired else input.storedOffsetMs ?: paired
    }

    /** Places one read whose current boot's offset is known. */
    private class Placer(
        private val input: ReadInput,
        private val first: Long,
        private val next: Long,
        private val cursor: Long,
        private val segments: List<List<HistoryRecord>>,
        private val offset: Long,
    ) {
        private val currentIndex = segments.lastIndex
        private val events = ArrayList<NewHistoryEvent>()
        private val gaps = ArrayList<HistoryGapEntity>()
        private val transitions = HashSet<HistoryKind>()
        private val previous = input.previous

        /** The end of what was stored last, as a gap's start. */
        private var lastBoot = previous?.bootSeq
        private var lastElapsedNs = previous?.lastElapsedNs
        private var lastMonotonic = previous?.lastMonotonicMs
        private var state: MutableMap<HistoryKind, Long>? = previous?.state?.values?.toMutableMap()

        fun place(newOffset: Long?): ReadResult {
            for ((index, segment) in segments.withIndex()) {
                val target = target(index)
                if (target == null) {
                    skipped(segment)
                } else {
                    store(index, segment, target.first, target.second)
                }
            }
            val lastMonotonicMs = next - 1
            val end =
                ReadEnd(
                    bootSeq = input.bootSeq,
                    offsetMs = offset,
                    lastMonotonicMs = lastMonotonicMs,
                    lastElapsedNs = (lastMonotonicMs - offset) * NS_PER_MS,
                    state = state?.let { HistoryState(it.toMap()) },
                )
            return ReadResult(events, gaps, cursor, newOffset, end, transitions)
        }

        /** The boot and offset a segment's events go to, or null when they are not stored. */
        private fun target(index: Int): Pair<Int, Long>? =
            when {
                index == currentIndex -> {
                    input.bootSeq to offset
                }

                // Before the first START: the boot the previous read ended in, if Coulomb knows it.
                index == 0 && previous != null && segments[0].firstOrNull()?.kind != HistoryKind.BOOT -> {
                    previous.bootSeq to
                        previous.offsetMs
                }

                else -> {
                    null
                }
            }

        /** Set by a skipped segment: the gap closes at the next stored record. */
        private var pendingSkip = false

        /** A segment of a boot Coulomb did not run in, or older than Coulomb: a gap, unless first read. */
        private fun skipped(segment: List<HistoryRecord>) {
            state = null
            if (previous != null && segment.isNotEmpty()) pendingSkip = true
        }

        private fun store(
            index: Int,
            segment: List<HistoryRecord>,
            bootSeq: Int,
            segmentOffset: Long,
        ) {
            if (segment.isEmpty()) return
            val firstMonotonic = first + segment.first().offsetMs
            val firstElapsedNs = (firstMonotonic - segmentOffset) * NS_PER_MS
            if ((index == 0 && input.history.gapBeforeFirstRecord) || pendingSkip) {
                gapTo(firstMonotonic, bootSeq, firstElapsedNs, HistoryGapReason.CURSOR_SKIPPED)
                pendingSkip = false
            } else if (index == 0 && segment.first().kind != HistoryKind.BOOT) {
                compareRestated(segment, firstMonotonic, bootSeq, firstElapsedNs)
            }
            var overflowAt: Triple<Long, Int, Long>? = null
            for (record in segment) {
                val monotonic = first + record.offsetMs
                val elapsedNs = (monotonic - segmentOffset) * NS_PER_MS
                overflowAt?.let { (startMono, startBoot, startNs) ->
                    if (monotonic > startMono) {
                        gaps += gap(startMono, startBoot, startNs, monotonic, bootSeq, elapsedNs, HistoryGapReason.OVERFLOW)
                        overflowAt = null
                    }
                }
                if (record.kind == HistoryKind.OVERFLOW) overflowAt = Triple(monotonic, bootSeq, elapsedNs)
                track(record)
                if (record.kind !in DROPPED && elapsedNs >= 0) {
                    events +=
                        NewHistoryEvent(
                            bootSeq,
                            monotonic - segmentOffset,
                            record.kind,
                            record.change,
                            record.baseline,
                            record.uid,
                            record.tag,
                            record.value,
                        )
                }
                lastBoot = bootSeq
                lastElapsedNs = elapsedNs
                lastMonotonic = monotonic
            }
            overflowAt?.let { (startMono, startBoot, startNs) ->
                val endMono = next - 1
                gaps +=
                    gap(startMono, startBoot, startNs, endMono, bootSeq, (endMono - segmentOffset) * NS_PER_MS, HistoryGapReason.OVERFLOW)
            }
        }

        /**
         * The read's first records restate the state. If the compared part differs from the state
         * the previous read ended in, a change was merged into that read's last record: the span
         * between them is a gap, not a transition at this read's first record (ADR-0014).
         */
        private fun compareRestated(
            segment: List<HistoryRecord>,
            firstMonotonic: Long,
            bootSeq: Int,
            firstElapsedNs: Long,
        ) {
            val stored = state ?: return
            val restated = segment.takeWhile { it.baseline || it.offsetMs == segment.first().offsetMs }.filter { it.baseline }
            if (restated.isEmpty()) return
            val now = HashMap<HistoryKind, Long>()
            restated.forEach { apply(now, it) }
            val differs = (COMPARED_FLAGS + COMPARED_VALUES).any { (now[it] ?: 0L) != (stored[it] ?: 0L) }
            if (differs) gapTo(firstMonotonic, bootSeq, firstElapsedNs, HistoryGapReason.STATE_MISMATCH)
        }

        /** Follows the compared state through the read: a restated group replaces it. */
        private var inRestated = false

        private fun track(record: HistoryRecord) {
            if (record.kind == HistoryKind.BOOT) {
                state = HashMap()
                inRestated = false
                return
            }
            if (record.baseline && !inRestated) state = HashMap()
            inRestated = record.baseline
            if (!record.baseline && (record.change == HistoryChange.START || record.change == HistoryChange.STOP)) {
                transitions += record.kind
            }
            state?.let { apply(it, record) }
        }

        private fun apply(
            target: MutableMap<HistoryKind, Long>,
            record: HistoryRecord,
        ) {
            when {
                record.kind in COMPARED_FLAGS && record.change == HistoryChange.START -> target[record.kind] = 1
                record.kind in COMPARED_FLAGS && record.change == HistoryChange.STOP -> target.remove(record.kind)
                record.kind in COMPARED_VALUES && record.change == HistoryChange.SET -> target[record.kind] = record.value ?: 0
            }
        }

        private fun gapTo(
            endMonotonic: Long,
            endBoot: Int,
            endElapsedNs: Long,
            reason: HistoryGapReason,
        ) {
            val startMono = lastMonotonic ?: input.cursorMs
            gaps += gap(startMono, lastBoot ?: endBoot, lastElapsedNs ?: endElapsedNs, endMonotonic, endBoot, endElapsedNs, reason)
        }

        private fun gap(
            startMono: Long,
            startBoot: Int,
            startNs: Long,
            endMono: Long,
            endBoot: Int,
            endNs: Long,
            reason: HistoryGapReason,
        ) = HistoryGapEntity(
            startMonotonicMs = startMono,
            endMonotonicMs = endMono,
            startBootSeq = startBoot,
            startElapsedNs = startNs,
            endBootSeq = endBoot,
            endElapsedNs = endNs,
            reason = reason,
        )
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import io.github.akane599.coulomb.core.collect.history.HistoryIngest
import io.github.akane599.coulomb.core.collect.snapshot.HistoryRead
import io.github.akane599.coulomb.core.collect.snapshot.SourceStatus
import io.github.akane599.coulomb.core.model.history.BatteryHistory
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import io.github.akane599.coulomb.core.model.history.HistoryRecord

/**
 * The fixture's history records that the synthetic world replays: none it writes itself (markers
 * and the compared screen, plug, charge and level state), none the collector drops, no restated ones.
 */
internal class HistoryPool(
    parsed: List<HistoryRecord>,
) {
    val records: List<HistoryRecord> =
        parsed.filter { r ->
            !r.baseline && r.kind !in MARKERS && r.kind !in HistoryIngest.DROPPED &&
                r.kind !in HistoryIngest.COMPARED_FLAGS && r.kind !in HistoryIngest.COMPARED_VALUES
        }
    val spanMs: Long = records.last().offsetMs - records.first().offsetMs
    val distinctTags: Int = records.mapNotNullTo(HashSet()) { it.tag }.size

    private companion object {
        val MARKERS =
            setOf(HistoryKind.BOOT, HistoryKind.RESET, HistoryKind.WALL_CLOCK, HistoryKind.SHUTDOWN, HistoryKind.OVERFLOW)
    }
}

/**
 * The platform's history buffer for the stress test (SPEC 16.3), on its monotonic clock, which runs
 * on across boots (ADR-0018). The pool is tiled at [storedPerDay] stored events; each tiling renames
 * a share of its tags so that about [newTagsPerDay] new tags appear a day, as jobs and alarms do on a
 * real phone. A read returns what `--history-start` would: a wall-clock record and the restated state
 * at the first record, then the records from the cursor to now.
 *
 * Records are held from the last cursor on, so memory stays bounded by the longest stretch without a
 * read (a night).
 */
internal class SyntheticHistory(
    private val pool: HistoryPool,
    startMonotonicMs: Long,
    storedPerDay: Double,
    newTagsPerDay: Double,
    initialState: Map<HistoryKind, Long>,
    firstEpoch: Epoch,
) {
    /** One boot's clocks: monotonic = elapsed + [monotonicMinusElapsedMs]. */
    data class Epoch(
        val startMonotonicMs: Long,
        val monotonicMinusElapsedMs: Long,
        val wallMinusElapsedMs: Long,
    )

    private val cycleMs = pool.records.size / (storedPerDay / DAY_MS)
    private val scale = cycleMs / pool.spanMs
    private val renameShare = (newTagsPerDay * cycleMs / DAY_MS / pool.distinctTags).coerceAtMost(1.0)
    private val firstOffset = pool.records.first().offsetMs
    private val base = startMonotonicMs
    private var cycle = 0L
    private var index = 0

    /** Unread records, with absolute monotonic times in [HistoryRecord.offsetMs]. */
    private val log = ArrayDeque<HistoryRecord>()

    /** The compared state just before the first held record. */
    private var state = HashMap(initialState)
    private var inRestated = false
    private val epochs = ArrayList(listOf(firstEpoch))

    /** Stored events written so far, the restated ones included. */
    var written: Long = 0
        private set

    /** Appends a record of the world at [monotonicMs], after the pool's records up to then. */
    fun append(
        monotonicMs: Long,
        kind: HistoryKind,
        change: HistoryChange,
        value: Long? = null,
        baseline: Boolean = false,
    ) {
        fill(monotonicMs)
        add(HistoryRecord(monotonicMs, kind, change, value, baseline = baseline))
    }

    /** A clean reboot: `SHUTDOWN` in the old boot, then `START`, the time and the state in the new one. */
    fun reboot(
        shutdownMonotonicMs: Long,
        epoch: Epoch,
        now: Map<HistoryKind, Long>,
    ) {
        append(shutdownMonotonicMs, HistoryKind.SHUTDOWN, HistoryChange.SET)
        epochs += epoch
        if (epochs.size > MAX_EPOCHS) epochs.removeAt(0)
        val at = epoch.startMonotonicMs
        append(at, HistoryKind.BOOT, HistoryChange.SET)
        append(at, HistoryKind.WALL_CLOCK, HistoryChange.SET, wallAt(at))
        restate(now).forEach { add(it.copy(offsetMs = at)) }
    }

    /** A stats reset writes `RESET:TIME` and restates the state. */
    fun reset(
        monotonicMs: Long,
        now: Map<HistoryKind, Long>,
    ) {
        append(monotonicMs, HistoryKind.RESET, HistoryChange.SET, wallAt(monotonicMs))
        restate(now).forEach { add(it.copy(offsetMs = monotonicMs)) }
    }

    fun read(
        cursorMs: Long,
        nowMonotonicMs: Long,
    ): HistoryRead {
        fill(nowMonotonicMs)
        while (log.isNotEmpty() && log.first().offsetMs < cursorMs) track(log.removeFirst())
        if (log.isEmpty()) {
            return HistoryRead(emptyList(), BatteryHistory(null, cursorMs, null, 0, false), SourceStatus.OK)
        }
        val first = log.first().offsetMs
        val records = ArrayList<HistoryRecord>(log.size + RESTATED_MAX)
        if (log.first().kind != HistoryKind.BOOT) {
            records += HistoryRecord(0, HistoryKind.WALL_CLOCK, HistoryChange.SET, wallAt(first))
            records += restate(state)
        }
        log.forEach { records += it.copy(offsetMs = it.offsetMs - first) }
        val history = BatteryHistory(null, log.last().offsetMs + 1, first, records.size.toLong(), false)
        return HistoryRead(records, history, SourceStatus.OK)
    }

    private fun add(record: HistoryRecord) {
        log.addLast(record)
        written++
    }

    /** The pool's records up to [monotonicMs]. */
    private fun fill(monotonicMs: Long) {
        while (true) {
            val record = pool.records[index]
            val at = base + ((cycle * pool.spanMs + record.offsetMs - firstOffset) * scale).toLong()
            if (at > monotonicMs) return
            val tag = record.tag?.let { if (renamed(it)) "$it#$cycle" else it }
            add(record.copy(offsetMs = at, tag = tag))
            if (++index == pool.records.size) {
                index = 0
                cycle++
            }
        }
    }

    private fun renamed(tag: String) = (tag.hashCode() and Int.MAX_VALUE) % SHARES < renameShare * SHARES

    /** The state as the first record after `START`, `RESET` or a cursor restates it. */
    private fun restate(values: Map<HistoryKind, Long>): List<HistoryRecord> =
        values.map { (kind, value) ->
            if (kind in HistoryIngest.COMPARED_FLAGS) {
                HistoryRecord(0, kind, HistoryChange.START, baseline = true)
            } else {
                HistoryRecord(0, kind, HistoryChange.SET, value, baseline = true)
            }
        }

    /** The compared state through a record, as HistoryIngest follows it. */
    private fun track(record: HistoryRecord) {
        if (record.kind == HistoryKind.BOOT) {
            state.clear()
            inRestated = false
            return
        }
        if (record.baseline && !inRestated) state.clear()
        inRestated = record.baseline
        when {
            record.kind in HistoryIngest.COMPARED_FLAGS && record.change == HistoryChange.START -> state[record.kind] = 1
            record.kind in HistoryIngest.COMPARED_FLAGS && record.change == HistoryChange.STOP -> state.remove(record.kind)
            record.kind in HistoryIngest.COMPARED_VALUES && record.change == HistoryChange.SET -> state[record.kind] = record.value ?: 0
        }
    }

    private fun wallAt(monotonicMs: Long): Long {
        val epoch = epochs.lastOrNull { it.startMonotonicMs <= monotonicMs } ?: epochs.first()
        return monotonicMs - epoch.monotonicMinusElapsedMs + epoch.wallMinusElapsedMs
    }

    private companion object {
        const val DAY_MS = 86_400_000.0
        const val SHARES = 1_000
        const val RESTATED_MAX = 8
        const val MAX_EPOCHS = 3
    }
}

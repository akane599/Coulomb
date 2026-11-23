// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.database.entity.HistoryEventRow
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind

/** A stored history event placed on the wall clock, with its tag's text. */
internal class TimedEvent(
    val wallMs: Long,
    val row: HistoryEventRow,
    val tag: String?,
)

/**
 * Turns history events into the Timeline's tracks (SPEC 10.6). Pure.
 *
 * A state is known from the first record that restates it (ADR-0014: each read's first state
 * record, [HistoryEventRow.baseline]) and until the boot changes, the phone shuts down or the
 * history has a gap. Before and after, nothing is drawn: a state that was not recorded is not off.
 * A restated state that differs from the one followed so far changed at a time the history did not
 * keep (ADR-0018); the change is placed at the restating record.
 */
internal class TimelineBuilder(
    private val fromWallMs: Long,
    private val toWallMs: Long,
) {
    private class State(
        var known: Boolean = false,
        var value: Int = OFF,
        var since: Long = 0,
        var uid: Int? = null,
        var tag: String? = null,
    )

    private val states = TimelineTrack.entries.associateWith { State() }
    private val spans = TimelineTrack.entries.associateWith { ArrayList<TrackSpan>() }
    private val level = ArrayList<LevelPoint>()
    private val temperature = ArrayList<TemperaturePoint>()
    private val known = ArrayList<LongRange>()
    private var knownSince: Long? = null
    private var boot: Int? = null
    private var newSegment = true

    /** The next temperature reading is a point even when unchanged: a break came before it (audit BUG-015). */
    private var newTemperatureSegment = true
    private var last = Long.MIN_VALUE

    /**
     * [events] in order, from a read's first record at or before the span's start so the state at
     * the start is known. [gaps] are stretches the history lost, on the wall clock. [knownUntilWallMs]
     * is where the stored history ends: open states end there, not at the span's end.
     */
    fun build(
        events: List<TimedEvent>,
        gaps: List<LongRange>,
        knownUntilWallMs: Long,
        current: List<CurrentPoint>,
    ): Timeline {
        val lost = ArrayDeque(gaps.sortedBy { it.first })
        var index = 0
        while (index < events.size) {
            val first = events[index].row
            // One printed record's changes share its time.
            var end = index + 1
            while (end < events.size && sameRecord(events[end].row, first)) end++
            val t = maxOf(events[index].wallMs, last)
            lose(lost, t)
            if (boot != null && boot != first.bootSeq) forget(last)
            boot = first.bootSeq
            last = t
            record(events.subList(index, end), t)
            index = end
        }
        val until = minOf(knownUntilWallMs, toWallMs).coerceAtLeast(last.coerceAtLeast(fromWallMs))
        lose(lost, until)
        return finish(until, current)
    }

    private fun sameRecord(
        a: HistoryEventRow,
        b: HistoryEventRow,
    ): Boolean = a.bootSeq == b.bootSeq && a.elapsedMs == b.elapsedMs

    /** Forgets the state at each gap that starts by [t]. */
    private fun lose(
        lost: ArrayDeque<LongRange>,
        t: Long,
    ) {
        while (lost.isNotEmpty() && lost.first().first <= t) forget(maxOf(lost.removeFirst().first, last))
    }

    /** A record's restated states first, then its changes. */
    private fun record(
        record: List<TimedEvent>,
        t: Long,
    ) {
        val restated = record.filter { it.row.baseline }
        if (restated.isNotEmpty()) restate(restated, t)
        record.forEach { if (!it.row.baseline) change(it, t) }
    }

    private fun finish(
        until: Long,
        current: List<CurrentPoint>,
    ): Timeline {
        val lastLevel = level.lastOrNull()
        if (lastLevel != null && knownSince != null && lastLevel.wallMs < until) {
            level += LevelPoint(until, lastLevel.levelPct, startsSegment = false)
        }
        forget(until)
        val shown = known.mapNotNull { clip(it.first, it.last) }
        return Timeline(
            fromWallMs = fromWallMs,
            toWallMs = toWallMs,
            level = clipLevel(),
            current = current,
            temperature = clipTemperature(),
            tracks = spans.mapValues { (_, list) -> list.mapNotNull(::clip) },
            unknown = complement(shown),
            knownUntilWallMs = shown.lastOrNull()?.last,
        )
    }

    private fun clip(span: TrackSpan): TrackSpan? =
        clip(span.fromWallMs, span.toWallMs)?.let {
            span.copy(fromWallMs = it.first, toWallMs = it.last)
        }

    /** The whole state as [restated] names it: a flag or value it leaves out is off or zero. */
    private fun restate(
        restated: List<TimedEvent>,
        t: Long,
    ) {
        if (knownSince == null) knownSince = t
        for ((kind, track) in FLAGS) {
            val on = restated.firstOrNull { it.row.type == kind && it.row.change == HistoryChange.START }
            set(track, if (on != null) TrackSpan.ON else OFF, t, on?.row?.uid, on?.tag)
        }
        for ((kind, track) in VALUES) {
            val value = restated.firstOrNull { it.row.type == kind && it.row.change == HistoryChange.SET }
            set(track, value?.row?.value?.toInt() ?: OFF, t)
        }
        restated.forEach { if (it.row.type in READINGS) reading(it.row, t) }
    }

    private fun change(
        event: TimedEvent,
        t: Long,
    ) {
        val row = event.row
        val flag = FLAGS[row.type]
        val value = VALUES[row.type]
        when {
            flag != null -> flag(flag, event, t)
            value != null -> row.value?.let { set(value, it.toInt(), t) }
            row.type == HistoryKind.BOOT || row.type == HistoryKind.SHUTDOWN -> forget(t)
            row.type == HistoryKind.TOP_APP -> topApp(event, t)
            row.type == HistoryKind.WAKE_REASON -> wakeReason(event, t)
            else -> reading(row, t)
        }
    }

    /** START and STOP turn the track on and off; SET only names a holder (a wakelock's). */
    private fun flag(
        track: TimelineTrack,
        event: TimedEvent,
        t: Long,
    ) {
        when (event.row.change) {
            HistoryChange.START -> set(track, TrackSpan.ON, t, event.row.uid, event.tag)
            HistoryChange.STOP -> set(track, OFF, t)
            HistoryChange.SET -> states.getValue(track).let { if (it.known && it.tag == null) it.tag = event.tag }
        }
    }

    private fun reading(
        row: HistoryEventRow,
        t: Long,
    ) {
        val value = row.value?.toInt() ?: return
        when (row.type) {
            HistoryKind.BATTERY_LEVEL -> level(value, t)
            HistoryKind.BATTERY_TEMPERATURE -> temperature(value, t)
            else -> Unit
        }
    }

    /** Why the CPU woke: the reason recorded as it began to run names that stretch. */
    private fun wakeReason(
        event: TimedEvent,
        t: Long,
    ) {
        val awake = states.getValue(TimelineTrack.AWAKE)
        val running = awake.known && awake.value != OFF
        if (running && awake.tag == null && t - awake.since <= WAKE_REASON_WINDOW_MS) awake.tag = event.tag
    }

    /** The app on top: from its START to its STOP, or to the next app's START. */
    private fun topApp(
        event: TimedEvent,
        t: Long,
    ) {
        val state = states.getValue(TimelineTrack.TOP_APP)
        if (event.row.change == HistoryChange.START) {
            close(TimelineTrack.TOP_APP, t)
            state.known = true
            state.value = TrackSpan.ON
            state.since = t
            state.uid = event.row.uid
            state.tag = event.tag
        } else if (event.row.change == HistoryChange.STOP && state.known && state.uid == event.row.uid) {
            set(TimelineTrack.TOP_APP, OFF, t)
        }
    }

    private fun level(
        percent: Int,
        t: Long,
    ) {
        if (!newSegment && level.lastOrNull()?.levelPct == percent) return
        level += LevelPoint(t, percent, startsSegment = newSegment && level.isNotEmpty())
        newSegment = false
    }

    private fun temperature(
        deciC: Int,
        t: Long,
    ) {
        if (newTemperatureSegment || temperature.lastOrNull()?.deciC != deciC) temperature += TemperaturePoint(t, deciC)
        newTemperatureSegment = false
    }

    private fun set(
        track: TimelineTrack,
        value: Int,
        t: Long,
        uid: Int? = null,
        tag: String? = null,
    ) {
        val state = states.getValue(track)
        if (state.known && state.value == value) {
            if (state.tag == null && value != OFF) {
                state.tag = tag
                state.uid = state.uid ?: uid
            }
            return
        }
        close(track, t)
        state.known = true
        state.value = value
        state.since = t
        state.uid = uid
        state.tag = tag
    }

    /** Ends the track's state at [t]; a state that is on, or any signal strength, becomes a span. */
    private fun close(
        track: TimelineTrack,
        t: Long,
    ) {
        val state = states.getValue(track)
        val drawn = state.known && (state.value != OFF || track == TimelineTrack.SIGNAL)
        if (drawn && t > state.since) spans.getValue(track) += TrackSpan(state.since, t, state.value, state.uid, state.tag)
    }

    /** From [t] nothing is known until a record restates the state. */
    private fun forget(t: Long) {
        TimelineTrack.entries.forEach { track ->
            close(track, t)
            states.getValue(track).known = false
        }
        knownSince?.let { if (t > it) known += it..t }
        knownSince = null
        newSegment = true
        newTemperatureSegment = true
    }

    private fun clip(
        from: Long,
        to: Long,
    ): LongRange? = (maxOf(from, fromWallMs)..minOf(to, toWallMs)).takeIf { it.first < it.last }

    /** The level inside the span, with the last value before it carried to the span's start. */
    private fun clipLevel(): List<LevelPoint> {
        val inside = level.filter { it.wallMs in fromWallMs..toWallMs }
        val before = level.lastOrNull { it.wallMs < fromWallMs } ?: return inside
        // Carried only within one stretch of known history: across a break the level at the start is not known.
        if (known.none { before.wallMs in it && fromWallMs in it }) return inside
        return listOf(LevelPoint(fromWallMs, before.levelPct, startsSegment = false)) + inside
    }

    /** The readings inside the span, with the last one before it carried to the span's start, as [clipLevel] does. */
    private fun clipTemperature(): List<TemperaturePoint> {
        val inside = temperature.filter { it.wallMs in fromWallMs..toWallMs }
        // Carried only within one stretch of known history, and not beside a reading at the start itself.
        val before =
            temperature
                .lastOrNull { it.wallMs < fromWallMs }
                ?.takeIf { inside.firstOrNull()?.wallMs != fromWallMs }
                ?.takeIf { reading -> known.any { reading.wallMs in it && fromWallMs in it } }
        return listOfNotNull(before?.let { TemperaturePoint(fromWallMs, it.deciC) }) + inside
    }

    private fun complement(shown: List<LongRange>): List<TrackSpan> {
        val out = ArrayList<TrackSpan>()
        var at = fromWallMs
        for (range in shown) {
            if (range.first > at) out += TrackSpan(at, range.first)
            at = maxOf(at, range.last)
        }
        if (at < toWallMs) out += TrackSpan(at, toWallMs)
        return out
    }

    companion object {
        private const val OFF = 0

        /** A wake reason is printed with the record that starts the CPU, or the next one. */
        private const val WAKE_REASON_WINDOW_MS = 2_000L

        /** The kinds [TimelineBuilder] reads; a reader need not load the others. */
        val KINDS: Set<HistoryKind> by lazy {
            FLAGS.keys + VALUES.keys + READINGS +
                setOf(HistoryKind.BOOT, HistoryKind.SHUTDOWN, HistoryKind.TOP_APP, HistoryKind.WAKE_REASON)
        }

        /** On or off, START and STOP. */
        private val FLAGS: Map<HistoryKind, TimelineTrack> =
            mapOf(
                HistoryKind.SCREEN to TimelineTrack.SCREEN,
                HistoryKind.PLUGGED to TimelineTrack.CHARGING,
                HistoryKind.CPU_RUNNING to TimelineTrack.AWAKE,
                HistoryKind.WAKE_LOCK to TimelineTrack.WAKELOCKS,
                HistoryKind.MOBILE_RADIO to TimelineTrack.MOBILE_RADIO,
                HistoryKind.WIFI_RADIO to TimelineTrack.WIFI,
                HistoryKind.GPS to TimelineTrack.GPS,
            )

        /** A small number, SET. */
        private val VALUES: Map<HistoryKind, TimelineTrack> =
            mapOf(
                HistoryKind.DEVICE_IDLE to TimelineTrack.DOZE,
                HistoryKind.PHONE_SIGNAL_STRENGTH to TimelineTrack.SIGNAL,
            )

        /** Battery readings, which a restating record always names. */
        private val READINGS: Set<HistoryKind> = setOf(HistoryKind.BATTERY_LEVEL, HistoryKind.BATTERY_TEMPERATURE)
    }
}

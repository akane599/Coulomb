// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.HistoryEventRow
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import org.junit.Test

/** SPEC 10.6: the tracks a stretch of history events draws. Times are seconds on a wall clock that starts at 0. */
class TimelineBuilderTest {
    private var nextId = 1L

    private fun at(
        second: Long,
        kind: HistoryKind,
        change: HistoryChange,
        value: Long? = null,
        baseline: Boolean = false,
        uid: Int? = null,
        tag: String? = null,
        boot: Int = 1,
    ) = TimedEvent(second * 1_000, HistoryEventRow(nextId++, boot, second * 1_000, kind, change, baseline, uid, null, value), tag)

    private fun on(
        second: Long,
        kind: HistoryKind,
        tag: String? = null,
        uid: Int? = null,
    ) = at(second, kind, HistoryChange.START, uid = uid, tag = tag)

    private fun off(
        second: Long,
        kind: HistoryKind,
        uid: Int? = null,
    ) = at(second, kind, HistoryChange.STOP, uid = uid)

    private fun set(
        second: Long,
        kind: HistoryKind,
        value: Long,
        tag: String? = null,
    ) = at(second, kind, HistoryChange.SET, value, tag = tag)

    /** A read's first record: the level, and the flags and values that are on. */
    private fun restated(
        second: Long,
        level: Long,
        vararg on: HistoryKind,
        doze: Long? = null,
        boot: Int = 1,
    ): List<TimedEvent> =
        listOf(at(second, HistoryKind.BATTERY_LEVEL, HistoryChange.SET, level, baseline = true, boot = boot)) +
            on.map { at(second, it, HistoryChange.START, baseline = true, boot = boot) } +
            listOfNotNull(doze?.let { at(second, HistoryKind.DEVICE_IDLE, HistoryChange.SET, it, baseline = true, boot = boot) })

    private fun build(
        events: List<TimedEvent>,
        from: Long = 0,
        to: Long = 1_000,
        knownUntil: Long = to,
        gaps: List<LongRange> = emptyList(),
    ) = TimelineBuilder(
        from * 1_000,
        to * 1_000,
    ).build(events, gaps.map { it.first * 1_000..it.last * 1_000 }, knownUntil * 1_000, emptyList())

    private fun Timeline.seconds(track: TimelineTrack) = tracks.getValue(track).map { it.fromWallMs / 1_000 to it.toWallMs / 1_000 }

    @Test
    fun aFlagIsDrawnFromItsStartToItsStop() {
        val timeline =
            build(
                restated(0, 80) +
                    on(100, HistoryKind.SCREEN) + off(250, HistoryKind.SCREEN) +
                    on(400, HistoryKind.SCREEN),
                knownUntil = 600,
            )

        // The last stretch is still on: it ends where the stored history does, not at the span's end.
        assertThat(timeline.seconds(TimelineTrack.SCREEN)).containsExactly(100L to 250L, 400L to 600L).inOrder()
        assertThat(timeline.unknown).containsExactly(TrackSpan(600_000, 1_000_000))
        assertThat(timeline.knownUntilWallMs).isEqualTo(600_000)
    }

    @Test
    fun theStateAtTheSpansStartComesFromTheRecordThatRestatesIt() {
        // The read starts before the span: the screen was on, the phone plugged in, in no Doze.
        val events =
            restated(0, 80, HistoryKind.SCREEN, HistoryKind.PLUGGED) +
                off(500, HistoryKind.SCREEN) +
                set(600, HistoryKind.DEVICE_IDLE, 1) + set(700, HistoryKind.DEVICE_IDLE, 2) + set(900, HistoryKind.DEVICE_IDLE, 0)

        val timeline = build(events, from = 300)

        assertThat(timeline.seconds(TimelineTrack.SCREEN)).containsExactly(300L to 500L)
        assertThat(timeline.seconds(TimelineTrack.CHARGING)).containsExactly(300L to 1_000L)
        assertThat(timeline.tracks.getValue(TimelineTrack.DOZE).map { it.value to it.durationMs / 1_000 })
            .containsExactly(TrackSpan.DOZE_LIGHT to 100L, TrackSpan.DOZE_DEEP to 200L)
            .inOrder()
        assertThat(timeline.unknown).isEmpty()
        // The level before the span is carried to its start.
        assertThat(timeline.level.first()).isEqualTo(LevelPoint(300_000, 80, startsSegment = false))
    }

    @Test
    fun nothingIsDrawnBeforeAStateIsKnown() {
        // No restating record: the first thing known about the screen is that it turned off at 200.
        val timeline = build(listOf(off(200, HistoryKind.SCREEN), on(300, HistoryKind.SCREEN), off(350, HistoryKind.SCREEN)))

        assertThat(timeline.seconds(TimelineTrack.SCREEN)).containsExactly(300L to 350L)
        assertThat(timeline.unknown).containsExactly(TrackSpan(0, 1_000_000))
    }

    @Test
    fun aLaterRestatementMovesAStateItFindsChanged() {
        // The screen went off at a time the history did not keep; the next read finds it off.
        val timeline = build(restated(0, 80, HistoryKind.SCREEN) + restated(400, 79) + restated(600, 79, HistoryKind.GPS))

        assertThat(timeline.seconds(TimelineTrack.SCREEN)).containsExactly(0L to 400L)
        assertThat(timeline.seconds(TimelineTrack.GPS)).containsExactly(600L to 1_000L)
    }

    @Test
    fun aNewBootEndsEveryStateAndBreaksTheLevelLine() {
        val events =
            restated(0, 80, HistoryKind.SCREEN) + set(100, HistoryKind.BATTERY_LEVEL, 79) +
                listOf(at(500, HistoryKind.BOOT, HistoryChange.SET, boot = 2)) +
                restated(510, 77, HistoryKind.SCREEN, boot = 2)

        val timeline = build(events)

        // The phone was off between the last record of the first boot and the second boot's first.
        assertThat(timeline.seconds(TimelineTrack.SCREEN)).containsExactly(0L to 100L, 510L to 1_000L).inOrder()
        assertThat(timeline.unknown).containsExactly(TrackSpan(100_000, 510_000))
        assertThat(timeline.level).containsExactly(
            LevelPoint(0, 80, false),
            LevelPoint(100_000, 79, false),
            LevelPoint(510_000, 77, startsSegment = true),
            LevelPoint(1_000_000, 77, false),
        )
    }

    @Test
    fun aGapInTheHistoryIsUnknownUntilTheStateIsRestated() {
        val events = restated(0, 80, HistoryKind.SCREEN) + restated(400, 78, HistoryKind.SCREEN)

        val timeline = build(events, gaps = listOf(200L..400L))

        assertThat(timeline.seconds(TimelineTrack.SCREEN)).containsExactly(0L to 200L, 400L to 1_000L).inOrder()
        assertThat(timeline.unknown).containsExactly(TrackSpan(200_000, 400_000))
    }

    @Test
    fun theTopAppRunsToItsStopOrToTheNextApp() {
        val events =
            restated(0, 80) +
                on(100, HistoryKind.TOP_APP, "app.one", 10_001) +
                on(200, HistoryKind.TOP_APP, "app.two", 10_002) +
                // The first app's stop arrives after the second took over, and ends nothing.
                off(201, HistoryKind.TOP_APP, 10_001) +
                off(300, HistoryKind.TOP_APP, 10_002)

        val apps = build(events).tracks.getValue(TimelineTrack.TOP_APP)

        assertThat(apps).containsExactly(
            TrackSpan(100_000, 200_000, uid = 10_001, tag = "app.one"),
            TrackSpan(200_000, 300_000, uid = 10_002, tag = "app.two"),
        )
    }

    @Test
    fun aWakelockNamesItsFirstHolderAndAWakeupItsReason() {
        val events =
            restated(0, 80) +
                on(100, HistoryKind.CPU_RUNNING) +
                set(100, HistoryKind.WAKE_REASON, 0, tag = "alarm irq") +
                on(101, HistoryKind.WAKE_LOCK) +
                at(102, HistoryKind.WAKE_LOCK, HistoryChange.SET, uid = 10_001, tag = "sync") +
                off(110, HistoryKind.WAKE_LOCK) +
                // A reason recorded long after the CPU started does not rename the stretch.
                set(150, HistoryKind.WAKE_REASON, 0, tag = "later") +
                off(160, HistoryKind.CPU_RUNNING)

        val timeline = build(events)

        assertThat(timeline.tracks.getValue(TimelineTrack.AWAKE)).containsExactly(TrackSpan(100_000, 160_000, tag = "alarm irq"))
        assertThat(timeline.tracks.getValue(TimelineTrack.WAKELOCKS)).containsExactly(TrackSpan(101_000, 110_000, tag = "sync"))
    }

    @Test
    fun everySignalStrengthIsDrawnAndTheTemperatureIsAListOfReadings() {
        val events =
            restated(0, 80) +
                set(100, HistoryKind.PHONE_SIGNAL_STRENGTH, 3) +
                set(100, HistoryKind.BATTERY_TEMPERATURE, 301) +
                set(150, HistoryKind.BATTERY_TEMPERATURE, 301) +
                set(200, HistoryKind.PHONE_SIGNAL_STRENGTH, 0) +
                set(300, HistoryKind.BATTERY_TEMPERATURE, 315)

        val timeline = build(events)

        // "None" is a strength too: the restating record that names none means none.
        assertThat(timeline.tracks.getValue(TimelineTrack.SIGNAL).map { Triple(it.fromWallMs / 1_000, it.toWallMs / 1_000, it.value) })
            .containsExactly(Triple(0L, 100L, 0), Triple(100L, 200L, 3), Triple(200L, 1_000L, 0))
            .inOrder()
        assertThat(timeline.temperature).containsExactly(TemperaturePoint(100_000, 301), TemperaturePoint(300_000, 315)).inOrder()
        assertThat(timeline.tracks.getValue(TimelineTrack.TEMPERATURE)).isEmpty()
    }

    @Test
    fun aSpanWithoutHistoryIsEmptyAndWhollyUnknown() {
        val timeline = build(emptyList(), from = 100, to = 200)

        assertThat(timeline.empty).isTrue()
        assertThat(timeline.unknown).containsExactly(TrackSpan(100_000, 200_000))
        assertThat(timeline.knownUntilWallMs).isNull()
    }

    /**
     * Audit BUG-015: the history restates the temperature after a gap. An unchanged value is still a
     * reading, and the chart's line starts again from it.
     */
    @Test
    fun aTemperatureRestatedAfterAGapStartsItsLineAgain() {
        val restated = { second: Long ->
            listOf(
                at(second, HistoryKind.BATTERY_LEVEL, HistoryChange.SET, 80, baseline = true),
                at(second, HistoryKind.BATTERY_TEMPERATURE, HistoryChange.SET, 250, baseline = true),
            )
        }
        val timeline =
            TimelineBuilder(
                0,
                3_600_000,
            ).build(restated(0) + restated(1_200), listOf(600_000L..1_200_000L), 3_600_000, emptyList())

        assertThat(timeline.temperature).containsExactly(TemperaturePoint(0, 250), TemperaturePoint(1_200_000, 250)).inOrder()
    }

    /** A steady temperature read before the span is the span's temperature, as its level is. */
    @Test
    fun aSteadyTemperatureFromBeforeTheSpanIsCarriedIn() {
        val events =
            listOf(
                at(0, HistoryKind.BATTERY_LEVEL, HistoryChange.SET, 80, baseline = true),
                at(0, HistoryKind.BATTERY_TEMPERATURE, HistoryChange.SET, 250, baseline = true),
            )
        val timeline = TimelineBuilder(300_000, 3_600_000).build(events, emptyList(), 3_600_000, emptyList())

        assertThat(timeline.temperature).containsExactly(TemperaturePoint(300_000, 250))
        // Not across a break: there the temperature at the span's start is not known.
        val broken = TimelineBuilder(300_000, 3_600_000).build(events, listOf(100_000L..200_000L), 3_600_000, emptyList())
        assertThat(broken.temperature).isEmpty()
    }
}

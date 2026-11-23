// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.data.CurrentPoint
import io.github.akane599.coulomb.core.data.LevelPoint
import io.github.akane599.coulomb.core.data.TemperaturePoint
import io.github.akane599.coulomb.core.data.Timeline
import io.github.akane599.coulomb.core.data.TimelineTrack
import io.github.akane599.coulomb.core.data.TrackSpan
import org.junit.Test

class TimelineLookupTest {
    private val t0 = 1_000 * HOUR

    private fun at(minutes: Int) = t0 + minutes * MINUTE

    /** Two hours; the first quarter hour has no history. */
    private val timeline =
        Timeline(
            fromWallMs = at(0),
            toWallMs = at(120),
            level = listOf(LevelPoint(at(15), 80, false), LevelPoint(at(60), 79, false), LevelPoint(at(110), 78, false)),
            current = listOf(CurrentPoint(at(30), -410.0), CurrentPoint(at(31), -400.0), CurrentPoint(at(90), -46.0)),
            temperature = emptyList(),
            tracks =
                TimelineTrack.entries.associateWith { emptyList<TrackSpan>() } +
                    mapOf(
                        TimelineTrack.SCREEN to listOf(TrackSpan(at(20), at(40))),
                        TimelineTrack.DOZE to
                            listOf(TrackSpan(at(55), at(70), TrackSpan.DOZE_LIGHT), TrackSpan(at(70), at(120), TrackSpan.DOZE_DEEP)),
                        TimelineTrack.AWAKE to
                            listOf(
                                TrackSpan(at(20), at(41), tag = "screen"),
                                TrackSpan(at(60), at(60) + 4_000, tag = "alarm"),
                                TrackSpan(at(61), at(61) + 4_000, tag = "alarm"),
                                TrackSpan(at(62), at(62) + 4_000, tag = "modem"),
                                TrackSpan(at(100), at(100) + 4_000, tag = "modem"),
                            ),
                    ),
            unknown = listOf(TrackSpan(at(0), at(15))),
            knownUntilWallMs = at(120),
        )

    @Test
    fun aMomentSaysWhatIsKnownThere() {
        val screenOn = timeline.moment(at(30))
        assertThat(screenOn.known).isTrue()
        assertThat(screenOn.levelPct).isEqualTo(80)
        assertThat(screenOn.screenOn).isTrue()
        assertThat(screenOn.doze).isEqualTo(0)
        assertThat(screenOn.currentMa).isEqualTo(-410.0)

        val asleep = timeline.moment(at(75))
        assertThat(asleep.screenOn).isFalse()
        assertThat(asleep.doze).isEqualTo(TrackSpan.DOZE_DEEP)
        assertThat(asleep.levelPct).isEqualTo(79)
        // The nearest reading is a quarter of an hour away: not this moment's.
        assertThat(asleep.currentMa).isNull()
    }

    @Test
    fun aMomentWithoutHistoryIsNotOff() {
        val unknown = timeline.moment(at(5))

        assertThat(unknown.known).isFalse()
        assertThat(unknown.screenOn).isNull()
        assertThat(unknown.levelPct).isNull()
    }

    @Test
    fun aTapOnALongStretchPicksItAlone() {
        val picked = timeline.pick(TimelineTrack.AWAKE, at(30), reachMs = 2 * MINUTE)

        assertThat(picked?.spans?.map { it.tag }).containsExactly("screen")
    }

    @Test
    fun aTapOnARunOfTicksPicksThemAllWithTheirNames() {
        val picked = checkNotNull(timeline.pick(TimelineTrack.AWAKE, at(61), reachMs = 2 * MINUTE))

        assertThat(picked.spans).hasSize(3)
        assertThat(picked.names()).containsExactly("alarm" to 2, "modem" to 1).inOrder()
        assertThat(picked.totalMs).isEqualTo(12_000)
    }

    @Test
    fun aTapOnNothingPicksNothing() {
        assertThat(timeline.pick(TimelineTrack.AWAKE, at(80), reachMs = 2 * MINUTE)).isNull()
        assertThat(timeline.pick(TimelineTrack.GPS, at(30), reachMs = 2 * MINUTE)).isNull()
    }

    @Test
    fun theCursorTicksWhenAStretchBeginsUnderIt() {
        val tracks = listOf(TimelineTrack.AWAKE, TimelineTrack.DOZE)

        assertThat(timeline.crosses(at(58), at(60), tracks)).isTrue()
        // Backwards too.
        assertThat(timeline.crosses(at(71), at(69), tracks)).isTrue()
        assertThat(timeline.crosses(at(80), at(90), tracks)).isFalse()
        // A hidden track does not tick.
        assertThat(timeline.crosses(at(69), at(71), listOf(TimelineTrack.AWAKE))).isFalse()
    }

    @Test
    fun aCutKeepsWhatIsInViewAndCarriesTheLevelIn() {
        val cut = timeline.cut(TimeWindow(at(30), at(65)))

        assertThat(cut.level.map { it.wallMs to it.levelPct }).containsExactly(at(30) to 80, at(60) to 79).inOrder()
        assertThat(cut.tracks.getValue(TimelineTrack.SCREEN)).containsExactly(TrackSpan(at(30), at(40)))
        assertThat(cut.tracks.getValue(TimelineTrack.AWAKE)).hasSize(4)
        assertThat(cut.unknown).isEmpty()
        assertThat(cut.knownMs).isEqualTo(35 * MINUTE)
    }

    @Test
    fun aCutPastWhatWasReadIsUnknownThere() {
        val cut = timeline.cut(TimeWindow(at(-30), at(30)))

        assertThat(cut.knownMs).isEqualTo(15 * MINUTE)
        assertThat(cut.level.map { it.levelPct }).containsExactly(80)
    }

    @Test
    fun theWakeupRateIsPerHourOfKnownHistory() {
        // Five wakeups in an hour and three quarters of known history.
        assertThat(timeline.wakeupsPerHour()).isWithin(0.01).of(5 / 1.75)
        assertThat(timeline.cut(TimeWindow(at(0), at(10))).wakeupsPerHour()).isNull()
    }

    /**
     * Audit DATA-015: a stretch cut by the view's start, or one the history restated as awake right
     * after a gap, did not begin in view and is no wakeup.
     */
    @Test
    fun onlyAStretchThatBeganInViewIsAWakeup() {
        assertThat(timeline.wakeups).isEqualTo(5)
        // From 0:30 the screen's stretch from 0:20 is cut by the edge.
        assertThat(timeline.cut(TimeWindow(at(30), at(120))).wakeups).isEqualTo(4)
        val afterGap =
            timeline.copy(
                tracks =
                    timeline.tracks + (TimelineTrack.AWAKE to timeline.tracks.getValue(TimelineTrack.AWAKE) + TrackSpan(at(15), at(16))),
            )
        assertThat(afterGap.wakeups).isEqualTo(5)
    }

    /** Audit BUG-054: the signal's and the temperature's lines lift the pen over a stretch without history. */
    @Test
    fun theValueLinesStopAtAGap() {
        val signal =
            signalLine(
                listOf(TrackSpan(at(15), at(30), value = 3), TrackSpan(at(30), at(50), value = 3), TrackSpan(at(70), at(90), value = 3)),
            )
        assertThat(signal.map { it.startsSegment }).containsExactly(true, false, false, false, true, false).inOrder()

        val warm =
            timeline.copy(
                temperature = listOf(TemperaturePoint(at(20), 300), TemperaturePoint(at(40), 320)),
                unknown = listOf(TrackSpan(at(0), at(15)), TrackSpan(at(30), at(35))),
                knownUntilWallMs = at(120),
            )
        val line = warm.temperatureLine()
        // The first reading stands until the gap, the second starts again and stands to the end.
        assertThat(line.map { it.atMs to it.startsSegment })
            .containsExactly(at(20) to false, at(30) to false, at(40) to true, at(120) to false)
            .inOrder()
        assertThat(line[1].share).isEqualTo(0f)
    }

    /** Audit DATA-016: a gap is not off, so a timeline the history did not cover reads as a lower bound. */
    @Test
    fun aTimelineWithAGapIsPartlyKnown() {
        assertThat(timeline.partlyKnown).isTrue()
        assertThat(timeline.cut(TimeWindow(at(20), at(120))).partlyKnown).isFalse()
    }

    /** Audit BUG-015: what is on screen carries the temperature in as it carries the level. */
    @Test
    fun aCutCarriesTheTemperatureIn() {
        val warm =
            timeline.copy(temperature = listOf(TemperaturePoint(at(0), 240), TemperaturePoint(at(20), 250), TemperaturePoint(at(100), 262)))

        assertThat(warm.cut(TimeWindow(at(30), at(65))).temperature).containsExactly(TemperaturePoint(at(30), 250))
        // Where nothing is known at the window's start, nothing is carried.
        assertThat(warm.cut(TimeWindow(at(5), at(14))).temperature).isEmpty()
    }
}

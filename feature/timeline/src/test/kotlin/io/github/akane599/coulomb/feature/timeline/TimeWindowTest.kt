// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimeWindowTest {
    private val now = 100 * DAY
    private val day = TimeWindow.latest(now)

    @Test
    fun zoomingInKeepsTheTimeUnderTheFingers() {
        val moved = day.moved(zoom = 2f, focus = 0.25f, pan = 0f, nowMs = now)

        assertThat(moved.spanMs).isEqualTo(12 * HOUR)
        // A quarter of the way in is the same moment before and after.
        assertThat(moved.fromMs + moved.spanMs / 4).isEqualTo(day.fromMs + day.spanMs / 4)
    }

    @Test
    fun theWindowIsNeverShorterThanAQuarterHourOrLongerThanAWeek() {
        assertThat(day.moved(1_000f, 0.5f, 0f, now).spanMs).isEqualTo(15 * MINUTE)
        assertThat(day.moved(0.001f, 0.5f, 0f, now).spanMs).isEqualTo(7 * DAY)
    }

    @Test
    fun draggingTowardsTheEndShowsEarlierTimes() {
        val moved = day.moved(zoom = 1f, focus = 0.5f, pan = 0.5f, nowMs = now)

        assertThat(moved).isEqualTo(TimeWindow(day.fromMs - 12 * HOUR, day.toMs - 12 * HOUR))
    }

    @Test
    fun theWindowStopsAtNowAndAtTheOldestHistoryKept() {
        assertThat(day.moved(1f, 0.5f, -3f, now)).isEqualTo(day)
        assertThat(day.moved(1f, 0.5f, 100f, now)).isEqualTo(TimeWindow(now - 30 * DAY, now - 29 * DAY))
    }

    @Test
    fun aPresetKeepsTheEndAndNowMovesIt() {
        val earlier = TimeWindow(now - 3 * DAY, now - 2 * DAY)

        assertThat(earlier.spanning(6 * HOUR, now)).isEqualTo(TimeWindow(now - 2 * DAY - 6 * HOUR, now - 2 * DAY))
        assertThat(earlier.toNow(now)).isEqualTo(day)
        assertThat(earlier.follows(now)).isFalse()
        assertThat(day.follows(now + 30_000)).isTrue()
    }

    @Test
    fun aReadCoversAWindowEachSideButNotTheFuture() {
        assertThat(day.padded(now)).isEqualTo(TimeWindow(now - 2 * DAY, now))
        assertThat(TimeWindow(now - 3 * DAY, now - 2 * DAY).padded(now)).isEqualTo(TimeWindow(now - 4 * DAY, now - DAY))
    }
}

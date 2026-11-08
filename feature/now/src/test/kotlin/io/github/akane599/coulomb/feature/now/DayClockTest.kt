// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.now

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.ui.format.Formats
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** Times on Now name their day once they leave today, so an estimate a day out never reads as today's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DayClockTest {
    private val formats =
        Formats(
            resources = RuntimeEnvironment.getApplication().resources,
            locale = Locale.US,
            clockPattern = "h:mm a",
            secondsPattern = "h:mm:ss a",
            hourPattern = "h a",
            dayClockPattern = "EEE h:mm a",
            weekdayPattern = "EEE",
        )
    private val zone = ZoneOffset.UTC

    /** Sunday 8 November 2026, 14:20 UTC. */
    private val now = at("2026-11-08T14:20:00Z")

    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun aTimeLaterTodayIsAClockTimeAlone() {
        assertThat(formats.clock(at("2026-11-08T21:30:00Z"), zone, now)).isEqualTo("9:30 PM")
    }

    @Test
    fun aTimeOnAnotherDayNamesTheWeekday() {
        assertThat(formats.clock(at("2026-11-09T01:20:00Z"), zone, now)).isEqualTo("Mon 1:20 AM")
        assertThat(formats.clock(at("2026-11-07T23:55:00Z"), zone, now)).isEqualTo("Sat 11:55 PM")
    }

    @Test
    fun theMidnightTickIsTheWeekday() {
        assertThat(formats.hourTick(at("2026-11-09T00:00:00Z"), zone)).isEqualTo("Mon")
        assertThat(formats.hourTick(at("2026-11-09T12:00:00Z"), zone)).isEqualTo("12 PM")
    }
}

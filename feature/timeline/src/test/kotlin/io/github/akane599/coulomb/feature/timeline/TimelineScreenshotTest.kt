// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.akane599.coulomb.core.data.TimelineTrack
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * The Timeline (SPEC 10.6) over the lab's day and zoomed in, in its SPEC 10.11 states, light and
 * dark, at 200% text, on an expanded width with its side panel, in Turkish and right to left. A
 * plain Application keeps Hilt out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = COMPACT)
class TimelineScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun day() = shoot("day") { Timeline(dayState()) }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun dayDark() = shoot("day-dark", dark = true) { Timeline(dayState()) }

    /** SPEC 11.4: the opt-in true black theme, which Settings now offers. */
    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun dayBlack() = shoot("day-black", dark = true, black = true) { Timeline(dayState()) }

    @Test
    @Config(qualifiers = "tr-$COMPACT")
    fun dayTurkish() = shoot("day-tr") { Timeline(dayState()) }

    @Test
    @Config(qualifiers = "ar-$COMPACT")
    fun dayArabic() = shoot("day-ar", rtl = true) { Timeline(dayState()) }

    @Test
    @Config(qualifiers = "w412dp-h2200dp-xhdpi")
    fun dayText200() = shoot("day-200", fontScale = 2f) { Timeline(dayState()) }

    /** The cursor on the night: asleep on the charger. */
    @Test
    fun scrubbed() = shoot("scrubbed") { Timeline(dayState(cursorMs = NOW - 6 * HOUR)) }

    /** Six hours of the evening: density spans split back into ticks. */
    @Test
    fun sixHours() = shoot("six-hours") { Timeline(dayState(window = TimeWindow(NOW - 20 * HOUR, NOW - 14 * HOUR))) }

    /** A quarter of an hour, with only the phone's own states. */
    @Test
    fun quarterHour() =
        shoot("quarter-hour") {
            Timeline(
                dayState(
                    window = TimeWindow(NOW - 13 * HOUR, NOW - 13 * HOUR + 15 * MINUTE),
                    tracks =
                        setOf(
                            TimelineTrack.SCREEN,
                            TimelineTrack.DOZE,
                            TimelineTrack.AWAKE,
                            TimelineTrack.TOP_APP,
                            TimelineTrack.WAKELOCKS,
                        ),
                ),
            )
        }

    /** A week back: most of it before the history that was read. */
    @Test
    fun pastTheHistory() = shoot("past-history") { Timeline(dayState(window = TimeWindow(NOW - 5 * DAY, NOW - 4 * DAY))) }

    /** The same empty range in Basic mode: why nothing is here, and where that changes. */
    @Test
    fun locked() = shoot("locked") { Timeline(dayState(window = TimeWindow(NOW - 5 * DAY, NOW - 4 * DAY)).copy(locked = true)) }

    /** A range whose read is still running is not a range without history. */
    @Test
    fun reading() = shoot("reading") { Timeline(dayState(window = TimeWindow(NOW - 5 * DAY, NOW - 4 * DAY)).copy(pending = true)) }

    /** A later read failed: what was read stays, and the page says so. */
    @Test
    fun readFailed() = shoot("read-failed") { Timeline(dayState().copy(failed = true)) }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun expandedWithDetails() =
        shoot("expanded") {
            val state = dayState()
            val picked = checkNotNull(state.timeline).pick(TimelineTrack.AWAKE, NOW - 3 * HOUR, reachMs = 24 * MINUTE)
            Timeline(state.copy(cursorMs = NOW - 3 * HOUR, picked = picked))
        }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun expandedHint() = shoot("expanded-hint") { Timeline(dayState()) }

    /** One stretch and a run of them, as the sheet and the side panel show them. */
    @Test
    fun eventDetails() =
        shoot("event-details") {
            val timeline = labDay()
            val one = checkNotNull(timeline.pick(TimelineTrack.TOP_APP, NOW - 19 * HOUR, reachMs = MINUTE))
            val many = checkNotNull(timeline.pick(TimelineTrack.AWAKE, NOW - 3 * HOUR, reachMs = 24 * MINUTE))
            val labels = dayState().labels
            Column(Modifier.padding(currentGutter())) {
                EventDetails(one, labels, ZoneOffset.UTC, rememberFormats(), NOW) {}
                Box(Modifier.padding(top = currentGutter())) { EventDetails(many, labels, ZoneOffset.UTC, rememberFormats(), NOW) {} }
            }
        }

    @Test
    fun loading() = shoot("loading") { Timeline(TimelineUiState(DAY_WINDOW, NOW)) }

    @Test
    fun error() = shoot("error") { Timeline(TimelineUiState(DAY_WINDOW, NOW, failed = true)) }

    @Composable
    private fun Timeline(state: TimelineUiState) =
        TimelineContent(state, TimelineActions(), TimelineLinks({}, {}, {}), zone = ZoneOffset.UTC)

    private fun shoot(
        name: String,
        dark: Boolean = false,
        black: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        // The ldrtl qualifier does not reach the composition under Robolectric; the override does.
        val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.LayoutDirection(direction),
            ) {
                CoulombTheme(darkTheme = dark, trueBlack = black) {
                    Box(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/timeline-$name.png")
    }
}

private const val COMPACT = "w412dp-h915dp-xhdpi"

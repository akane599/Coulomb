// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.BatteryDates
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * Health, the full session list and one session in each SPEC 10.11 state, at the lab's 412 x 915
 * dp and as a whole page, light, dark and black, at 130% and 200% text, at wider sizes, in Turkish
 * and in Arabic, and hardened. A plain Application keeps Hilt out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = COMPACT)
class HealthScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun labLight() = shoot("lab-light") { Health(ready()) }

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun page() = shoot("page") { Health(ready()) }

    @Test
    @Config(qualifiers = "$PAGE-night-xhdpi")
    fun pageDark() = shoot("page-dark", dark = true) { Health(ready()) }

    @Test
    @Config(qualifiers = "$PAGE-night-xhdpi")
    fun pageBlack() = shoot("page-black", dark = true, black = true) { Health(ready()) }

    @Test
    @Config(qualifiers = "w412dp-h3600dp-xhdpi")
    fun pageText130() = shoot("page-130", fontScale = 1.3f) { Health(ready()) }

    @Test
    @Config(qualifiers = "w412dp-h6000dp-xhdpi")
    fun pageText200() = shoot("page-200", fontScale = 2f) { Health(ready()) }

    /** A 700 dp window beside the 80 dp rail: 620 dp is under two 280 dp columns, so one (ADR-0036 §23). */
    @Test
    @Config(qualifiers = "w700dp-h1600dp-xhdpi")
    fun medium() = shoot("medium", width = 620.dp) { Health(ready()) }

    /** An 839 dp window beside the rail: two columns. */
    @Test
    @Config(qualifiers = "w839dp-h1600dp-xhdpi")
    fun mediumWide() = shoot("medium-wide", width = 759.dp) { Health(ready()) }

    @Test
    @Config(qualifiers = "w1000dp-h1600dp-xhdpi")
    fun expanded() = shoot("expanded") { Health(ready()) }

    /** The list pane beside an open session at 1000 dp (SPEC 10.10): the chosen row is marked and in view. */
    @Test
    @Config(qualifiers = "w1000dp-h3000dp-xhdpi")
    fun paneSelected() = shoot("pane", width = PANE) { Health(ready(), selected = LAB_SESSIONS.first().id) }

    @Test
    @Config(qualifiers = "w1000dp-h3000dp-xhdpi")
    fun paneSelectedDark() = shoot("pane-dark", dark = true, width = PANE) { Health(ready(), selected = LAB_SESSIONS.first().id) }

    @Test
    @Config(qualifiers = "tr-$PAGE-xhdpi")
    fun turkish() = shoot("tr") { Health(ready()) }

    @Test
    @Config(qualifiers = "ar-$PAGE-xhdpi")
    fun arabic() = shoot("ar", rtl = true) { Health(ready()) }

    /** A session row is one button that reads as a whole; a charger row's name and figure read apart from Rename. */
    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun rowsReadAsUnits() {
        shoot("rows", capture = false) { Health(ready()) }
        compose.onNode(hasText("12:30", substring = true) and hasClickAction()).assertExists()
        compose.onNode(hasText("Desk charger") and hasClickAction().not()).assertExists()
    }

    /** A first week: what the first estimate needs, and a line for each empty section. */
    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun firstWeek() = shoot("first-week") { Health(HealthUiState.Ready(newReport(), BatteryDates.NONE)) }

    @Test
    fun loading() = shoot("loading") { Health(HealthUiState.Loading) }

    @Test
    fun error() = shoot("error") { Health(HealthUiState.Error) }

    /** SPEC 11.12 harden: no design capacity, disagreeing sources, long names, missing figures. */
    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun hardened() = shoot("harden") { Health(HealthUiState.Ready(hardenedReport(), BatteryDates.NONE)) }

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun hardenedRightToLeft() = shoot("harden-rtl", rtl = true) { Health(HealthUiState.Ready(hardenedReport(), BatteryDates.NONE)) }

    @Test
    @Config(qualifiers = "w412dp-h6000dp-xhdpi")
    fun hardenedText200() = shoot("harden-200", fontScale = 2f) { Health(HealthUiState.Ready(hardenedReport(), BatteryDates.NONE)) }

    /** The trend's "View as table" (SPEC 11.8). */
    @Test
    fun trendTable() {
        shoot("trend-table", capture = false) { Health(ready()) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("View as table"))
        compose.onAllNodesWithText("View as table").onLast().performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/health-trend-table.png")
    }

    /** The design capacity dialog, opened from the estimate. */
    @Test
    @OptIn(ExperimentalRoborazziApi::class) // The dialog draws in its own window.
    fun designDialog() {
        shoot("design-dialog", capture = false) { Health(ready()) }
        // A focused field's cursor blinks for ever, so the clock steps by hand once the dialog is open.
        compose.mainClock.autoAdvance = false
        compose.onAllNodesWithText("Change design capacity").onFirst().performClick()
        compose.mainClock.advanceTimeBy(DIALOG_SETTLE_MS)
        captureScreenRoboImage("src/test/screenshots/health-design-dialog.png")
    }

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun allSessions() =
        shoot(
            "sessions",
        ) { ChargeSessionsContent(Read.Ready(LAB_SESSIONS + hardenedReport().sessions), {}, {}, zone = ZONE, nowMs = NOW_MS) }

    @Test
    fun session() = shoot("session") { ChargeSessionContent(Read.Ready(labSession()), {}, zone = ZONE, nowMs = NOW_MS) }

    @Test
    @Config(qualifiers = "w412dp-h1800dp-night-xhdpi")
    fun sessionDark() =
        shoot("session-dark", dark = true) { ChargeSessionContent(Read.Ready(labSession()), {}, zone = ZONE, nowMs = NOW_MS) }

    @Test
    @Config(qualifiers = "w412dp-h3000dp-xhdpi")
    fun sessionText200() =
        shoot("session-200", fontScale = 2f) { ChargeSessionContent(Read.Ready(labSession()), {}, zone = ZONE, nowMs = NOW_MS) }

    @Test
    @Config(qualifiers = "ar-w412dp-h1800dp-xhdpi")
    fun sessionArabic() =
        shoot("session-ar", rtl = true) { ChargeSessionContent(Read.Ready(labSession()), {}, zone = ZONE, nowMs = NOW_MS) }

    /** Before calibration: no power, so the level alone and why. */
    @Test
    fun sessionUncalibrated() =
        shoot("session-uncalibrated") {
            val detail = labSession()
            ChargeSessionContent(
                Read.Ready(
                    detail.copy(
                        peakW = null,
                        fullSpeedW = null,
                        ccToCvLevel = null,
                        charger = null,
                        curve = detail.curve.map { it.copy(watts = null) },
                    ),
                ),
                {},
                zone = ZONE,
                nowMs = NOW_MS,
            )
        }

    @Test
    fun sessionGone() = shoot("session-gone") { ChargeSessionContent(Read.Ready(null), {}, zone = ZONE, nowMs = NOW_MS) }

    @Composable
    private fun Health(
        state: HealthUiState,
        selected: Long? = null,
    ) = HealthContent(state, NoActions, selectedSession = selected, zone = ZONE, nowMs = NOW_MS)

    @Suppress("LongParameterList") // Test variants.
    private fun shoot(
        name: String,
        dark: Boolean = false,
        black: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        capture: Boolean = true,
        width: Dp? = null,
        content: @Composable () -> Unit,
    ) {
        // The ldrtl qualifier does not reach the composition under Robolectric; the override does.
        val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.LayoutDirection(direction),
            ) {
                CoulombTheme(darkTheme = dark, trueBlack = black) {
                    Box(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
                        Box(if (width != null) Modifier.width(width).fillMaxHeight() else Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
        if (capture) compose.onRoot().captureRoboImage("src/test/screenshots/health-$name.png")
    }
}

private const val COMPACT = "w412dp-h915dp-xhdpi"
private val PANE = 368.dp
private const val PAGE = "w412dp-h3000dp"
private val ZONE = ZoneOffset.UTC
private const val DIALOG_SETTLE_MS = 600L

private val NoActions =
    HealthActions(retry = {}, openSession = {}, seeAll = {}, rename = { _, _ -> }, setDesign = {}, useReportedDesign = {})

private fun ready() = HealthUiState.Ready(labReport(), LAB_DATES)

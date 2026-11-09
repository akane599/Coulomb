// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.charging

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.analytics.ChargePhase
import io.github.akane599.coulomb.core.analytics.ChargeTime
import io.github.akane599.coulomb.core.analytics.ChargeTimeBasis
import io.github.akane599.coulomb.core.data.ChargingNow
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * Charging in each SPEC 10.11 state and each phase, at the lab's 412 x 915 dp and as a whole page,
 * light, dark and black, at 130% and 200% text, at wider sizes, in Turkish and in Arabic, and
 * hardened. A plain Application keeps Hilt out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = COMPACT)
class ChargingScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun labLight() = shoot("lab-light") { Charging(labNow()) }

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun page() = shoot("page") { Charging(labNow()) }

    @Test
    @Config(qualifiers = "$PAGE-night-xhdpi")
    fun pageDark() = shoot("page-dark", dark = true) { Charging(labNow()) }

    @Test
    @Config(qualifiers = "$PAGE-night-xhdpi")
    fun pageBlack() = shoot("page-black", dark = true, black = true) { Charging(labNow()) }

    @Test
    @Config(qualifiers = "w412dp-h2400dp-xhdpi")
    fun pageText130() = shoot("page-130", fontScale = 1.3f) { Charging(labNow()) }

    @Test
    @Config(qualifiers = "w412dp-h3600dp-xhdpi")
    fun pageText200() = shoot("page-200", fontScale = 2f) { Charging(labNow()) }

    /** A 700 dp window beside the 80 dp rail. */
    @Test
    @Config(qualifiers = "w700dp-h1200dp-xhdpi")
    fun medium() = shoot("medium", width = 620.dp) { Charging(labNow()) }

    @Test
    @Config(qualifiers = "w1000dp-h1200dp-xhdpi")
    fun expanded() = shoot("expanded") { Charging(labNow()) }

    @Test
    @Config(qualifiers = "tr-$PAGE-xhdpi")
    fun turkish() = shoot("tr") { Charging(labNow()) }

    @Test
    @Config(qualifiers = "ar-$PAGE-xhdpi")
    fun arabic() = shoot("ar", rtl = true) { Charging(labNow()) }

    @Test
    fun loading() {
        shoot("loading", capture = false) { ChargingContent(ChargingUiState.Loading, NoActions, zone = ZONE) }
        // The skeleton shows only once a read takes longer than 150 ms.
        compose.mainClock.advanceTimeBy(SKELETON_WAIT_MS)
        compose.onRoot().captureRoboImage("src/test/screenshots/charging-loading.png")
    }

    @Test
    fun error() = shoot("error") { ChargingContent(ChargingUiState.Error, NoActions, zone = ZONE) }

    @Test
    fun unplugged() = shoot("unplugged") { ChargingContent(ChargingUiState.Unplugged, NoActions, zone = ZONE) }

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun uncalibrated() = shoot("uncalibrated") { Charging(uncalibratedNow()) }

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun newCharger() = shoot("new-charger") { Charging(newChargerNow()) }

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun learning() = shoot("learning") { Charging(learningNow()) }

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun startedHigh() = shoot("started-high") { Charging(startedHighNow()) }

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun holding() = shoot("holding") { Charging(holdingNow()) }

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun holdingBelowTarget() = shoot("holding-below-target") { Charging(holdingBelowTargetNow()) }

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun rightToLeftChargerName() = shoot("rtl-name") { Charging(rtlNameNow()) }

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun drawing() = shoot("drawing") { Charging(drawingNow()) }

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun full() = shoot("full") { Charging(fullNow()) }

    /** SPEC 11.12 harden: a long charger name, a 100% target and a long time, in both directions and at 200%. */
    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun hardened() = shoot("harden") { Charging(hardenedNow()) }

    @Test
    @Config(qualifiers = "ar-$PAGE-xhdpi")
    fun hardenedRightToLeft() = shoot("harden-rtl", rtl = true) { Charging(hardenedNow()) }

    @Test
    @Config(qualifiers = "w412dp-h3600dp-xhdpi")
    fun hardenedText200() = shoot("harden-200", fontScale = 2f) { Charging(hardenedNow()) }

    /** Charging polish: the longest phase words at 200% in Turkish, so "·" never ends a line alone. */
    @Test
    @Config(qualifiers = "tr-w412dp-h3600dp-xhdpi")
    fun slowingTurkishText200() =
        shoot("slowing-tr-200", fontScale = 2f) { Charging(labNow().copy(levelPct = 81, phase = ChargePhase.SLOWING)) }

    /** The curve's "View as table" (SPEC 11.8). */
    @Test
    fun curveTable() {
        shoot("curve-table", capture = false) { Charging(labNow()) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("View as table"))
        compose.onAllNodesWithText("View as table").onLast().performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/charging-curve-table.png")
    }

    /** A 70% target set elsewhere (SPEC 14.4 allows 50–100%) joins the group, selected. */
    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun targetOutsideTheGroup() =
        shoot("target-70") {
            Charging(labNow().copy(targetPct = 70, toTarget = ChargeTime(10.2, 13.1, ChargeTimeBasis.SAME_CHARGER, 7)))
        }

    /** The level scale's table, from its TalkBack action (SPEC 11.8: hero scales too). */
    @Test
    @OptIn(ExperimentalTestApi::class) // Custom accessibility actions.
    fun scaleTable() {
        shoot("scale-table", capture = false) { Charging(labNow()) }
        compose.onNode(hasContentDescription("Level 52%", substring = true)).performCustomAccessibilityActionWithLabel("View as table")
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/charging-scale-table.png")
    }

    /** The target group moves the tick: 85% is passed on and becomes the selected choice. */
    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun targetChoice() {
        var chosen = 0
        shoot("target", capture = false) {
            var target by remember { mutableIntStateOf(80) }
            val actions =
                ChargingActions(retry = {}, setTarget = { pct ->
                    chosen = pct
                    target = pct
                })
            ChargingContent(ChargingUiState.Ready(labNow().copy(targetPct = target)), actions, zone = ZONE)
        }
        compose.onNodeWithText("85%").performClick()
        compose.waitForIdle()
        assertThat(chosen).isEqualTo(85)
        compose.onRoot().captureRoboImage("src/test/screenshots/charging-target-85.png")
    }

    @Composable
    private fun Charging(now: ChargingNow) = ChargingContent(ChargingUiState.Ready(now), NoActions, zone = ZONE)

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
        if (capture) compose.onRoot().captureRoboImage("src/test/screenshots/charging-$name.png")
    }
}

private const val SKELETON_WAIT_MS = 200L
private const val COMPACT = "w412dp-h915dp-xhdpi"
private const val PAGE = "w412dp-h1800dp"
private val ZONE = ZoneOffset.UTC

private val NoActions = ChargingActions(retry = {}, setTarget = {})

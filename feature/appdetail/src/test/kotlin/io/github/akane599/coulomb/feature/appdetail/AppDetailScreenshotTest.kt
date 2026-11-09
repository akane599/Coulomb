// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.akane599.coulomb.core.data.AppCounters
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * App detail in each SPEC 10.11 state, at the lab's 412 x 915 dp and as a whole page, light, dark
 * and black, at 130% and 200% text, at wider sizes, in Turkish and in Arabic, and hardened. A
 * plain Application keeps Hilt out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = COMPACT)
class AppDetailScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun labLight() = shoot("lab-light", ready())

    @Test
    @Config(qualifiers = "$PAGE-night-xhdpi")
    fun pageDark() = shoot("page-dark", ready(), dark = true)

    @Test
    @Config(qualifiers = "$PAGE-night-xhdpi")
    fun pageBlack() = shoot("page-black", ready(), dark = true, black = true)

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun page() = shoot("page", ready())

    @Test
    @Config(qualifiers = "w412dp-h3000dp-xhdpi")
    fun pageText130() = shoot("page-130", ready(), fontScale = 1.3f)

    @Test
    @Config(qualifiers = "w412dp-h5000dp-xhdpi")
    fun pageText200() = shoot("page-200", ready(), fontScale = 2f)

    /** A 700 dp window beside the rail: two columns of 280 dp fit. */
    @Test
    @Config(qualifiers = "w700dp-h1400dp-xhdpi")
    fun medium() = shoot("medium", ready())

    @Test
    @Config(qualifiers = "w1000dp-h1400dp-xhdpi")
    fun expanded() = shoot("expanded", ready())

    /** The detail pane beside Drain's list at 840 dp: about 380 dp, so one list. */
    @Test
    @Config(qualifiers = "w380dp-h1400dp-xhdpi")
    fun pane() = shoot("pane", ready())

    @Test
    @Config(qualifiers = "tr-$PAGE-xhdpi")
    fun turkish() = shoot("tr", ready())

    @Test
    @Config(qualifiers = "ar-$PAGE-xhdpi")
    fun arabic() = shoot("ar", ready(), rtl = true)

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun scaled() = shoot("scaled", ready(scaled = true))

    /** No use in the window: the no-use line, its wakeups and trend still shown. */
    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun noUse() = shoot("no-use", ready(labDetail(used = false)))

    /** The Component breakdown setting was off for the window (ADR-0035 §2). */
    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun breakdownOff() =
        shoot("breakdown-off", ready(labDetail(brokenDown = false).let { it.copy(report = it.report.copy(breakdownOff = true)) }))

    @Test
    fun empty() = shoot("empty", AppDetailUiState.Ready(DrainSpan.SinceUnplugged, null, scaled = false))

    @Test
    fun loading() = shoot("loading", AppDetailUiState.Loading)

    @Test
    fun error() = shoot("error", AppDetailUiState.Error)

    /** SPEC 11.12 harden: a long name, six-digit figures, an estimated total, gap days, nothing recorded. */
    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun hardened() = shoot("harden", ready(hardenedDetail()))

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun hardenedRightToLeft() = shoot("harden-rtl", ready(hardenedDetail()), rtl = true)

    @Test
    @Config(qualifiers = "w412dp-h5000dp-xhdpi")
    fun hardenedText200() = shoot("harden-200", ready(hardenedDetail()), fontScale = 2f)

    /** The trend's "View as table" (SPEC 11.8). */
    @Test
    fun trendTable() {
        shoot("trend-table", ready(), capture = false)
        // The trend is below the fold: the list composes it once scrolled to.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("View as table"))
        compose.onAllNodesWithText("View as table").onLast().performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/appdetail-trend-table.png")
    }

    @Suppress("LongParameterList") // Test variants.
    private fun shoot(
        name: String,
        state: AppDetailUiState,
        dark: Boolean = false,
        black: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        capture: Boolean = true,
    ) {
        // The ldrtl qualifier does not reach the composition under Robolectric; the override does.
        val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.LayoutDirection(direction),
            ) {
                CoulombTheme(darkTheme = dark, trueBlack = black) {
                    Box(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
                        AppDetailContent(state, NoActions, zone = ZoneOffset.UTC)
                    }
                }
            }
        }
        if (capture) compose.onRoot().captureRoboImage("src/test/screenshots/appdetail-$name.png")
    }
}

private const val COMPACT = "w412dp-h915dp-xhdpi"
private const val PAGE = "w412dp-h2400dp"

private val NoActions = AppDetailActions(retry = {}, openAppInfo = {}, icon = { _, _ -> null })

private fun ready(
    detail: io.github.akane599.coulomb.core.data.AppDetailReport = labDetail(),
    scaled: Boolean = false,
) = AppDetailUiState.Ready(DrainSpan.SinceUnplugged, detail, scaled)

private fun hardenedDetail() =
    labDetail(
        drains =
            labDrains(halcyon = 123_456.0).copy(
                measuredMah = 140_000.0,
                measuredProvenance = Provenance.ESTIMATED,
                modeledMah = 124_000.0,
            ),
        brokenDown = false,
        trend = labTrend(gapDays = setOf(3, 4, 10)),
        counters = AppCounters(alarmWakeups = 123_456),
        label = "Very Long Navigation And Offline Maps Application Name",
    ).let { it.copy(report = it.report.copy(lowerBound = true, chargedInSpan = true)) }

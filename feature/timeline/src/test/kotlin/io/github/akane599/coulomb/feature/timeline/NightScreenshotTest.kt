// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.akane599.coulomb.core.data.WakeCoverage
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * The Night Report's list and one night (SPEC 10.7) in their SPEC 10.11 states, at the lab's
 * 412 x 915 dp and as a whole page, light and dark, at 200% text, beside the list, in Turkish and
 * right to left. A plain Application keeps Hilt out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = COMPACT)
class NightScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun list() = shoot("list") { NightReportContent(Read.Ready(labNights()), {}, {}, zone = ZoneOffset.UTC, nowMs = NOW) }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun listDark() =
        shoot("list-dark", dark = true) { NightReportContent(Read.Ready(labNights()), {}, {}, zone = ZoneOffset.UTC, nowMs = NOW) }

    @Test
    @Config(qualifiers = "tr-$COMPACT")
    fun listTurkish() = shoot("list-tr") { NightReportContent(Read.Ready(labNights()), {}, {}, zone = ZoneOffset.UTC, nowMs = NOW) }

    @Test
    @Config(qualifiers = "w412dp-h1800dp-xhdpi")
    fun listText200() =
        shoot("list-200", fontScale = 2f) { NightReportContent(Read.Ready(labNights()), {}, {}, zone = ZoneOffset.UTC, nowMs = NOW) }

    @Test
    fun listSelected() =
        shoot("list-selected", width = 360.dp) {
            NightReportContent(Read.Ready(labNights()), {}, {}, selected = 40, zone = ZoneOffset.UTC, nowMs = NOW)
        }

    @Test
    fun listEmpty() = shoot("list-empty") { NightReportContent(Read.Ready(emptyList()), {}, {}, zone = ZoneOffset.UTC, nowMs = NOW) }

    @Test
    fun listLoading() = shoot("list-loading") { NightReportContent(Read.Loading, {}, {}, zone = ZoneOffset.UTC, nowMs = NOW) }

    @Test
    fun listError() = shoot("list-error") { NightReportContent(Read.Error, {}, {}, zone = ZoneOffset.UTC, nowMs = NOW) }

    @Test
    @Config(qualifiers = "$PAGE-xhdpi")
    fun night() = shoot("night") { Night(labDetail()) }

    @Test
    @Config(qualifiers = "$PAGE-night-xhdpi")
    fun nightDark() = shoot("night-dark", dark = true) { Night(labDetail()) }

    @Test
    @Config(qualifiers = "tr-$PAGE-xhdpi")
    fun nightTurkish() = shoot("night-tr") { Night(labDetail()) }

    @Test
    @Config(qualifiers = "w412dp-h4200dp-xhdpi")
    fun nightText200() = shoot("night-200", fontScale = 2f) { Night(labDetail()) }

    @Test
    @Config(qualifiers = "ar-$PAGE-xhdpi")
    fun nightArabic() = shoot("night-ar", rtl = true) { Night(labDetail()) }

    /** On the charger there is no drain rate, and nothing stored but the session itself. */
    @Test
    fun nightCharged() = shoot("night-charged") { Night(NightDetail(labNights()[3], report = null, timeline = null)) }

    /** A drop of one point has no rate: the level lost is the figure. */
    @Test
    fun nightNoRate() = shoot("night-no-rate") { Night(NightDetail(labNights()[2], report = null, timeline = null)) }

    @Test
    fun nightNotCollected() =
        shoot("night-not-collected") {
            // Basic mode or a failed read: nothing was collected, which is not "none recorded".
            val nothing = WakeCoverage(Coverage.NONE, Coverage.NONE, Coverage.NONE)
            val report = labNightReport().copy(wakes = emptyList(), alarms = emptyList(), heldMs = emptyMap(), wakeCoverage = nothing)
            Night(labDetail().copy(report = report))
        }

    @Test
    fun nightPartlyCollected() =
        shoot("night-partly-collected") {
            val partly = WakeCoverage(Coverage.PARTIAL, Coverage.NONE, Coverage.NONE)
            val report = labNightReport().let { r -> r.copy(wakes = r.wakes.filter { it.kind == WakeKind.PARTIAL }, wakeCoverage = partly) }
            Night(labDetail().copy(report = report))
        }

    @Test
    fun nightGone() = shoot("night-gone") { NightContent(Read.Ready(null), {}, {}, zone = ZoneOffset.UTC, nowMs = NOW) }

    @Composable
    private fun Night(detail: NightDetail) = NightContent(Read.Ready(detail), {}, {}, zone = ZoneOffset.UTC, nowMs = NOW)

    @Suppress("LongParameterList") // One capture's settings.
    private fun shoot(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        width: Dp? = null,
        content: @Composable () -> Unit,
    ) {
        // The ldrtl qualifier does not reach the composition under Robolectric; the override does.
        val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.LayoutDirection(direction),
            ) {
                CoulombTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
                        Box(if (width != null) Modifier.width(width).fillMaxHeight() else Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/night-$name.png")
    }
}

private const val COMPACT = "w412dp-h915dp-xhdpi"
private const val PAGE = "w412dp-h2600dp"

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.now

import android.app.Application
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.akane599.coulomb.core.analytics.TimeLeft
import io.github.akane599.coulomb.core.data.AppDrain
import io.github.akane599.coulomb.core.data.BatteryNow
import io.github.akane599.coulomb.core.data.CurrentPoint
import io.github.akane599.coulomb.core.data.DeepSleepShare
import io.github.akane599.coulomb.core.data.DischargeSummary
import io.github.akane599.coulomb.core.data.LevelPoint
import io.github.akane599.coulomb.core.data.StateRate
import io.github.akane599.coulomb.core.data.WindowDrains
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.DataTableSheet
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.sin

/**
 * Now in each SPEC 10.11 state, at the lab's 412 x 915 dp, light and dark, at 200% text, at a
 * medium width and in Turkish. A plain Application keeps Hilt out: the content takes a fake state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = COMPACT)
class NowScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun dischargingLight() = shoot("discharging-light", discharging())

    @Test
    @Config(qualifiers = "$COMPACT_SIZE-night-xhdpi")
    fun dischargingDark() = shoot("discharging-dark", discharging(), dark = true)

    @Test
    @Config(qualifiers = "$COMPACT_SIZE-night-xhdpi")
    fun dischargingBlack() = shoot("discharging-black", discharging(), dark = true, black = true)

    @Test
    fun dischargingText130() = shoot("discharging-130", discharging(), fontScale = 1.3f)

    @Test
    fun dischargingLargeText() = shoot("discharging-200", discharging(), fontScale = 2f)

    @Test
    @Config(qualifiers = "w1000dp-h800dp-xhdpi")
    fun dischargingExpanded() = shoot("discharging-expanded", discharging())

    /** SPEC 11.12 harden: long names, a six-digit mAh, a low-quality estimate, right to left. */
    @Test
    @Config(qualifiers = "w412dp-h2000dp-xhdpi")
    fun hardenedRightToLeft() = shoot("harden-rtl", hardened(), rtl = true)

    /** The same at 200% text: names wrap, and each six-digit value moves under its name. */
    @Test
    @Config(qualifiers = "w412dp-h3000dp-xhdpi")
    fun hardenedLargeText() = shoot("harden-200", hardened(), fontScale = 2f)

    /** An Arabic locale: its own digits and percent placement, right to left. */
    @Test
    @Config(qualifiers = "ar-w412dp-h2000dp-xhdpi")
    fun dischargingArabic() = shoot("discharging-ar", discharging(), rtl = true)

    /** A phone on its side: an expanded width with a short height, so the column is capped. */
    @Test
    @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun dischargingLandscape() = shoot("discharging-landscape", discharging())

    @Test
    @Config(qualifiers = "w700dp-h1000dp-xhdpi")
    fun dischargingMedium() = shoot("discharging-medium", discharging())

    @Test
    @Config(qualifiers = "tr-$COMPACT")
    fun dischargingTurkish() = shoot("discharging-tr", discharging())

    @Test
    @Config(qualifiers = "w412dp-h2000dp-xhdpi")
    fun dischargingWholePage() = shoot("discharging-page", discharging())

    @Test
    @Config(qualifiers = "w412dp-h2000dp-xhdpi")
    fun dischargingWholePageLargeText() = shoot("discharging-page-200", discharging(), fontScale = 2f)

    @Test
    fun loading() = shoot("loading", NowUiState.Loading)

    @Test
    fun collecting() = shoot("collecting", collectingState())

    @Test
    fun partial() = shoot("partial", collectingState().copy(issue = PrivilegeIssue.MANAGER_NOT_INSTALLED))

    @Test
    fun pluggedIn() = shoot("plugged", plugged())

    @Test
    fun error() = shoot("error", NowUiState.Error)

    /** The live chart's "View as table" sheet (DESIGN.md, Charts): labels first, figures right-aligned. */
    @Test
    fun currentTable() = shootTable("table")

    /** The sheet is a window of its own, which takes the font scale from the configuration. */
    @Test
    @Config(fontScale = 2f)
    fun currentTableLargeText() = shootTable("table-200")

    // The only Roborazzi call that captures every window; test code only.
    @OptIn(ExperimentalRoborazziApi::class)
    private fun shootTable(name: String) {
        val state = discharging()
        compose.setContent {
            CoulombTheme {
                DataTableSheet(tableOf(OpenTable.CURRENT, state, rememberFormats(), ZoneOffset.UTC), onDismiss = {})
            }
        }
        // The sheet is a window of its own, so the capture takes the whole screen.
        captureScreenRoboImage("src/test/screenshots/now-$name.png")
    }

    private fun shoot(
        name: String,
        state: NowUiState,
        dark: Boolean = false,
        fontScale: Float = 1f,
        black: Boolean = false,
        rtl: Boolean = false,
    ) {
        // The ldrtl qualifier does not reach the composition under Robolectric; the override does.
        val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.LayoutDirection(direction),
            ) {
                CoulombTheme(darkTheme = dark, trueBlack = black) {
                    Box(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
                        NowContent(state = state, refreshing = false, actions = NoActions, zone = ZoneOffset.UTC)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/now-$name.png")
    }
}

private const val COMPACT_SIZE = "w412dp-h915dp"
private const val COMPACT = "$COMPACT_SIZE-xhdpi"
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/** 14:20 UTC on the day the screen was built. */
private val NOW = Instant.parse("2026-11-08T14:20:00Z").toEpochMilli()
private val UNPLUG = NOW - (5 * HOUR + 12 * MINUTE)

private val NoActions = NowActions(refresh = {}, retry = {}, seeAllDrains = {}, openPrivileges = {}, icon = { _, _ -> null })

private fun battery(
    level: Int,
    currentMa: Double?,
    plugged: Boolean = false,
    status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING,
) = BatteryNow(
    levelPct = level,
    currentMa = currentMa,
    plugged = plugged,
    status = status,
    tempDeciC = 300,
    voltageMv = 3_900,
    screenOn = true,
    wallMs = NOW,
)

/**
 * 100% at the unplug to 74% now, at the strip's rates: screen on for 70 min at 12.6 %/h, off for
 * 3 h 20 min at 0.8 %/h, then on again for 42 min.
 */
private fun levels(): List<LevelPoint> =
    (0..(NOW - UNPLUG) / (5 * MINUTE)).map { step ->
        val minutes = step * 5.0
        val on = minOf(minutes, 70.0) + maxOf(0.0, minutes - 270.0)
        val off = (minOf(minutes, 270.0) - 70.0).coerceAtLeast(0.0)
        LevelPoint(UNPLUG + step * 5 * MINUTE, Math.round(100 - on * 12.6 / 60 - off * 0.8 / 60).toInt(), startsSegment = false)
    }

/** Ten minutes around −410 mA with one rise to about 1 A six minutes ago. */
private fun live(): List<CurrentPoint> =
    (0..119).map { i ->
        val at = NOW - 10 * MINUTE + i * 5_000L
        val bump = if (i in 40..55) -600.0 * sin((i - 40) / 15.0 * Math.PI) else 0.0
        CurrentPoint(at, -410.0 + 40 * sin(i / 3.0) + bump)
    }

private fun drains() =
    WindowDrains(
        fromWallMs = UNPLUG,
        toWallMs = NOW,
        measuredMah = 1_220.0,
        measuredProvenance = Provenance.MEASURED,
        modeledMah = 1_210.0,
        apps =
            listOf(
                AppDrain(10_123, "Halcyon", isSystem = false, iconPackage = null, mah = 402.0),
                AppDrain(10_241, "Wayline", isSystem = false, iconPackage = null, mah = 168.0),
                AppDrain(1_000, null, isSystem = true, iconPackage = null, mah = 96.0),
            ),
        otherMah = 220.0,
        deviceMah = 324.0,
    )

private fun discharging() =
    NowUiState.Ready(
        battery = battery(74, -410.0),
        discharge =
            DischargeSummary(
                startWallMs = UNPLUG,
                startLevel = 100,
                levelPct = 74,
                screenOn = StateRate(pctPerHour = 12.6, hours = 1.87, quality = 0.9),
                screenOff = StateRate(pctPerHour = 0.8, hours = 3.33, quality = 0.4),
                deepSleep = DeepSleepShare(share = 0.88, hours = 3.2, quality = 1.0),
                timeLeft = TimeLeft(shortestHours = 5.9, longestHours = 7.3),
            ),
        levels = levels(),
        drains = drains(),
        live = live(),
        issue = null,
    )

private fun hardened(): NowUiState.Ready {
    val base = discharging()
    val apps =
        listOf(
            AppDrain(10_123, "Very Long Navigation And Offline Maps Application Name", false, null, 123_456.0),
            AppDrain(10_241, "Wayline", isSystem = false, iconPackage = null, mah = 4.2),
            AppDrain(10_999, null, isSystem = false, iconPackage = null, mah = 0.3),
        )
    return base.copy(
        battery = base.battery.copy(levelPct = 100, currentMa = -2_345.0),
        discharge =
            base.discharge?.copy(
                startLevel = 100,
                levelPct = 100,
                deepSleep = DeepSleepShare(share = 0.42, hours = 3.2, quality = 0.4),
                timeLeft = TimeLeft(31.2, 58.9),
            ),
        levels = listOf(LevelPoint(UNPLUG, 100, false), LevelPoint(NOW, 100, false)),
        drains = base.drains?.copy(measuredMah = 130_000.0, modeledMah = 128_000.0, apps = apps),
        issue = PrivilegeIssue.GRANTS_REVOKED,
    )
}

/** Six minutes after the unplug: nothing to rate yet and no window stored. */
private fun collectingState() =
    NowUiState.Ready(
        battery = battery(99, null),
        discharge =
            DischargeSummary(
                startWallMs = NOW - 6 * MINUTE,
                startLevel = 100,
                levelPct = 99,
                screenOn = StateRate(pctPerHour = 9.0, hours = 0.1, quality = 0.0),
                screenOff = null,
                deepSleep = null,
                timeLeft = null,
            ),
        levels = listOf(LevelPoint(NOW - 6 * MINUTE, 100, false), LevelPoint(NOW, 99, false)),
        drains = null,
        live = emptyList(),
        issue = null,
    )

private fun plugged() =
    NowUiState.Ready(
        battery = battery(62, 1_240.0, plugged = true, status = BatteryManager.BATTERY_STATUS_CHARGING),
        discharge = null,
        levels = emptyList(),
        drains = drains().copy(toWallMs = NOW - 25 * MINUTE),
        live = live().map { it.copy(currentMa = 1_240.0 + 30 * sin(it.wallMs / 20_000.0)) },
        issue = null,
    )

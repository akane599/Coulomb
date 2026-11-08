// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.akane599.coulomb.core.data.AlarmEntry
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.data.LevelEstimate
import io.github.akane599.coulomb.core.data.WakeCoverage
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Compare two periods (ADR-0038) in each lens and state, at the lab's 412 dp width, light and dark,
 * at 200% text, two columns wide, in Turkish and right to left. A plain Application keeps Hilt out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w412dp-h1400dp-xhdpi")
class CompareScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun apps() = shoot("apps", compared())

    @Test
    @Config(qualifiers = "w412dp-h1400dp-night-xhdpi")
    fun appsDark() = shoot("apps-dark", compared(), dark = true)

    @Test
    @Config(qualifiers = "tr-w412dp-h1600dp-xhdpi")
    fun appsTurkish() = shoot("apps-tr", compared())

    @Test
    @Config(qualifiers = "w412dp-h2600dp-xhdpi")
    fun appsLargeText() = shoot("apps-200", compared(), fontScale = 2f)

    @Test
    fun appsRightToLeft() = shoot("apps-rtl", compared(), rtl = true)

    @Test
    @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun appsLandscape() = shoot("apps-landscape", compared())

    @Test
    fun components() = shoot("components", compared(), lens = Lens.COMPONENTS)

    @Test
    @Config(qualifiers = "w412dp-h2400dp-xhdpi")
    fun wakeups() = shoot("wakeups", compared(), lens = Lens.WAKEUPS)

    @Test
    @Config(qualifiers = "tr-w412dp-h4400dp-xhdpi")
    fun wakeupsTurkishLargeText() = shoot("wakeups-tr-200", compared(), lens = Lens.WAKEUPS, fontScale = 2f)

    /** Yesterday in shell mode, today with root: the kernel group is read from two sources. */
    @Test
    @Config(qualifiers = "w412dp-h2400dp-xhdpi")
    fun wakeupsFromTwoSources() {
        val root =
            today().let { r ->
                r.copy(
                    wakes = r.wakes.filter { it.kind != WakeKind.KERNEL },
                    wakeCoverage = WakeCoverage(Coverage.WHOLE, Coverage.WHOLE, Coverage.WHOLE),
                )
            }
        shoot("wakeups-sources", compared(second = root), lens = Lens.WAKEUPS)
    }

    @Test
    fun secondPeriodWithoutRecords() = shoot("no-records", compared(second = null))

    @Test
    fun secondPeriodTooShort() {
        val short =
            today().let {
                it.copy(onBatteryMs = 10 * MINUTE, measuredMs = 10 * MINUTE, drains = it.drains.copy(measuredMah = 6.0))
            }
        shoot("too-short", compared(second = short))
    }

    /** Today estimated from the level: a range per hour, and no change against a measured day. */
    @Test
    fun secondPeriodEstimated() {
        val estimated =
            today().let {
                it.copy(
                    drains =
                        it.drains.copy(
                            measuredProvenance = Provenance.ESTIMATED,
                            levelEstimate = LevelEstimate(spreadMah = 50.0, stretches = 1, withCounter = false),
                        ),
                )
            }
        shoot("estimated", compared(second = estimated))
    }

    /** Today without S5 or any wake source: the counter's rate stands, and each lens says what is missing. */
    @Test
    fun secondPeriodWithoutStats() = shoot("no-stats", compared(second = unread()))

    @Test
    fun wakeupsWithoutStats() = shoot("wakeups-no-stats", compared(second = unread()), lens = Lens.WAKEUPS)

    @Test
    fun loading() = shoot("loading", state(comparison = null))

    /** TalkBack reads a row as one sentence with both rates and the direction of the change. */
    @Test
    fun aRowReadsAsOneSentence() {
        shoot("spoken", compared(), capture = false)
        compose
            .onNodeWithContentDescription(
                // 290 mAh over 14 h, then 168 mAh over 5 h 12 min; the change is rounded from the
                // unrounded rates (11.6), not from the shown ones.
                "Wayline: 21\u00A0mAh/h in the first period, 32\u00A0mAh/h in the second, up 12\u00A0mAh/h.",
            ).assertExists()
    }

    /** A row with a tag reads the tag after the name. */
    @Test
    fun aTaggedRowReadsItsTag() {
        shoot("spoken-tag", compared(), lens = Lens.WAKEUPS, capture = false)
        compose
            .onNodeWithContentDescription("Wayline, LocationUpdates: ", substring = true)
            .assertExists()
    }

    @Suppress("LongParameterList") // Test variants.
    private fun shoot(
        name: String,
        state: CompareUiState,
        dark: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        lens: Lens = Lens.APPS,
        capture: Boolean = true,
    ) {
        val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.LayoutDirection(direction),
            ) {
                CoulombTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
                        CompareContent(state = state, actions = NoActions, initialLens = lens)
                    }
                }
            }
        }
        if (capture) compose.onRoot().captureRoboImage("src/test/screenshots/compare-$name.png")
    }
}

private val YESTERDAY = DrainSpan.Days(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 7))
private val TODAY = DrainSpan.Days(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 8))

private fun state(comparison: Comparison?) = CompareUiState(YESTERDAY, TODAY, LocalDate.of(2026, 11, 8), comparison)

private fun compared(
    first: DrainReport? = yesterday(),
    second: DrainReport? = today(),
) = state(compare(Period(YESTERDAY, first), Period(TODAY, second), includeSystem = false))

/** Today so far: the lab's window (5 h 12 min on battery, 1,204 mAh measured). */
private fun today(): DrainReport = report(TODAY)

/** Today with the counter read but neither S5 nor a wake source: no time on battery, apps or wakes. */
private fun unread(): DrainReport =
    today().copy(
        drains = today().drains.copy(modeledMah = 0.0, apps = emptyList(), otherMah = 0.0, deviceMah = 0.0),
        onBatteryMs = 0,
        screenOffOnBatteryMs = 0,
        wakes = emptyList(),
        alarms = emptyList(),
        components = emptyList(),
        wakeCoverage = WakeCoverage(Coverage.NONE, Coverage.NONE, Coverage.NONE),
    )

/**
 * Yesterday: 14 h on battery, 10 of them with the screen off, at a lower drain. Wayline ran less and
 * Driftly not at all; a few wakelocks ran longer.
 */
private fun yesterday(): DrainReport {
    val lab = report(YESTERDAY)
    val onBattery = 14 * HOUR
    val screenOff = 10 * HOUR
    val factor = onBattery.toDouble() / lab.onBatteryMs
    val apps =
        lab.drains.apps
            .filter { it.uid != 10_402 }
            .map {
                when (it.uid) {
                    10_241 -> it.copy(mah = 290.0)
                    10_310 -> it.copy(mah = it.mah * factor * 1.6)
                    else -> it.copy(mah = it.mah * factor * 0.9)
                }
            }
    val offFactor = screenOff.toDouble() / lab.screenOffOnBatteryMs
    return lab.copy(
        drains =
            lab.drains.copy(
                measuredMah = 2_380.0,
                modeledMah = apps.sumOf { it.mah } + 220.0 + 40.0,
                apps = apps,
                otherMah = 40.0,
                deviceMah = 220.0,
            ),
        durationMs = 24 * HOUR,
        screenOffMs = 20 * HOUR,
        onBatteryMs = onBattery,
        screenOffOnBatteryMs = screenOff,
        measuredMs = onBattery,
        lowerBound = true,
        chargedInSpan = true,
        components = lab.components.map { it.copy(mah = it.mah * factor * if (it.component.name == "SCREEN") 0.7 else 1.0) },
        wakes =
            lab.wakes
                .filter { it.uid != 10_402 }
                .map { it.copy(totalMs = (it.totalMs * offFactor * if (it.uid == 1_000) 1.5 else 1.0).toLong()) },
        alarms = lab.alarms.map { it.copy(wakeups = it.wakeups * 4) } + AlarmEntry(AlarmEntry.SMALL_APPS_UID, null, null, 30),
    )
}

private val NoActions =
    CompareActions(
        chooseFirst = {},
        chooseSecond = {},
        includeSystem = {},
        retry = {},
        openApp = {},
        icon = { _, _ -> null },
    )

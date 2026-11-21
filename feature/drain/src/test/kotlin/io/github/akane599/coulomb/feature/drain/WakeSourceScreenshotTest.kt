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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.akane599.coulomb.core.analytics.WakeDictionary
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.data.NightSummary
import io.github.akane599.coulomb.core.data.WakeApp
import io.github.akane599.coulomb.core.data.WakeApps
import io.github.akane599.coulomb.core.data.WakeEntry
import io.github.akane599.coulomb.core.data.WakeNight
import io.github.akane599.coulomb.core.data.WakeSourceDetail
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

/** The wakeup source detail (SPEC 10.5): a reason with apps, a kernel record without, and the states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w412dp-h1500dp-xhdpi")
class WakeSourceScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reason() = shoot("reason", WakeSourceState.Ready(REASON))

    /** SPEC 10.10: as the detail pane beside Drain's list, about 550 dp of a 1000 dp window. */
    @Test
    @Config(qualifiers = "w560dp-h1400dp-xhdpi")
    fun reasonPane() = shoot("reason-pane", WakeSourceState.Ready(REASON))

    @Test
    @Config(qualifiers = "w412dp-h1500dp-night-xhdpi")
    fun reasonDark() = shoot("reason-dark", WakeSourceState.Ready(REASON), dark = true)

    /** SPEC 11.4: the opt-in true black theme, which Settings now offers. */
    @Test
    @Config(qualifiers = "w412dp-h1500dp-night-xhdpi")
    fun reasonBlack() = shoot("reason-black", WakeSourceState.Ready(REASON), dark = true, black = true)

    @Test
    @Config(qualifiers = "tr-w412dp-h1500dp-xhdpi")
    fun reasonTurkish() = shoot("reason-tr", WakeSourceState.Ready(REASON))

    @Test
    @Config(qualifiers = "w412dp-h3000dp")
    fun reason200() = shoot("reason-200", WakeSourceState.Ready(REASON), fontScale = 2f)

    @Test
    fun kernel() = shoot("kernel", WakeSourceState.Ready(KERNEL))

    @Test
    fun notCollected() =
        shoot("not-collected", WakeSourceState.Ready(KERNEL.copy(entry = null, coverage = Coverage.NONE, nights = emptyList())))

    @Test
    fun noHistory() =
        shoot(
            "no-history",
            WakeSourceState.Ready(
                REASON.copy(apps = REASON.apps?.copy(knownMs = 0, wakes = 0, unclaimed = 0, unnamed = 0, apps = emptyList())),
            ),
        )

    @Test
    fun loading() = shoot("loading", WakeSourceState.Loading)

    @Test
    fun error() = shoot("error", WakeSourceState.Error)

    private fun shoot(
        name: String,
        state: WakeSourceState,
        dark: Boolean = false,
        black: Boolean = false,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                CoulombTheme(darkTheme = dark, trueBlack = black) {
                    Box(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
                        WakeSourceContent(state, retry = {}, onOpenApp = {}, zone = ZoneOffset.UTC, nowMs = WAKE_NOW)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/wake-$name.png")
    }
}

private const val WAKE_NOW = 1_763_719_200_000L
private const val WAKE_HOUR = 3_600_000L
private const val WAKE_DAY = 24 * WAKE_HOUR

private fun wakeNight(
    daysAgo: Int,
    hours: Double,
) = NightSummary(
    id = daysAgo.toLong(),
    startWallMs = WAKE_NOW - daysAgo * WAKE_DAY - 8 * WAKE_HOUR,
    endWallMs = WAKE_NOW - daysAgo * WAKE_DAY - 8 * WAKE_HOUR + (hours * WAKE_HOUR).toLong(),
    durationMs = (hours * WAKE_HOUR).toLong(),
    startLevel = 80,
    endLevel = 75,
    ratePctPerHour = 0.7,
    charged = false,
    deepSleepShare = 0.9,
    deepDozeReached = true,
    deepDozeAfterMs = null,
)

private fun wakeEntry(
    kind: WakeKind,
    tag: String,
    totalMs: Long,
    count: Long,
) = WakeEntry(
    kind,
    uid = null,
    label = null,
    iconPackage = null,
    tag = tag,
    totalMs = totalMs,
    count = count,
    screenOffShare = null,
    wakeups = null,
)

private const val REASON_TAG = "178 qcom,glink-smem-native-xprt-modem"

private val REASON =
    WakeSourceDetail(
        kind = WakeKind.WAKEUP_REASON,
        tag = REASON_TAG,
        subsystem = WakeDictionary.subsystem(REASON_TAG),
        meaning = WakeDictionary.meaning(WakeKind.WAKEUP_REASON, REASON_TAG),
        span = DrainSpan.SinceUnplugged,
        entry = wakeEntry(WakeKind.WAKEUP_REASON, REASON_TAG, 0, 126),
        coverage = Coverage.WHOLE,
        nights =
            listOf(
                WakeNight(wakeNight(0, 6.7), wakeEntry(WakeKind.WAKEUP_REASON, REASON_TAG, 0, 41), Coverage.WHOLE),
                WakeNight(wakeNight(1, 7.2), wakeEntry(WakeKind.WAKEUP_REASON, REASON_TAG, 0, 88), Coverage.WHOLE),
                WakeNight(wakeNight(2, 5.1), null, Coverage.WHOLE),
                WakeNight(wakeNight(3, 8.0), null, Coverage.NONE),
            ),
        apps =
            WakeApps(
                fromWallMs = WAKE_NOW - WAKE_DAY,
                toWallMs = WAKE_NOW,
                knownMs = WAKE_DAY,
                wakes = 126,
                unclaimed = 95,
                unnamed = 2,
                apps = listOf(WakeApp(10_001, "Mail", 16), WakeApp(10_002, "Messages", 9), WakeApp(10_003, null, 4)),
            ),
    )

private const val KERNEL_TAG = "wlan_rx_wake"

private val KERNEL =
    WakeSourceDetail(
        kind = WakeKind.KERNEL,
        tag = KERNEL_TAG,
        subsystem = WakeDictionary.subsystem(KERNEL_TAG),
        meaning = WakeDictionary.meaning(WakeKind.KERNEL, KERNEL_TAG),
        span = DrainSpan.SinceUnplugged,
        entry = wakeEntry(WakeKind.KERNEL, KERNEL_TAG, 18 * 60_000L, 412).copy(screenOffShare = 0.82, wakeups = 37),
        coverage = Coverage.PARTIAL,
        nights =
            listOf(
                WakeNight(wakeNight(0, 6.7), wakeEntry(WakeKind.KERNEL, KERNEL_TAG, 4 * 60_000L, 120), Coverage.WHOLE),
                WakeNight(wakeNight(1, 7.2), wakeEntry(WakeKind.KERNEL, KERNEL_TAG, 11 * 60_000L, 240), Coverage.WHOLE),
            ),
        apps = null,
    )

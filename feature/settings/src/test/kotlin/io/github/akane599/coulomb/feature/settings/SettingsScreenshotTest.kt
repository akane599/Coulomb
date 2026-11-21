// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import android.app.Application
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
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.akane599.coulomb.core.data.SelfCost
import io.github.akane599.coulomb.core.datastore.ThemeChoice
import io.github.akane599.coulomb.core.datastore.UserSettings
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/** The settings built so far (SPEC 14.4): defaults, some on, a target set elsewhere, dark, 200%, Turkish. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w412dp-h2300dp-xhdpi")
class SettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun defaults() = shoot("defaults", UserSettings())

    @Test
    fun changed() = shoot("changed", CHANGED)

    @Test
    @Config(qualifiers = "w412dp-h2300dp-night-xhdpi")
    fun dark() = shoot("dark", CHANGED, dark = true)

    /** SPEC 11.4: the opt-in true black theme, which Settings now offers. */
    @Test
    @Config(qualifiers = "w412dp-h2300dp-night-xhdpi")
    fun black() = shoot("black", CHANGED.copy(theme = ThemeChoice.BLACK), dark = true, black = true)

    @Test
    @Config(qualifiers = "w412dp-h5200dp")
    fun text200() = shoot("200", CHANGED, fontScale = 2f)

    @Test
    @Config(qualifiers = "tr-w412dp-h2300dp-xhdpi")
    fun turkish() = shoot("tr", CHANGED)

    @Test
    fun loading() = shoot("loading", null)

    @Test
    @Config(qualifiers = "ar-w412dp-h2300dp-xhdpi")
    fun arabic() = shoot("ar", CHANGED, rtl = true)

    @Test
    @Config(qualifiers = "w1280dp-h2000dp")
    fun wide() = shoot("wide", CHANGED)

    @Test
    fun saveFailed() = shoot("save-failed", CHANGED, saveFailed = true)

    /** Alarms and the digest on while Coulomb may not post: said where they are set. */
    @Test
    fun notificationsOff() = shoot("notifications-off", CHANGED, notificationsAllowed = false)

    @Test
    @Config(qualifiers = "tr-w412dp-h2300dp-night-xhdpi")
    fun notificationsOffTurkishDark() = shoot("notifications-off-tr-dark", CHANGED, dark = true, notificationsAllowed = false)

    /** SPEC 7.1: Lite has no sampler to set a rate for; a phone without a capacity shows the charge alone. */
    @Test
    fun lite() =
        shoot(
            "lite",
            CHANGED.copy(liteMode = true, theme = ThemeChoice.BLACK, fahrenheit = true, longRawRetention = true, developerConsole = true),
            device = DEVICE.copy(language = AppLanguage.TURKISH, ownUse = OwnUse(SelfCost(1.84, null, FROM, NOW))),
        )

    /** SPEC 12.7: the switch names what an allowed app can do and read; once on, the apps the user allowed. */
    @Test
    @Config(qualifiers = "w412dp-h2500dp-xhdpi")
    fun automation() =
        shoot(
            "automation",
            CHANGED.copy(automation = true),
            device = DEVICE.copy(automation = AutomationAccess(apps = listOf("Northwind Tasks", "Tidewater Macros"))),
        )

    @Test
    @Config(qualifiers = "tr-w412dp-h2500dp-night-xhdpi")
    fun automationNoAppsTurkishDark() =
        shoot(
            "automation-none-tr-dark",
            CHANGED.copy(automation = true),
            dark = true,
            device = DEVICE.copy(automation = AutomationAccess()),
        )

    /** Before two readings of the day are stored. */
    @Test
    fun ownUseNotMeasured() = shoot("own-use-none", UserSettings(), device = DEVICE.copy(ownUse = OwnUse(null)))

    @Suppress("LongParameterList") // One switch per variant of the screen.
    private fun shoot(
        name: String,
        settings: UserSettings?,
        dark: Boolean = false,
        black: Boolean = false,
        fontScale: Float = 1f,
        saveFailed: Boolean = false,
        rtl: Boolean = false,
        notificationsAllowed: Boolean = true,
        device: SettingsDevice = DEVICE,
    ) {
        // The ldrtl qualifier does not reach the composition under Robolectric; the override does.
        val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.LayoutDirection(direction),
            ) {
                CoulombTheme(darkTheme = dark, trueBlack = black) {
                    Box(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
                        SettingsContent(
                            settings,
                            NoActions,
                            saveFailed = saveFailed,
                            notificationsAllowed = notificationsAllowed,
                            device = device,
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/settings-$name.png")
    }
}

private val CHANGED =
    UserSettings(
        showSystemApps = true,
        scaleToMeasured = true,
        chargeTargetPct = 75,
        chargeAlarm = true,
        temperatureAlarmDeciC = 450,
        insightsDigestMinute = 8 * 60 + 30,
    )
private val NoActions = SettingsActions({}, {}, {}, {})

// 2026-11-19 14:05 and 2026-11-20 09:30 UTC.
private const val FROM = 1_795_097_100_000L
private const val NOW = 1_795_167_000_000L
private val DEVICE =
    SettingsDevice(ownUse = OwnUse(SelfCost(5.9, 4_855.0, FROM, NOW)), version = "1.0.0", zone = ZoneOffset.UTC, nowMs = NOW)

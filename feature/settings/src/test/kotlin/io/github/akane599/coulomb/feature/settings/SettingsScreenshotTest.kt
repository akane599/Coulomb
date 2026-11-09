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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.akane599.coulomb.core.datastore.UserSettings
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The settings built so far (SPEC 14.4): defaults, some on, a target set elsewhere, dark, 200%, Turkish. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class SettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun defaults() = shoot("defaults", UserSettings())

    @Test
    fun changed() = shoot("changed", CHANGED)

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun dark() = shoot("dark", CHANGED, dark = true)

    @Test
    @Config(qualifiers = "w412dp-h1800dp")
    fun text200() = shoot("200", CHANGED, fontScale = 2f)

    @Test
    @Config(qualifiers = "tr-w412dp-h915dp-xhdpi")
    fun turkish() = shoot("tr", CHANGED)

    @Test
    fun loading() = shoot("loading", null)

    private fun shoot(
        name: String,
        settings: UserSettings?,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                CoulombTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
                        SettingsContent(settings, NoActions)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/settings-$name.png")
    }
}

private val CHANGED = UserSettings(showSystemApps = true, scaleToMeasured = true, chargeTargetPct = 75)
private val NoActions = SettingsActions({}, {}, {}, {})

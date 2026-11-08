// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.ui

import android.app.Application
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shell at the lab's 412 x 915 dp and at a medium width, light and dark, and at 200% text
 * (DESIGN.md, 200% text and reflow). A plain Application keeps Hilt and the collector out of it,
 * so Now is left empty; NowScreenshotTest covers it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = COMPACT)
class ShellScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun compactLight() = shoot("compact-light")

    @Test
    @Config(qualifiers = "$COMPACT_SIZE-night-xhdpi")
    fun compactDark() = shoot("compact-dark", dark = true)

    @Test
    fun compactLargeText() = shoot("compact-200", fontScale = 2f)

    @Test
    @Config(qualifiers = "w700dp-h1000dp-xhdpi")
    fun mediumRail() = shoot("medium-light")

    @Test
    fun innerScreenHasBack() {
        setShell()
        compose.onNodeWithText("Drain").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/shell-inner-settings.png")
    }

    private fun shoot(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        setShell(dark, fontScale)
        compose.onRoot().captureRoboImage("src/test/screenshots/shell-$name.png")
    }

    private fun setShell(
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                CoulombTheme(darkTheme = dark) { CoulombApp(screens = AppScreens(now = {})) }
            }
        }
    }
}

private const val COMPACT_SIZE = "w412dp-h915dp"
private const val COMPACT = "$COMPACT_SIZE-xhdpi"

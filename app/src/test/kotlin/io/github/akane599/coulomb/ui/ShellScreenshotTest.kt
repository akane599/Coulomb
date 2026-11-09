// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.navigation.Route
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

    /** SPEC 10.10: on expanded widths App detail opens beside Drain's list, and another row swaps it. */
    @Test
    @Config(qualifiers = "w1000dp-h800dp-xhdpi")
    fun expandedListDetail() {
        setShell()
        compose.onNodeWithText("Drain").performClick()
        compose.onNodeWithText("Open 10123").performClick()
        compose.onNodeWithText("Open 10456").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rows").assertExists()
        compose.onNodeWithText("Detail 10456").assertExists()
        compose.onNodeWithText("Detail 10123").assertDoesNotExist()
        compose.onRoot().captureRoboImage("src/test/screenshots/shell-expanded-list-detail.png")
    }

    /** On compact widths the detail covers the list, with back. */
    @Test
    fun compactDetailCoversTheList() {
        setShell()
        compose.onNodeWithText("Drain").performClick()
        compose.onNodeWithText("Open 10123").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rows").assertDoesNotExist()
        compose.onNodeWithText("Detail 10123").assertExists()
        compose.onNodeWithContentDescription("Back").assertExists()
    }

    /** A long app name in the top bar wraps in full; the bar grows and back stays beside it. */
    @Test
    fun compactLongTitle() {
        setShell()
        compose.onNodeWithText("Drain").performClick()
        compose.onNodeWithText("Open 10456").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/shell-compact-long-title.png")
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
                CoulombTheme(darkTheme = dark) {
                    CoulombApp(screens = StandIns)
                }
            }
        }
    }
}

/** Stand-ins without Hilt: Drain's rows open App detail, which names its UID. */
private val StandIns =
    AppScreens(
        now = {},
        plugged = { false },
        charging = {},
        drain = { navigator ->
            Column {
                Text("Rows")
                listOf(10_123, 10_456).forEach { uid ->
                    Text("Open $uid", Modifier.clickable { navigator.openDetail(Route.AppDetail(uid)) })
                }
            }
        },
        compare = {},
        appDetail = { _, uid -> Text("Detail $uid") },
        appDetailTitle = { uid -> if (uid == 10_456) "Very Long Navigation And Offline Maps Application Name" else "Halcyon" },
        health = {},
        chargeSessions = {},
        chargeSession = { _, _ -> },
    )

private const val COMPACT_SIZE = "w412dp-h915dp"
private const val COMPACT = "$COMPACT_SIZE-xhdpi"

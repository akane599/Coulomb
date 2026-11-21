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
import io.github.akane599.coulomb.core.data.Archive
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/** "Data and export" (SPEC 14.5): at rest, while it works, after each ending, with imported histories. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w412dp-h1300dp-xhdpi")
class DataScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun atRest() = shoot("rest", DataUiState(archives = emptyList()))

    @Test
    fun exporting() = shoot("exporting", DataUiState(emptyList(), DataWork.Exporting(ExportKind.BUNDLE, 45_000, 182_340)))

    @Test
    fun exported() = shoot("exported", DataUiState(emptyList(), message = DataMessage.EXPORTED))

    @Test
    fun rawUnavailable() = shoot("raw-unavailable", DataUiState(emptyList(), message = DataMessage.RAW_UNAVAILABLE))

    @Test
    fun importing() = shoot("importing", DataUiState(ARCHIVES.take(1), DataWork.Importing(60_000)))

    @Test
    fun imported() = shoot("imported", IMPORTED)

    @Test
    fun importRefused() = shoot("import-refused", DataUiState(ARCHIVES.take(1), message = DataMessage.IMPORT_DAMAGED))

    @Test
    fun deleting() = shoot("deleting", DataUiState(ARCHIVES, DataWork.Deleting))

    @Test
    @Config(qualifiers = "w412dp-h1300dp-night-xhdpi")
    fun dark() = shoot("dark", IMPORTED, dark = true)

    /** SPEC 11.4: the opt-in true black theme, which Settings now offers. */
    @Test
    @Config(qualifiers = "w412dp-h1300dp-night-xhdpi")
    fun black() = shoot("black", IMPORTED, dark = true, black = true)

    @Test
    @Config(qualifiers = "tr-w412dp-h1300dp-xhdpi")
    fun turkish() = shoot("tr", IMPORTED)

    @Test
    @Config(qualifiers = "w412dp-h3400dp")
    fun text200() = shoot("200", IMPORTED, fontScale = 2f)

    @Test
    @Config(qualifiers = "ar-w412dp-h1300dp-xhdpi")
    fun arabic() = shoot("ar", IMPORTED, rtl = true)

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun wide() = shoot("wide", IMPORTED)

    private fun shoot(
        name: String,
        state: DataUiState,
        dark: Boolean = false,
        black: Boolean = false,
        fontScale: Float = 1f,
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
                        DataContent(state, DataActions(), zone = ZoneOffset.UTC, nowMs = NOW)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/data-$name.png")
    }
}

private const val DAY = 86_400_000L

/** 2026-11-21 12:00 UTC. */
private const val NOW = 1_795_262_400_000L
private val ARCHIVES =
    listOf(
        Archive("11111111-1111-4111-8111-111111111111", "Pixel 9 Pro", NOW - 40 * DAY, NOW - 2 * DAY, NOW - DAY, 14_300_000, 41, 38),
        Archive("22222222-2222-4222-8222-222222222222", "SM-S938B", NOW - 420 * DAY, NOW - 380 * DAY, NOW - 379 * DAY, 3_100_000, 12, 9),
    )
private val IMPORTED = DataUiState(ARCHIVES, message = DataMessage.IMPORTED, offeredSettings = "{}")

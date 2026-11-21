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
import io.github.akane599.coulomb.core.data.ConsoleQuery
import io.github.akane599.coulomb.core.data.ConsoleTable
import io.github.akane599.coulomb.core.data.ConsoleValue
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/** The developer console (SPEC 14.4): each kind of answer, and the states without one. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w412dp-h1100dp-xhdpi")
class ConsoleScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun storage() = shoot("storage", ConsoleUiState(ConsoleQuery.STORAGE, STORAGE))

    @Test
    @Config(qualifiers = "tr-w412dp-h1100dp-night-xhdpi")
    fun snapshotsTurkishDark() = shoot("snapshots-tr-dark", ConsoleUiState(ConsoleQuery.SNAPSHOTS, SNAPSHOTS), dark = true)

    @Test
    @Config(qualifiers = "w412dp-h2200dp")
    fun snapshots200() = shoot("snapshots-200", ConsoleUiState(ConsoleQuery.SNAPSHOTS, SNAPSHOTS), fontScale = 2f)

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun snapshotsWide() = shoot("snapshots-wide", ConsoleUiState(ConsoleQuery.SNAPSHOTS, SNAPSHOTS))

    @Test
    fun empty() = shoot("empty", ConsoleUiState(ConsoleQuery.SOURCES, ConsoleTable(listOf("source", "status"), emptyList())))

    @Test
    fun failed() = shoot("failed", ConsoleUiState(ConsoleQuery.CAPABILITIES, failed = true))

    private fun shoot(
        name: String,
        state: ConsoleUiState,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                CoulombTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
                        ConsoleContent(state, ZoneOffset.UTC, onSelect = {}, onHide = {})
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/console-$name.png")
    }
}

private fun text(value: String) = ConsoleValue.Text(value)

private val STORAGE =
    ConsoleTable(
        listOf("table", "rows"),
        listOf(
            listOf(text("(file, bytes)"), ConsoleValue.Count(14_823_424)),
            listOf(text("battery_sample"), ConsoleValue.Count(118_402)),
            listOf(text("history_chunk"), ConsoleValue.Count(3_911)),
            listOf(text("kernel_log_event"), ConsoleValue.Count(0)),
            listOf(text("snapshot"), ConsoleValue.Count(2_764)),
            listOf(text("window_result"), ConsoleValue.Count(1_988)),
        ),
    )

// From 2026-11-20 09:30:12 UTC back.
private const val NOW = 1_795_167_012_000L
private val SNAPSHOTS =
    ConsoleTable(
        listOf("wallMs", "trigger", "kind", "durationMs"),
        listOf(
            listOf(ConsoleValue.Time(NOW), text("APP_OPENED"), text("HEAVY"), ConsoleValue.Count(1_342)),
            listOf(ConsoleValue.Time(NOW - 412_000), text("SCREEN_ON"), text("HEAVY"), ConsoleValue.Count(1_518)),
            listOf(ConsoleValue.Time(NOW - 5_164_000), text("LEVEL_CHANGED"), text("LIGHT"), ConsoleValue.Count(14)),
            listOf(ConsoleValue.Time(NOW - 9_020_000), text("SCREEN_OFF"), text("HEAVY"), ConsoleValue.None),
        ),
    )

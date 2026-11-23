// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.akane599.coulomb.core.data.TrackSpan
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * Audit DATA-005: the Night chart's spoken summary. Over a night with a stretch without history
 * its wakeup count and time awake cover only the known part, as the table beside it already says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w412dp-h3000dp-xhdpi")
class NightSummaryTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(detail: NightDetail) {
        compose.setContent {
            CoulombTheme { NightContent(Read.Ready(detail), {}, {}, zone = ZoneOffset.UTC, nowMs = NOW) }
        }
    }

    @Test
    fun aGappedNightSaysItsTotalsAreLowerBounds() {
        val gap = TrackSpan(NIGHT_START + 2 * HOUR, NIGHT_START + 2 * HOUR + 30 * MINUTE)
        show(labDetail().let { it.copy(timeline = checkNotNull(it.timeline).copy(unknown = listOf(gap))) })

        compose.onNodeWithContentDescription("The history has gaps", substring = true).assertExists()
        compose.onNodeWithContentDescription("woke at least", substring = true).assertExists()
    }

    @Test
    fun aWholeNightKeepsItsPlainSummary() {
        show(labDetail())

        compose.onNodeWithContentDescription("The phone woke", substring = true).assertExists()
        compose.onNodeWithContentDescription("at least", substring = true).assertDoesNotExist()
    }
}

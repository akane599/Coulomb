// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.ui.rowMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/** SPEC 11.6 motion on Drain: the tabs hold still on a lens switch, and rows slide or jump under reduced motion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class DrainMotionTest {
    @get:Rule
    val compose = createComposeRule()

    /** "Scale to measured" sits under the tabs, so leaving Apps does not move them (ADR-0036 §24). */
    @Test
    fun theTabsHoldStillOnALensSwitch() {
        compose.setContent {
            CoulombTheme {
                Box(Modifier.fillMaxSize()) {
                    DrainContent(state = ready(), actions = NoActions, zone = ZoneOffset.UTC)
                }
            }
        }
        val before = compose.onNodeWithText("Components").getBoundsInRoot().top
        compose.onNodeWithText("Components").performClick()
        compose.waitForIdle()
        assertThat(compose.onNodeWithText("Components").getBoundsInRoot().top).isEqualTo(before)
        compose.onNodeWithText("Wakeups").performClick()
        compose.waitForIdle()
        assertThat(compose.onNodeWithText("Wakeups").getBoundsInRoot().top).isEqualTo(before)
    }

    // A head that stays first: a lazy list keeps its first visible key in place, as Drain's tabs are.
    private var order by mutableStateOf(listOf("head", "a", "b"))

    private fun rows(reduced: Boolean) {
        compose.setContent {
            CoulombTheme(reducedMotion = reduced) {
                LazyColumn {
                    items(order, key = { it }) { id -> Box(rowMotion().fillMaxWidth().height(ROW.dp).testTag(id)) }
                }
            }
        }
    }

    private fun swap() {
        compose.mainClock.autoAdvance = false
        order = listOf("head", "b", "a")
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun top(tag: String) =
        compose
            .onNodeWithTag(tag)
            .getBoundsInRoot()
            .top.value

    @Test
    fun aReorderSlides() {
        rows(reduced = false)
        swap()
        compose.mainClock.advanceTimeBy(MID_MS)
        assertThat(top("a")).isGreaterThan(ROW.toFloat())
        assertThat(top("a")).isLessThan(2f * ROW)
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertThat(top("a")).isEqualTo(2f * ROW)
    }

    @Test
    fun aReorderUnderReducedMotionJumps() {
        rows(reduced = true)
        swap()
        compose.mainClock.advanceTimeByFrame()
        assertThat(top("a")).isEqualTo(2f * ROW)
        assertThat(top("b")).isEqualTo(ROW.toFloat())
    }

    private companion object {
        const val ROW = 50
        const val MID_MS = 64L
        const val SETTLE_MS = 2_000L
    }
}

private val NoActions =
    DrainActions(
        choose = {},
        refresh = {},
        includeSystem = {},
        scale = {},
        retry = {},
        openApp = {},
        openPrivileges = {},
        openCompare = {},
        icon = { _, _ -> null },
    )

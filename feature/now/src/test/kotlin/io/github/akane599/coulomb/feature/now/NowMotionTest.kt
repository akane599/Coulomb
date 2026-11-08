// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.now

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.component.animatePlacement
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.ui.FigureText
import io.github.akane599.coulomb.core.ui.format.Affixed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SPEC 11.6 motion on Now: rows slide to a new rank, digits roll and read as one figure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NowMotionTest {
    @get:Rule
    val compose = createComposeRule()

    private var order by mutableStateOf(listOf("a", "b"))

    private fun rows(reduced: Boolean) {
        compose.setContent {
            CoulombTheme(reducedMotion = reduced) {
                Column {
                    order.forEach { id ->
                        key(id) {
                            Box(
                                // The tag goes inside the animated placement, so the bounds the
                                // test reads are where the row is drawn, not where it is laid out.
                                Modifier
                                    .width(100.dp)
                                    .height(ROW.dp)
                                    .animatePlacement()
                                    .testTag(id),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Reorders the rows and runs the frame that recomposes them. */
    private fun swap() {
        order = listOf("b", "a")
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun top(tag: String) =
        compose
            .onNodeWithTag(tag)
            .getBoundsInRoot()
            .top.value

    @Test
    fun reorderSlidesThenSettles() {
        rows(reduced = false)
        assertThat(top("a")).isEqualTo(0f)
        compose.mainClock.autoAdvance = false
        swap()
        // The spring's first frame only stamps its start time; a few frames later it is under way.
        compose.mainClock.advanceTimeBy(MID_MS)
        // Mid-move, "a" is on its way down, not yet at its new place.
        assertThat(top("a")).isGreaterThan(0f)
        assertThat(top("a")).isLessThan(ROW.toFloat())
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertThat(top("a")).isEqualTo(ROW.toFloat())
        assertThat(top("b")).isEqualTo(0f)
    }

    @Test
    fun reorderUnderReducedMotionJumpsAndFades() {
        rows(reduced = true)
        compose.mainClock.autoAdvance = false
        swap()
        assertThat(top("a")).isEqualTo(ROW.toFloat())
        assertThat(top("b")).isEqualTo(0f)
    }

    @Test
    fun rollingFigureReadsAsOneText() {
        var number by mutableStateOf("99")
        compose.setContent {
            CoulombTheme(reducedMotion = false) {
                FadeInOnce {
                    FigureText(
                        Affixed(before = "", number = number, after = " mA"),
                        numberStyle = CoulombTheme.typography.data,
                        unitStyle = CoulombTheme.typography.data,
                        rolling = true,
                    )
                }
            }
        }
        number = "100"
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithText("100 mA").fetchSemanticsNodes()).hasSize(1)
        // No digit is a node of TalkBack's tree, so it never reads "1", "0", "0".
        assertThat(compose.onAllNodesWithText("1").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("0").fetchSemanticsNodes()).isEmpty()
    }

    private companion object {
        const val ROW = 50
        const val MID_MS = 64L
        const val SETTLE_MS = 2_000L
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * A list's toggle stays the same node when it opens or closes, so TalkBack keeps its focus there
 * instead of losing it to a removed node (audit 2026-11-08; ADR-0036 §26). This stands in for the
 * TalkBack walk, which the API 37 image cannot run: it has no TalkBack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w412dp-h3000dp-xhdpi")
class DrainFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(lens: Lens) {
        compose.setContent {
            CoulombTheme {
                Box(Modifier.fillMaxSize()) {
                    DrainContent(state = ready(), actions = NoActions, zone = ZoneOffset.UTC, initialLens = lens)
                }
            }
        }
    }

    private fun sameNode(id: Int) = SemanticsMatcher("node $id") { it.id == id }

    @Test
    fun showAllStaysTheFocusedNode() {
        show(Lens.WAKEUPS)
        val toggle = compose.onNodeWithText("Show all", substring = true)
        val id = toggle.fetchSemanticsNode().id
        toggle.performClick()
        compose.waitForIdle()
        compose.onNode(sameNode(id)).assertTextEquals("Show fewer")
    }

    @Test
    fun theRemainderRowStaysTheFocusedNode() {
        show(Lens.APPS)
        val row = compose.onNodeWithText("more app", substring = true)
        val id = row.fetchSemanticsNode().id
        row.performClick()
        compose.waitForIdle()
        val after = compose.onNode(sameNode(id)).fetchSemanticsNode()
        assertThat(after.config[SemanticsProperties.StateDescription]).isEqualTo("Expanded")
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

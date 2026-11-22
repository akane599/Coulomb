// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.page.InlineTable
import io.github.akane599.coulomb.core.ui.page.TableRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit PLAT-001: a table row is one TalkBack stop, so its provenance mark's "Explain provenance"
 * action is the row's, as Health's cross-check shows it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MarkedRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aMarkedRowOffersTheMarksExplanation() {
        compose.setContent {
            CoulombTheme {
                InlineTable(
                    listOf("Source", "Capacity"),
                    listOf(
                        TableRow("Charge counter", listOf("4,812 mAh"), mark = Provenance.MEASURED),
                        TableRow("Design", listOf("5,000 mAh")),
                    ),
                )
            }
        }
        val marked = compose.onNode(hasContentDescription("Charge counter", substring = true)).fetchSemanticsNode()
        val actions = marked.config.getOrElse(SemanticsActions.CustomActions) { emptyList() }
        assertThat(actions.map { it.label }).containsExactly("Explain provenance")
        compose
            .onNode(
                hasContentDescription("Design", substring = true),
            ).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
    }
}

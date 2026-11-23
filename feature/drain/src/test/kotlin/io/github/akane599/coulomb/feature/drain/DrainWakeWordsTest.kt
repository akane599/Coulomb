// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.WakeKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * Audit DATA-004: what the Wakeups lens says of records it does not have. A window keeps wake
 * records of 1 s or more and the 30 longest of a kind, so "none" and an exact sum over several
 * windows are both more than is known.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w412dp-h3000dp-xhdpi")
class DrainWakeWordsTest {
    @get:Rule
    val compose = createComposeRule()

    private val noActions =
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

    private fun show(report: DrainReport) {
        compose.setContent {
            CoulombTheme {
                Box(Modifier.fillMaxSize()) {
                    DrainContent(state = ready(report), actions = noActions, zone = ZoneOffset.UTC, initialLens = Lens.WAKEUPS)
                }
            }
        }
    }

    @Test
    fun anEmptyBatterystatsGroupIsNoneOfOneSecondNotNone() {
        // The partial wakelocks are empty; the other groups keep their records.
        show(
            report().let {
                it.copy(
                    wakes =
                        it.wakes.filter { wake ->
                            wake.kind != WakeKind.PARTIAL
                        },
                )
            },
        )

        compose.onNodeWithText("None of 1 s or longer recorded.").assertExists()
        compose.onAllNodesWithText("None recorded.").fetchSemanticsNodes().let { check(it.isEmpty()) { "an S5 group still says none" } }
    }

    @Test
    fun withNothingKeptAtAllTheLensSaysNothingOfOneSecondOrLonger() {
        show(report().copy(wakes = emptyList(), alarms = emptyList()))

        compose.onNodeWithText("for 1 s or longer", substring = true).assertExists()
        compose.onNodeWithText("None recorded in this window.").assertDoesNotExist()
    }

    @Test
    fun aSummedSpanSaysItsFiguresMayBeLow() {
        show(report().copy(wakesLowerBound = true))
        compose.onNodeWithText("so a figure may be low", substring = true).assertExists()
    }

    @Test
    fun oneWindowsOwnRecordsCarryNoSuchNote() {
        show(report())
        compose.onNodeWithText("so a figure may be low", substring = true).assertDoesNotExist()
    }
}

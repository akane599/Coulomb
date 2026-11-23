// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.ui

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.InsightState
import io.github.akane599.coulomb.core.model.insight.ActionId
import io.github.akane599.coulomb.core.model.insight.Evidence
import io.github.akane599.coulomb.core.model.insight.EvidenceUnit
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.model.insight.RuleId
import io.github.akane599.coulomb.navigation.AppNavigator
import io.github.akane599.coulomb.navigation.Destination
import io.github.akane599.coulomb.navigation.Route
import org.junit.Test

/**
 * Where an insight card's link lands (audit BUG-004): on the destination's first screen, never on
 * a screen left open there, as Now's own links do.
 */
class InsightLinksTest {
    private fun navigator() =
        AppNavigator(
            stacks = Destination.entries.associateWith { NavBackStack<NavKey>(it.root) },
            currentState = mutableStateOf(Destination.Now),
        )

    private val insight =
        Insight(
            id = 1,
            rule = RuleId.EXCESSIVE_WAKELOCK,
            severity = RuleId.EXCESSIVE_WAKELOCK.severity,
            state = InsightState.NEW,
            subjectKey = null,
            uid = APP,
            windowId = null,
            evidence = Evidence(1.0, 1.0, EvidenceUnit.HOURS, 0, 1),
            createdWallMs = 0,
        )

    @Test
    fun anAppDetailLinkOpensAboveDrainsList() {
        val navigator = navigator()
        navigator.select(Destination.Drain)
        navigator.open(Route.DrainCompare)
        navigator.select(Destination.Now)

        navigator.follow(ActionId.OPEN_APP_DETAIL, insight)

        assertThat(navigator.backStack.toList()).containsExactly(Route.Drain, Route.AppDetail(APP)).inOrder()
    }

    @Test
    fun aHealthLinkShowsHealth() {
        val navigator = navigator()
        navigator.select(Destination.Health)
        navigator.openDetail(Route.ChargeSession(SESSION))
        navigator.select(Destination.Now)

        navigator.follow(ActionId.OPEN_HEALTH, insight)

        assertThat(navigator.backStack.toList()).containsExactly(Route.Health)
    }

    private companion object {
        const val APP = 10_123
        const val SESSION = 5L
    }
}

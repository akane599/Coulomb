// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.insight

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.InsightState
import io.github.akane599.coulomb.core.model.insight.ActionId
import io.github.akane599.coulomb.core.model.insight.Evidence
import io.github.akane599.coulomb.core.model.insight.EvidenceUnit
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.model.insight.RuleId
import org.junit.Test

/** SPEC 9.2 "Action offered": which button an insight's card gets. */
class InsightActionsTest {
    private fun insight(
        uid: Int? = 10_123,
        sessionId: Long? = null,
    ) = Insight(
        id = 1,
        rule = RuleId.EXCESSIVE_WAKELOCK,
        severity = RuleId.EXCESSIVE_WAKELOCK.severity,
        state = InsightState.NEW,
        subjectKey = null,
        uid = uid,
        windowId = null,
        evidence = Evidence(1.0, 1.0, EvidenceUnit.HOURS, 0, 1, sessionId = sessionId),
        createdWallMs = 0,
    )

    @Test
    fun theFirstOfferedActionIsTheButton() {
        val offered = listOf(ActionId.RESTRICT_BACKGROUND, ActionId.STANDBY_BUCKET)
        assertThat(InsightActions.pick(offered, insight())).isEqualTo(ActionId.RESTRICT_BACKGROUND)
    }

    @Test
    fun anActionForAnAppNeedsTheApp() {
        assertThat(InsightActions.pick(listOf(ActionId.RESTRICT_BACKGROUND), insight(uid = null))).isNull()
        assertThat(InsightActions.pick(listOf(ActionId.OPEN_APP_DETAIL), insight(uid = null))).isNull()
        assertThat(InsightActions.pick(listOf(ActionId.OPEN_HEALTH), insight(uid = null))).isEqualTo(ActionId.OPEN_HEALTH)
    }

    @Test
    fun aNightNeedsItsSession() {
        assertThat(InsightActions.pick(listOf(ActionId.OPEN_SESSION), insight())).isNull()
        assertThat(InsightActions.pick(listOf(ActionId.OPEN_SESSION), insight(sessionId = 7))).isEqualTo(ActionId.OPEN_SESSION)
    }

    @Test
    fun anActionWithoutAPlaceOrLeftOutHasNoButton() {
        assertThat(InsightActions.pick(listOf(ActionId.OPEN_SELF_DIAGNOSTIC, ActionId.SWITCH_TO_LITE), insight())).isNull()
        // On the app's own page, "open the app's page" is left out.
        assertThat(InsightActions.pick(listOf(ActionId.OPEN_APP_DETAIL), insight(), except = setOf(ActionId.OPEN_APP_DETAIL))).isNull()
    }

    @Test
    fun everyActionHasWordsOrIsKnownToLackAPlace() {
        val without = ActionId.entries.filter { InsightActions.label(it) == null }
        assertThat(without).containsExactly(ActionId.OPEN_BATTERY_PROTECTION, ActionId.OPEN_SELF_DIAGNOSTIC, ActionId.SWITCH_TO_LITE)
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.InsightSeverity.ACT
import io.github.akane599.coulomb.core.model.InsightSeverity.INFO
import io.github.akane599.coulomb.core.model.InsightSeverity.WATCH
import io.github.akane599.coulomb.core.model.InsightState
import org.junit.Test

/** SPEC 9.1 and 9.4: one open insight per rule and subject, and when a finding may come back. */
class LifecycleTest {
    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun stored(
        state: InsightState,
        severity: io.github.akane599.coulomb.core.model.InsightSeverity = WATCH,
        changedAgoMs: Long = hour,
        snoozeUntil: Long? = null,
    ) = StoredInsight(7, severity, state, now - changedAgoMs, snoozeUntil)

    @Test
    fun aFirstFindingOpensAnInsight() {
        assertThat(InsightLifecycle.decide(WATCH, null, now)).isEqualTo(Decision.Open)
    }

    @Test
    fun anOpenInsightIsKeptUnlessTheSeverityRises() {
        for (state in listOf(InsightState.NEW, InsightState.SEEN)) {
            assertThat(InsightLifecycle.decide(WATCH, stored(state), now)).isEqualTo(Decision.Keep)
            assertThat(InsightLifecycle.decide(INFO, stored(state), now)).isEqualTo(Decision.Keep)
            assertThat(InsightLifecycle.decide(ACT, stored(state), now)).isEqualTo(Decision.Raise(7))
        }
    }

    @Test
    fun aDismissedFindingReturnsAfter24HoursOrWhenItRises() {
        for (state in listOf(InsightState.DISMISSED, InsightState.ACTED)) {
            assertThat(InsightLifecycle.decide(WATCH, stored(state, changedAgoMs = 24 * hour - 1), now)).isEqualTo(Decision.Keep)
            assertThat(InsightLifecycle.decide(WATCH, stored(state, changedAgoMs = 24 * hour), now)).isEqualTo(Decision.Open)
            // A rise opens a new insight; the dismissed one is not reopened.
            assertThat(InsightLifecycle.decide(ACT, stored(state, changedAgoMs = hour), now)).isEqualTo(Decision.Open)
        }
    }

    @Test
    fun aSnoozeHidesTheFindingForItsTime() {
        val snoozed = stored(InsightState.SNOOZED, snoozeUntil = now + hour)
        assertThat(InsightLifecycle.decide(WATCH, snoozed, now)).isEqualTo(Decision.Keep)
        assertThat(InsightLifecycle.decide(WATCH, snoozed, now + hour)).isEqualTo(Decision.Open)
        assertThat(InsightLifecycle.decide(ACT, snoozed, now)).isEqualTo(Decision.Open)
        // A snooze stored without its end counts from when it was set.
        assertThat(InsightLifecycle.decide(WATCH, stored(InsightState.SNOOZED), now)).isEqualTo(Decision.Open)
    }

    @Test
    fun aClockSetBackOnlyDelaysTheReturn() {
        val dismissedInTheFuture = stored(InsightState.DISMISSED, changedAgoMs = -5 * hour)
        assertThat(InsightLifecycle.decide(WATCH, dismissedInTheFuture, now)).isEqualTo(Decision.Keep)
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.actions.FakePhone.Companion.APP
import io.github.akane599.coulomb.core.database.entity.ActionLogEntity
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.ActionOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Audit PERF-007: a screen reads a page of the log, and only a real action makes it read the phone again. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActionChangesTest {
    private val log = FakeLog()
    private val center = ActionCenter(FakePhone(), log, { true }, { 1_000L })

    private fun automationCall(name: String) =
        ActionLogEntity(
            actionType = "automation_call",
            target = name,
            beforeValue = null,
            afterValue = "accepted",
            tier = AccessTier.T0_UNPRIVILEGED,
            executedWall = 1_000L,
            revertedWall = null,
            origin = ActionOrigin.AUTOMATION,
            insightId = null,
            externalDriftWall = null,
            externalDriftValue = null,
        )

    @Test
    fun anAutomationCallsOwnRowChangesNothingTheScreenReadsThePhoneFor() =
        runTest {
            val seen = mutableListOf<Any>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { center.changes.collect { seen += it } }
            assertThat(seen).hasSize(1)

            log.insert(automationCall("snapshot_now"))
            log.insert(automationCall("doze_profile"))
            assertThat(seen).hasSize(1)

            // A real action does.
            center.run(ActionRequest.RestrictBackground(APP))
            assertThat(seen.size).isGreaterThan(1)
        }

    /**
     * Audit PERF-002: Room runs the "in force" query again on every log write, an automation
     * call's own row too. The list it gives is the same, and a screen that reads the phone for each
     * emission (App detail's actions) must not hear of it.
     */
    @Test
    fun anAutomationCallsOwnRowDoesNotMoveTheChangesInForce() =
        runTest {
            val inForce = mutableListOf<List<ActionRecord>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { center.active.collect { inForce += it } }
            assertThat(inForce).hasSize(1)

            repeat(3) { log.insert(automationCall("snapshot_now $it")) }
            assertThat(inForce).hasSize(1)

            center.run(ActionRequest.RestrictBackground(APP))
            assertThat(inForce).hasSize(2)
            assertThat(inForce.last()).hasSize(1)
        }

    /** The same through `AppActionsViewModel`'s own inputs, `combine(center.changes, center.active)`. */
    @Test
    fun theAppActionsInputsIgnoreAnAutomationCallsOwnRow() =
        runTest {
            var emissions = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                combine(center.changes, center.active) { _, active -> active }.collect { emissions++ }
            }
            val before = emissions

            repeat(3) { log.insert(automationCall("snapshot_now $it")) }

            assertThat(emissions).isEqualTo(before)
        }

    @Test
    fun theLogIsReadAPageAtATime() =
        runTest {
            repeat(5) { log.insert(automationCall("call $it")) }
            assertThat(center.records(2).first().map { it.target }).containsExactly("call 4", "call 3").inOrder()
            assertThat(center.active.first()).isEmpty()
        }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.actions.FakePhone.Companion.APP
import io.github.akane599.coulomb.core.database.entity.ActionLogEntity
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.ActionOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    @Test
    fun theLogIsReadAPageAtATime() =
        runTest {
            repeat(5) { log.insert(automationCall("call $it")) }
            assertThat(center.records(2).first().map { it.target }).containsExactly("call 4", "call 3").inOrder()
            assertThat(center.active.first()).isEmpty()
        }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.actions.FakePhone.Companion.APP
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.ActionOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Audit CONC-001: deleting all data never deletes the only record of a change on the phone. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActionEraseTest {
    private val phone = FakePhone()
    private val log = FakeLog()
    private val center = ActionCenter(phone, log, { true }, { 1_000L })
    private val restrict = ActionRequest.RestrictBackground(APP)

    @Test
    fun aChangeInForceKeepsTheDataAndNothingIsErased() =
        runTest {
            center.run(restrict) as ActionResult.Done
            var erased = false
            assertThat(center.eraseWhenNoneInForce { erased = true }).isEqualTo(1)
            assertThat(erased).isFalse()
        }

    @Test
    fun noChangeStartsOnceTheEraseHasRun() =
        runTest {
            var erased = false
            assertThat(center.eraseWhenNoneInForce { erased = true }).isEqualTo(0)
            assertThat(erased).isTrue()
            assertThat(center.run(restrict)).isEqualTo(ActionResult.Blocked(ActionBlock.OFF))
            assertThat(phone.acts).isEmpty()
        }

    @Test
    fun anEraseThatFailedOpensTheActionCenterAgain() =
        runTest {
            val failure = runCatching { center.eraseWhenNoneInForce { error("disk") } }
            assertThat(failure.isFailure).isTrue()
            assertThat(center.run(restrict)).isInstanceOf(ActionResult.Done::class.java)
        }

    /** Audit DATA-006: an Automation API call's own row lands before the erase or not at all. */
    @Test
    fun anAutomationCallDuringOrAfterTheEraseLeavesNoRow() =
        runTest {
            val inside = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val erase =
                launch {
                    center.eraseWhenNoneInForce {
                        inside.complete(Unit)
                        release.await()
                    }
                }
            inside.await()
            val during = async { center.logCall("snapshot_now <org.example>", null, "accepted") }
            runCurrent()
            release.complete(Unit)
            erase.join()

            assertThat(during.await()).isNull()
            assertThat(center.logCall("snapshot_now <org.example>", null, "accepted")).isNull()
            assertThat(log.all).isEmpty()
        }

    @Test
    fun anAutomationCallIsLoggedWhenNoEraseRunsOrAfterOneFailed() =
        runTest {
            assertThat(center.logCall("snapshot_now <org.example>", null, "accepted")).isNotNull()
            runCatching { center.eraseWhenNoneInForce { error("disk") } }
            assertThat(center.logCall("set_charge_target", "80", "70")).isNotNull()

            assertThat(log.all).hasSize(2)
            assertThat(log.all.map { it.origin }.toSet()).containsExactly(ActionOrigin.AUTOMATION)
            assertThat(log.all.map { it.tier }.toSet()).containsExactly(AccessTier.T0_UNPRIVILEGED)
            assertThat(log.all.map { it.actionType }.toSet()).containsExactly(ActionType.AUTOMATION_CALL.id)
        }
}

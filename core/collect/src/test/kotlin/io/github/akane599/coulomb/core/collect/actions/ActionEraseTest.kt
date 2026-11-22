// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.actions.FakePhone.Companion.APP
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Audit CONC-001: deleting all data never deletes the only record of a change on the phone. */
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
}

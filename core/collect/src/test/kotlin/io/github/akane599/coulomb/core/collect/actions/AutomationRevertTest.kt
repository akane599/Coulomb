// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.analytics.DozeProfile
import io.github.akane599.coulomb.core.model.ActionOrigin
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Audit BUG-002: with the Action Center off, automation cannot revert a profile; the user still can. */
class AutomationRevertTest {
    private val phone = FakePhone()
    private val log = FakeLog()
    private var on = true
    private val center = ActionCenter(phone, log, { on }, { 1_000L })

    @Test
    fun offBlocksTheAutomationRevertAndNotTheUsers() =
        runTest {
            val applied = center.run(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED)) as ActionResult.Done
            val written = phone.doze
            on = false
            assertThat(center.revert(applied.id, ActionOrigin.AUTOMATION)).isEqualTo(ActionResult.Blocked(ActionBlock.OFF))
            assertThat(phone.doze).isEqualTo(written)
            assertThat(center.revert(applied.id)).isInstanceOf(ActionResult.Done::class.java)
            assertThat(phone.doze).isNull()
        }

    @Test
    fun onTheAutomationRevertRuns() =
        runTest {
            val applied = center.run(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED)) as ActionResult.Done
            assertThat(center.revert(applied.id, ActionOrigin.AUTOMATION)).isInstanceOf(ActionResult.Done::class.java)
            assertThat(phone.doze).isNull()
        }
}

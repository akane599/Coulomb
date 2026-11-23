// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.actions

import android.app.Application
import android.database.sqlite.SQLiteFullException
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.actions.ActionPreview
import io.github.akane599.coulomb.core.collect.actions.ActionRequest
import io.github.akane599.coulomb.core.collect.actions.ActionResult
import io.github.akane599.coulomb.core.collect.actions.ActionRisk
import io.github.akane599.coulomb.core.collect.actions.ActionType
import io.github.akane599.coulomb.core.model.ActionOrigin
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit BUG-022: what follows a confirmed action. The change and its log row are stored by then,
 * so a follow-up that fails must neither hide that result, nor leave the flow busy, nor end the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ActionFlowTest {
    private val uncaught = mutableListOf<Throwable>()
    private val done = ActionResult.Done(7, "ignore")

    private val engine =
        object : ActionEngine {
            override suspend fun run(
                request: ActionRequest,
                origin: ActionOrigin,
                insightId: Long?,
            ): ActionResult = done

            override suspend fun revert(id: Long): ActionResult = done

            override suspend fun revertAll(): List<ActionResult> = listOf(done)
        }

    /** A view model's scope in all but its dispatcher, with a recorder where the thread's own handler would be. */
    private fun TestScope.screenScope() =
        CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> uncaught += e })

    private val preview =
        ActionPreview(ActionRequest.RestrictBackground("com.example.app"), ActionRisk.LOW, "cmd", "undo", "allow", "ignore", null)

    @Test
    fun aFailedInsightUpdateAfterADoneActionStillSaysDone() =
        runTest {
            val flow = ActionFlow(engine, screenScope(), onDone = { throw SQLiteFullException("database or disk is full") })
            flow.open(preview)

            flow.confirm()

            assertThat(uncaught).isEmpty()
            assertThat(flow.state.value.busy).isFalse()
            assertThat(
                flow.state.value.note
                    ?.result,
            ).isEqualTo(done)
        }

    /** Whatever ends the work, the flow is free again: a stuck "busy" would swallow every later tap. */
    @Test
    fun aBugInTheFollowUpLeavesTheFlowFree() =
        runTest {
            val flow = ActionFlow(engine, screenScope(), onDone = { error("a bug") })
            flow.open(preview)

            flow.confirm()

            assertThat(uncaught.single()).isInstanceOf(IllegalStateException::class.java)
            assertThat(flow.state.value.busy).isFalse()
        }

    @Test
    fun aRevertIsNotFollowedByOnDone() =
        runTest {
            var followed = 0
            val flow = ActionFlow(engine, screenScope(), onDone = { followed++ })

            flow.revert(7, ActionType.RESTRICT_BACKGROUND)
            assertThat(followed).isEqualTo(0)
            assertThat(
                flow.state.value.note
                    ?.revert,
            ).isTrue()

            flow.open(preview)
            flow.confirm()
            assertThat(followed).isEqualTo(1)
        }
}

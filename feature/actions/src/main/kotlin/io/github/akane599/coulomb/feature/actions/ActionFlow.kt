// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.actions

import io.github.akane599.coulomb.core.collect.actions.ActionCenter
import io.github.akane599.coulomb.core.collect.actions.ActionPreview
import io.github.akane599.coulomb.core.collect.actions.ActionResult
import io.github.akane599.coulomb.core.collect.actions.ActionType
import io.github.akane599.coulomb.core.model.ActionOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the last action or revert ended, for the line a screen says it with. */
internal data class ActionNote(
    val result: ActionResult,
    val type: ActionType?,
    val revert: Boolean,
    /** The value the action asked for, to tell when Android set another. */
    val asked: String? = null,
)

/** The preview that is open, whether its command is running, and how the last one ended. */
internal data class FlowState(
    val preview: ActionPreview? = null,
    val busy: Boolean = false,
    val note: ActionNote? = null,
)

/**
 * One screen's way through an action (SPEC 13.1): a preview opens, the user confirms, the engine
 * runs it and the result is said. One at a time; a tap while a command runs does nothing.
 */
internal class ActionFlow(
    private val center: ActionCenter,
    private val scope: CoroutineScope,
    private val origin: ActionOrigin = ActionOrigin.USER,
    private val insightId: Long? = null,
    /** After an action, not a revert, ran and was read back. */
    private val onDone: suspend () -> Unit = {},
) {
    private val flow = MutableStateFlow(FlowState())
    val state: StateFlow<FlowState> = flow.asStateFlow()

    fun open(preview: ActionPreview) = flow.update { if (it.busy) it else it.copy(preview = preview, note = null) }

    fun dismiss() = flow.update { if (it.busy) it else it.copy(preview = null) }

    /** Runs the open preview's request. The engine reads the phone again, so a preview gone stale is refused there. */
    fun confirm() {
        val preview = flow.value.preview ?: return
        work {
            val result = center.run(preview.request, origin, insightId)
            if (result is ActionResult.Done) onDone()
            ActionNote(result, preview.request.type, revert = false, asked = preview.after)
        }
    }

    fun revert(
        id: Long,
        type: ActionType?,
    ) = work { ActionNote(center.revert(id), type, revert = true) }

    /** SPEC 13.1 "Revert all". The note is the first revert that did not go through, or that all did. */
    fun revertAll(then: suspend () -> Unit = {}) =
        work {
            val results = center.revertAll()
            then()
            ActionNote(results.firstOrNull { it !is ActionResult.Done } ?: ActionResult.Done(0, ""), type = null, revert = true)
        }

    private fun work(block: suspend () -> ActionNote) {
        if (flow.value.busy) return
        flow.update { it.copy(busy = true, note = null) }
        scope.launch {
            val note = block()
            flow.value = FlowState(note = note)
        }
    }
}

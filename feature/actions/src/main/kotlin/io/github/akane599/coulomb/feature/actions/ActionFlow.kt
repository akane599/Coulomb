// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.actions

import android.database.SQLException
import android.util.Log
import io.github.akane599.coulomb.core.collect.actions.ActionCenter
import io.github.akane599.coulomb.core.collect.actions.ActionPreview
import io.github.akane599.coulomb.core.collect.actions.ActionRequest
import io.github.akane599.coulomb.core.collect.actions.ActionResult
import io.github.akane599.coulomb.core.collect.actions.ActionType
import io.github.akane599.coulomb.core.model.ActionOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

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
    private val engine: ActionEngine,
    private val scope: CoroutineScope,
    private val origin: ActionOrigin = ActionOrigin.USER,
    private val insightId: Long? = null,
    /** After an action, not a revert, ran and was read back. */
    private val onDone: suspend () -> Unit = {},
) {
    constructor(
        center: ActionCenter,
        scope: CoroutineScope,
        origin: ActionOrigin = ActionOrigin.USER,
        insightId: Long? = null,
        onDone: suspend () -> Unit = {},
    ) : this(CenterEngine(center), scope, origin, insightId, onDone)

    private val flow = MutableStateFlow(FlowState())
    val state: StateFlow<FlowState> = flow.asStateFlow()

    fun open(preview: ActionPreview) = flow.update { if (it.busy) it else it.copy(preview = preview, note = null) }

    fun dismiss() = flow.update { if (it.busy) it else it.copy(preview = null) }

    /** Runs the open preview's request. The engine reads the phone again, so a preview gone stale is refused there. */
    fun confirm() {
        val preview = flow.value.preview ?: return
        work {
            val result = engine.run(preview.request, origin, insightId)
            if (result is ActionResult.Done) afterDone()
            ActionNote(result, preview.request.type, revert = false, asked = preview.after)
        }
    }

    fun revert(
        id: Long,
        type: ActionType?,
    ) = work { ActionNote(engine.revert(id), type, revert = true) }

    /** SPEC 13.1 "Revert all". The note is the first revert that did not go through, or that all did. */
    fun revertAll(then: suspend () -> Unit = {}) =
        work {
            val results = engine.revertAll()
            then()
            ActionNote(results.firstOrNull { it !is ActionResult.Done } ?: ActionResult.Done(0, ""), type = null, revert = true)
        }

    private fun work(block: suspend () -> ActionNote) {
        if (flow.value.busy) return
        flow.update { it.copy(busy = true, note = null) }
        scope.launch {
            var note: ActionNote? = null
            try {
                note = block()
            } finally {
                // However the work ends, the flow is free again: a "busy" left behind would swallow
                // every later tap (audit BUG-022).
                flow.value = FlowState(note = note)
            }
        }
    }

    /**
     * The screen's follow-up to a change that went through, such as marking its insight acted on.
     * The change and its row are stored by now: a follow-up that fails for storage is logged, and
     * neither hides that result nor ends the process (audit BUG-022).
     */
    private suspend fun afterDone() {
        try {
            onDone()
        } catch (e: SQLException) {
            Log.w(TAG, "after the action", e)
        } catch (e: IOException) {
            Log.w(TAG, "after the action", e)
        }
    }

    private companion object {
        const val TAG = "ActionFlow"
    }
}

/** What [ActionFlow] asks of the Action Center: a stand-in in tests, whose constructor is the engine module's own. */
internal interface ActionEngine {
    suspend fun run(
        request: ActionRequest,
        origin: ActionOrigin,
        insightId: Long?,
    ): ActionResult

    suspend fun revert(id: Long): ActionResult

    suspend fun revertAll(): List<ActionResult>
}

private class CenterEngine(
    private val center: ActionCenter,
) : ActionEngine {
    override suspend fun run(
        request: ActionRequest,
        origin: ActionOrigin,
        insightId: Long?,
    ): ActionResult = center.run(request, origin, insightId)

    override suspend fun revert(id: Long): ActionResult = center.revert(id)

    override suspend fun revertAll(): List<ActionResult> = center.revertAll()
}

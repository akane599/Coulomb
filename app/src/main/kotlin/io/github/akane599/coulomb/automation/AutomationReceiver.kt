// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import io.github.akane599.coulomb.core.collect.actions.AutomationCalls
import io.github.akane599.coulomb.core.common.AutomationContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SPEC 12.7's three broadcast actions. The manifest guards the receiver with the automation
 * permission and keeps it disabled while the setting is off. The answer goes back only as the
 * result of an ordered broadcast: a code of [AutomationContract.Result], a reason as the data, and
 * the API version in the extras. A sender that did not ask for a result gets none.
 *
 * The work runs off the main thread with the broadcast held open (`goAsync`, BroadcastReceiver
 * android-17.0.0_r1 L446, android-16.0.0_r4 L443), inside the ten seconds a receiver has. It sets
 * no alarm, schedules no job and holds no wakelock (SPEC 7.4).
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val id = context.packageName
        val call = read(id, intent, sender()) ?: return
        // Read before goAsync: afterwards the receiver no longer holds the broadcast (isOrderedBroadcast,
        // android-17.0.0_r1 L664, android-16.0.0_r4 L661).
        val ordered = isOrderedBroadcast
        val pending = goAsync()
        val engine = EntryPointAccessors.fromApplication(context.applicationContext, AutomationEntryPoint::class.java).automationEngine()
        // The work is its own job: a change under way is finished and logged even when the answer is due first.
        val work = scope.async { engine.handle(call) }
        scope.launch {
            try {
                val answer = answer(call, work)
                if (ordered) {
                    val extras =
                        Bundle().apply {
                            putInt(AutomationContract.extraApiVersion(id), AutomationContract.API_VERSION)
                            answer.retryAfterS?.let { putLong(AutomationContract.extraRetryAfterS(id), it) }
                        }
                    pending.setResult(answer.code, answer.reason, extras)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** [work]'s answer, or "unverified: timeout" when it is not there in time, or "failed" when it threw. */
    private suspend fun answer(
        call: AutomationCall,
        work: Deferred<AutomationAnswer>,
    ): AutomationAnswer =
        try {
            withTimeoutOrNull(BUDGET_MS) { work.await() } ?: TIMED_OUT
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Room, the settings file or the worker failed for this call; the caller is told, not left waiting.
            Log.w(TAG, "automation call ${call.name} failed", e)
            FAILED
        }

    /**
     * The sender's package, which is known only when the sender shared its identity
     * (`BroadcastOptions.setShareIdentityEnabled`; getSentFromPackage, BroadcastReceiver
     * android-17.0.0_r1 L739, android-16.0.0_r4 L734). It names the caller in the log and decides nothing.
     */
    private fun sender(): String? = sentFromPackage

    internal companion object {
        private const val TAG = "CoulombAutomation"

        /** Under the ten seconds a foreground broadcast's receiver has. */
        private const val BUDGET_MS = 8_000L
        private val FAILED = AutomationAnswer(AutomationContract.Result.FAILED, AutomationContract.Reason.FAILED)

        /** The work goes on; whether it changed anything is in the action log, not in this answer. */
        private val TIMED_OUT = AutomationAnswer(AutomationContract.Result.UNVERIFIED, AutomationContract.Reason.TIMEOUT)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * [call], or null for extras that cannot be read. A sender can put a value in the bundle
         * that this process cannot unparcel (BadParcelableException, Parcel android-17.0.0_r1
         * L5161, android-16.0.0_r4 L5142); thrown out of `onReceive` it would end the process.
         */
        fun read(
            id: String,
            intent: Intent,
            caller: String?,
        ): AutomationCall? =
            try {
                call(id, intent, caller)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                Log.w(TAG, "automation call with unreadable extras", e)
                null
            }

        /** [intent] as a call, or null for an action that is not this API's. Unknown extras are ignored. */
        fun call(
            id: String,
            intent: Intent,
            caller: String?,
        ): AutomationCall? =
            when (intent.action) {
                AutomationContract.actionSnapshotNow(id) -> {
                    AutomationCall(AutomationCalls.SNAPSHOT_NOW, caller = caller)
                }

                AutomationContract.actionSetChargeTarget(id) -> {
                    AutomationCall(AutomationCalls.SET_CHARGE_TARGET, chargeTargetPct = target(id, intent), caller = caller)
                }

                AutomationContract.actionApplyDozeProfile(id) -> {
                    val profile = intent.getStringExtra(AutomationContract.extraDozeProfile(id))
                    AutomationCall(AutomationCalls.APPLY_DOZE_PROFILE, dozeProfile = profile, caller = caller)
                }

                else -> {
                    null
                }
            }

        /** The target as an int, or as a decimal string, which is how some automation apps send every extra. */
        private fun target(
            id: String,
            intent: Intent,
        ): Int? {
            val key = AutomationContract.extraChargeTargetPct(id)
            if (!intent.hasExtra(key)) return null
            return intent.getStringExtra(key)?.trim()?.toIntOrNull()
                ?: intent.getIntExtra(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        }
    }
}

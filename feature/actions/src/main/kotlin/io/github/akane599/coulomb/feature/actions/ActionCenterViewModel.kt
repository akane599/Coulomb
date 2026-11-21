// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.actions

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akane599.coulomb.core.analytics.DozeProfile
import io.github.akane599.coulomb.core.collect.actions.ActionBlock
import io.github.akane599.coulomb.core.collect.actions.ActionCenter
import io.github.akane599.coulomb.core.collect.actions.ActionPreview
import io.github.akane599.coulomb.core.collect.actions.ActionRecord
import io.github.akane599.coulomb.core.collect.actions.ActionRequest
import io.github.akane599.coulomb.core.collect.actions.ActionType
import io.github.akane599.coulomb.core.collect.actions.DozeState
import io.github.akane599.coulomb.core.datastore.SettingsStore
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Where a log row's times are read. */
internal class LogClock(
    val zone: ZoneId,
    val nowMs: Long,
)

/** A row of the log with the name of the app it is about, where it is about one. */
internal data class LogRow(
    val record: ActionRecord,
    val appLabel: String?,
)

/** The phone's own actions against the phone as it is now. */
internal data class DeviceBoard(
    val doze: DozeState?,
    val balanced: ActionPreview,
    val aggressive: ActionPreview,
    val takeOver: ActionPreview,
    val saver: ActionPreview,
    val reset: ActionPreview,
) {
    /** No worker: nothing can be changed from the screen, and commands are for adb. */
    val noWorker: Boolean get() = reset.block == ActionBlock.NO_WORKER
}

internal sealed interface ActionCenterState {
    data object Loading : ActionCenterState

    /** [board] is null while the Action Center is off: the phone is not asked anything then. */
    data class Ready(
        val enabled: Boolean,
        val board: DeviceBoard?,
        val log: List<LogRow>,
    ) : ActionCenterState {
        val active: List<LogRow> get() = log.filter { it.record.active }
    }
}

/**
 * The Action Center (SPEC 13). It reads the phone only while its screen shows and the Action
 * Center is on, and again when the log, the worker or the screen's return says something changed.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
public class ActionCenterViewModel
    @Inject
    constructor(
        private val center: ActionCenter,
        private val store: SettingsStore,
        private val access: PrivilegedAccess,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val tick = MutableStateFlow(0)
        internal val flow = ActionFlow(center, viewModelScope)

        internal val state: StateFlow<ActionCenterState> =
            combine(
                store.settings.map { it.actionCenter }.distinctUntilChanged(),
                center.records,
                access.state.map { it.worker }.distinctUntilChanged(),
                tick,
            ) { on, records, _, _ -> on to records }
                .mapLatest { (on, records) ->
                    // A row is written before its command and may go again a moment later (ADR-0060 §21).
                    delay(SETTLE_MS)
                    ActionCenterState.Ready(on, if (on) board() else null, records.map { LogRow(it, appLabel(it)) })
                }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ActionCenterState.Loading)

        private val failed = MutableStateFlow(false)

        /** True after the switch could not be stored, until it can. */
        internal val saveFailed: StateFlow<Boolean> = failed.asStateFlow()

        /** The screen is back in front: a value may have changed while it was away. */
        internal fun refresh() {
            tick.value++
        }

        /**
         * SPEC 13: turning the Action Center on also grants `WRITE_SECURE_SETTINGS`. Without a
         * worker the grant waits for Privileges, and the commands are there to copy.
         */
        internal fun enable() {
            viewModelScope.launch {
                if (write { store.actionCenter(true) }) access.bootstrap(includeWriteSecureSettings = true)
            }
        }

        /** Turns it off, after putting every active change back when [revertFirst]. The log stays either way. */
        internal fun disable(revertFirst: Boolean) {
            if (revertFirst) {
                flow.revertAll { write { store.actionCenter(false) } }
            } else {
                viewModelScope.launch { write { store.actionCenter(false) } }
            }
        }

        private suspend fun write(block: suspend () -> Unit): Boolean {
            val stored =
                try {
                    block()
                    true
                } catch (e: IOException) {
                    Log.w(TAG, "settings write failed", e)
                    false
                }
            failed.value = !stored
            return stored
        }

        private suspend fun board(): DeviceBoard {
            // The name of a taken-over value is the day it was taken over (ADR-0060 §19).
            val takeOver = ActionRequest.TakeOverDoze(LocalDate.now().toString())
            val board =
                center.board(
                    listOf(
                        ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED),
                        ActionRequest.ApplyDozeProfile(DozeProfile.AGGRESSIVE),
                        takeOver,
                        ActionRequest.BatterySaver(on = true),
                        ActionRequest.BatterySaver(on = false),
                        ActionRequest.ResetBatteryStats,
                    ),
                    withDoze = true,
                )
            val previews = board.previews
            val saverOn = previews[SAVER_ON]
            return DeviceBoard(
                doze = board.doze,
                balanced = previews[0],
                aggressive = previews[1],
                takeOver = previews[2],
                // The one that would change something; while charging neither can, and "on" says why.
                saver = if (saverOn.block == ActionBlock.ALREADY) previews[SAVER_ON + 1] else saverOn,
                reset = previews.last(),
            )
        }

        private fun appLabel(record: ActionRecord): String? {
            if (record.type !in APP_TYPES) return null
            val packages = context.packageManager
            return try {
                packages.getApplicationLabel(packages.getApplicationInfo(record.target, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                // Uninstalled since: the package name is what is left to say.
                record.target
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val SETTLE_MS = 120L
            const val TAG = "ActionCenter"

            /** Where the board's requests put "battery saver on"; "off" follows it. */
            const val SAVER_ON = 3
            val APP_TYPES =
                setOf(ActionType.FORCE_STOP, ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET, ActionType.DOZE_ALLOWLIST)
        }
    }

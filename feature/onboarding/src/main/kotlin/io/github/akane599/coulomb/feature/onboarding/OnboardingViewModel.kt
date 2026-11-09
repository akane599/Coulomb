// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.onboarding

import android.content.Intent
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.collect.CapabilityStore
import io.github.akane599.coulomb.core.datastore.SettingsStore
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Onboarding's steps (SPEC 14.1, ADR-0047). The step and path are saved state, so leaving the app
 * keeps them. Step 3 moves on by itself once Shizuku allows Coulomb and the bootstrap has run; the
 * result step runs the probe suite each time it opens.
 */
@HiltViewModel
public class OnboardingViewModel
    @Inject
    constructor(
        private val saved: SavedStateHandle,
        private val privileged: PrivilegedAccess,
        private val capabilityStore: CapabilityStore,
        private val settings: SettingsStore,
    ) : ViewModel() {
        private val step = saved.getStateFlow(KEY_STEP, OnboardingStep.WELCOME.name)
        private val path = saved.getStateFlow(KEY_PATH, OnboardingPath.SHIZUKU.name)
        private val probe = MutableStateFlow<ProbeRun>(ProbeRun.Idle)
        private var probeJob: Job? = null
        private var ownBootstrap = false

        private val stage =
            combine(privileged.state, privileged.lastBootstrap) { state, report ->
                shizukuStage(state, bootstrapDone = report != null || state.grants.durableCore)
            }.distinctUntilChanged()

        internal val state: StateFlow<OnboardingUiState> =
            combine(
                combine(step, path) { s, p -> OnboardingStep.valueOf(s) to OnboardingPath.valueOf(p) },
                privileged.state,
                privileged.lastBootstrap,
                stage,
                combine(probe, capabilityStore.capabilities) { run, stored -> run to stored },
            ) { (s, p), privilege, report, stage, (run, stored) ->
                OnboardingUiState(
                    step = s,
                    path = p,
                    privilege = privilege,
                    stage = stage,
                    adbCommands = report?.adbCommands?.takeIf { it.isNotEmpty() } ?: privileged.adbBootstrapCommands(),
                    result =
                        when (run) {
                            ProbeRun.Running, ProbeRun.Idle -> ResultState.Checking

                            // Stored results may predate this run's grants, so a failure never shows them.
                            ProbeRun.Failed -> ResultState.Failed

                            ProbeRun.Done -> ResultState.Ready(stored)
                        },
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                // The saved step from the first frame, so a restored process doesn't flash Welcome.
                OnboardingUiState(step = current(), path = OnboardingPath.valueOf(path.value)),
            )

        init {
            viewModelScope.launch {
                var last: ShizukuStage? = null
                stage.collect { now ->
                    // Only the moment it connects while step 3 is open, not a later visit to step 3.
                    val connectedNow = now == ShizukuStage.CONNECTED && last.let { it != null && it != ShizukuStage.CONNECTED }
                    if (connectedNow && current() == OnboardingStep.SHIZUKU) go(OnboardingStep.RESULT)
                    last = now
                }
            }
            viewModelScope.launch {
                // The automatic bootstrap runs only on the first authorization (SPEC 4.4); one that
                // was skipped (grants verified on an earlier install) runs here, once. This model
                // outlives the screen in the activity, so it stops once onboarding is done.
                combine(privileged.state, settings.settings) { state, stored ->
                    !stored.onboarded && state.permission == ManagerPermission.GRANTED && !state.grants.durableCore
                }.distinctUntilChanged()
                    .collect { missing ->
                        if (missing && privileged.lastBootstrap.value == null && !ownBootstrap) {
                            ownBootstrap = true
                            quietly("bootstrap") { privileged.bootstrap() }
                        }
                    }
            }
            if (current() == OnboardingStep.RESULT) startProbe()
        }

        internal fun choose(choice: OnboardingPath) {
            saved[KEY_PATH] = choice.name
            go(OnboardingStep.PERMISSIONS)
        }

        internal fun next() {
            next(current(), OnboardingPath.valueOf(path.value))?.let(::go)
        }

        /** False at the first step, where back leaves the app. */
        internal fun back(): Boolean {
            val before = previous(current(), OnboardingPath.valueOf(path.value)) ?: return false
            go(before)
            return true
        }

        internal fun allowShizuku() = privileged.requestManagerPermission()

        internal fun managerIntent(): Intent? = privileged.managerLaunchIntent()

        /** On resume: grants may have changed in Settings or over ADB. */
        internal fun refresh() {
            viewModelScope.launch {
                quietly("grant check") { privileged.refreshGrants() }
            }
        }

        /** "Try again" after a failed check. */
        internal fun retryProbe() = startProbe()

        /** "Show my battery": the shell replaces onboarding once the flag is stored (ADR-0047 §1). */
        internal fun finish() {
            viewModelScope.launch { settings.onboarded() }
        }

        private fun current() = OnboardingStep.valueOf(step.value)

        private fun go(to: OnboardingStep) {
            saved[KEY_STEP] = to.name
            if (to == OnboardingStep.RESULT) startProbe()
        }

        private fun startProbe() {
            if (probeJob?.isActive == true) return
            probe.value = ProbeRun.Running
            probeJob =
                viewModelScope.launch {
                    probe.value =
                        try {
                            capabilityStore.probe()
                            ProbeRun.Done
                        } catch (e: CancellationException) {
                            throw e
                        } catch (
                            @Suppress("TooGenericExceptionCaught") e: Exception,
                        ) {
                            // A probe, Room or the platform failed; the stored matrix stays as it was.
                            Log.w(TAG, "probe failed", e)
                            ProbeRun.Failed
                        }
                }
        }

        /** Logs an unexpected failure of a privileged call; the screen keeps the state it has. */
        private suspend fun quietly(
            what: String,
            block: suspend () -> Unit,
        ) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "$what failed", e)
            }
        }

        private enum class ProbeRun { Idle, Running, Done, Failed }

        private companion object {
            const val KEY_STEP = "step"
            const val KEY_PATH = "path"
            const val STOP_TIMEOUT_MS = 5_000L
            const val TAG = "Onboarding"
        }
    }

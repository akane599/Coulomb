// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akane599.coulomb.core.collect.CapabilityStore
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.privileged.capability.CapabilityReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

/**
 * The Privileges screen (SPEC 14.3, ADR-0047 §7): the manager, the grants and their repair, and the
 * Capability Matrix with "Re-run probes" and the exported report.
 */
@HiltViewModel
public class PrivilegesViewModel
    @Inject
    constructor(
        private val privileged: PrivilegedAccess,
        private val capabilityStore: CapabilityStore,
        @ApplicationContext context: Context,
    ) : ViewModel() {
        private val local = MutableStateFlow(Local())

        /** The application's resolver: an export outlives the activity that asked for it. */
        private val resolver = context.contentResolver

        internal val state: StateFlow<PrivilegesUiState> =
            combine(
                privileged.state,
                privileged.lastBootstrap,
                capabilityStore.capabilities,
                capabilityStore.probing,
                local,
            ) { privilege, report, capabilities, probing, local ->
                PrivilegesUiState(
                    privilege = privilege,
                    capabilities = capabilities,
                    adbCommands = report?.adbCommands?.takeIf { it.isNotEmpty() } ?: privileged.adbBootstrapCommands(),
                    probing = probing,
                    probeFailed = local.probeFailed,
                    granting = local.granting,
                    grantFailed = local.grantFailed,
                    export = local.export,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PrivilegesUiState())

        internal fun allowShizuku() = privileged.requestManagerPermission()

        internal fun managerIntent(): Intent? = privileged.managerLaunchIntent()

        /** On resume: grants may have changed in Settings or over ADB. */
        internal fun refresh() {
            viewModelScope.launch { quietly("grant check") { privileged.refreshGrants() } }
        }

        /** "Grant through Shizuku" and "Re-enable": the SPEC 4.4 bootstrap, then a fresh matrix. */
        internal fun grant() {
            if (local.value.granting) return
            local.update { it.copy(granting = true, grantFailed = false) }
            viewModelScope.launch {
                var granted = false
                try {
                    // A grant that did not go through counts as a failure, as a call that threw does.
                    quietly("bootstrap") { granted = privileged.bootstrap().allGranted }
                } finally {
                    local.update { it.copy(granting = false, grantFailed = !granted) }
                }
                // Not rerun(): a run already going started before the grant, so this one queues
                // behind it on the store's lock.
                probe()
            }
        }

        /** "Re-run probes" (SPEC 4.6). A failed run keeps the stored matrix and says so. */
        internal fun rerun() {
            if (capabilityStore.probing.value) return
            viewModelScope.launch { probe() }
        }

        private suspend fun probe() {
            val failed = !quietly("probe") { capabilityStore.probe() }
            local.update { it.copy(probeFailed = failed) }
        }

        /**
         * "Export capability report": writes the Markdown report ([CapabilityReport]) to the file the
         * system's save dialog returned, through the application's resolver so no activity is held.
         */
        internal fun export(uri: Uri) {
            viewModelScope.launch {
                val saved =
                    quietly("export") {
                        val report =
                            CapabilityReport.markdown(
                                capabilityStore.capabilities.first(),
                                privileged.state.value,
                                privileged.diagnostics.value,
                                "${Build.MANUFACTURER} ${Build.MODEL}",
                                Instant.now().toString(),
                            )
                        withContext(
                            Dispatchers.IO,
                        ) { checkNotNull(resolver.openOutputStream(uri)) { "no stream" }.use { it.write(report.toByteArray()) } }
                    }
                local.update { it.copy(export = if (saved) ExportResult.SAVED else ExportResult.FAILED) }
            }
        }

        /** A new export starts without the last one's outcome. */
        internal fun clearExport() {
            local.update { it.copy(export = null) }
        }

        /** Runs [block]; false when it failed, which is logged. The screen keeps the state it has. */
        private suspend fun quietly(
            what: String,
            block: suspend () -> Unit,
        ): Boolean =
            try {
                block()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "$what failed", e)
                false
            }

        private data class Local(
            val probeFailed: Boolean = false,
            val granting: Boolean = false,
            val grantFailed: Boolean = false,
            val export: ExportResult? = null,
        )

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val TAG = "Privileges"
        }
    }

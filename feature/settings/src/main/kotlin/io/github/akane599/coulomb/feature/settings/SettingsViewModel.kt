// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.datastore.SettingsStore
import io.github.akane599.coulomb.core.datastore.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** The settings built so far (SPEC 14.4, ADR-0047 §8). */
@HiltViewModel
public class SettingsViewModel
    @Inject
    constructor(
        private val store: SettingsStore,
    ) : ViewModel() {
        /** Null until the stored values are read, so no switch shows a default and then flips. */
        internal val settings: StateFlow<UserSettings?> =
            store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        private val failed = MutableStateFlow(false)

        /** True after a write failed, until the next one goes through. */
        internal val saveFailed: StateFlow<Boolean> = failed.asStateFlow()

        internal fun showSystemApps(show: Boolean) = write { store.showSystemApps(show) }

        internal fun scaleToMeasured(scale: Boolean) = write { store.scaleToMeasured(scale) }

        internal fun componentBreakdown(on: Boolean) = write { store.componentBreakdown(on) }

        internal fun chargeTarget(pct: Int) = write { store.chargeTarget(pct) }

        /** A write that fails (a full disk) is logged and said; the switch shows the stored value again. */
        private fun write(block: suspend () -> Unit) {
            viewModelScope.launch {
                failed.value =
                    try {
                        block()
                        false
                    } catch (e: IOException) {
                        Log.w(TAG, "settings write failed", e)
                        true
                    }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val TAG = "Settings"
        }
    }

/** What the Settings screen's controls change. */
@Immutable
internal class SettingsActions(
    val showSystemApps: (Boolean) -> Unit,
    val scaleToMeasured: (Boolean) -> Unit,
    val componentBreakdown: (Boolean) -> Unit,
    val chargeTarget: (Int) -> Unit,
)

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.charging

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.data.ChargingRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Whether the latest sample says plugged in, for the shell to show Charging in place of Now (ADR-0046 §1). */
@HiltViewModel
public class PluggedViewModel
    @Inject
    constructor(
        charging: ChargingRepository,
    ) : ViewModel() {
        internal val plugged: StateFlow<Boolean?> =
            charging.plugged.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/** True while plugged in, false while not, null before the first sample is read. */
@Composable
public fun rememberPlugged(viewModel: PluggedViewModel = hiltViewModel()): Boolean? {
    val plugged = viewModel.plugged.collectAsStateWithLifecycle()
    return plugged.value
}

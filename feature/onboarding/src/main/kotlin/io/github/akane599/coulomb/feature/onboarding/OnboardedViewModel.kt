// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.datastore.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Whether onboarding is done (ADR-0047 §1), for the activity to show onboarding or the shell. */
@HiltViewModel
public class OnboardedViewModel
    @Inject
    constructor(
        settings: SettingsStore,
    ) : ViewModel() {
        internal val onboarded: StateFlow<Boolean?> =
            settings.settings
                .map { it.onboarded }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

/** True once onboarding is done, false before, null until the settings are read. */
@Composable
public fun rememberOnboarded(viewModel: OnboardedViewModel = hiltViewModel()): Boolean? {
    val onboarded = viewModel.onboarded.collectAsStateWithLifecycle()
    return onboarded.value
}

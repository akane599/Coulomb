// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.data.ChosenWindow
import io.github.akane599.coulomb.core.data.WakeSourceDetail
import io.github.akane599.coulomb.core.data.WakeSourceRepository
import io.github.akane599.coulomb.core.model.WakeKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The wakeup source detail in the states of SPEC 10.11. */
internal sealed interface WakeSourceState {
    data object Loading : WakeSourceState

    data object Error : WakeSourceState

    /** Null when Drain's window has no report to read the record from. */
    data class Ready(
        val detail: WakeSourceDetail?,
    ) : WakeSourceState
}

/**
 * One kernel record or wakeup reason over Drain's window (SPEC 10.5). It reads only while its
 * screen shows, and again when the window or its report changes.
 */
@HiltViewModel(assistedFactory = WakeSourceViewModel.Factory::class)
@OptIn(ExperimentalCoroutinesApi::class)
public class WakeSourceViewModel
    @AssistedInject
    constructor(
        @Assisted public val kind: WakeKind,
        @Assisted public val tag: String,
        sources: WakeSourceRepository,
        window: ChosenWindow,
    ) : ViewModel() {
        private val attempt = MutableStateFlow(0)

        internal val state: StateFlow<WakeSourceState> =
            combine(attempt, window.span) { _, span -> span }
                .flatMapLatest { span ->
                    sources
                        .detail(kind, tag, span)
                        .map<WakeSourceDetail?, WakeSourceState>(WakeSourceState::Ready)
                        .catch { emit(WakeSourceState.Error) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WakeSourceState.Loading)

        /** After [WakeSourceState.Error]: reads again. */
        internal fun retry() {
            attempt.value++
        }

        @AssistedFactory
        public interface Factory {
            public fun create(
                kind: WakeKind,
                tag: String,
            ): WakeSourceViewModel
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

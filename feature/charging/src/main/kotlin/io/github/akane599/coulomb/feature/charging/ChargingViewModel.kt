// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.charging

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.collect.Collector
import io.github.akane599.coulomb.core.collect.engine.VisibleScreen
import io.github.akane599.coulomb.core.data.ChargingNow
import io.github.akane599.coulomb.core.data.ChargingRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What Charging shows (SPEC 10.9), in the states of SPEC 10.11. */
internal sealed interface ChargingUiState {
    /** Nothing read yet: the still skeleton. */
    data object Loading : ChargingUiState

    /** The records could not be read. */
    data object Error : ChargingUiState

    /** Unplugged since the screen opened; the shell shows Now again on its next frame. */
    data object Unplugged : ChargingUiState

    @Immutable
    data class Ready(
        val now: ChargingNow,
    ) : ChargingUiState
}

/**
 * Charging over [ChargingRepository], which reads again at each sample. While the screen shows,
 * the collector samples every second (SPEC 7.3: 1 s on the Charging screen); nothing here polls.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
public class ChargingViewModel
    @Inject
    constructor(
        private val charging: ChargingRepository,
        private val collector: Collector,
    ) : ViewModel() {
        private val attempt = MutableStateFlow(0)

        internal val state: StateFlow<ChargingUiState> =
            attempt
                .flatMapLatest {
                    charging.now
                        .map { now -> now?.let(ChargingUiState::Ready) ?: ChargingUiState.Unplugged }
                        .catch { emit(ChargingUiState.Error) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChargingUiState.Loading)

        /** After [ChargingUiState.Error]: reads everything again. */
        internal fun retry() {
            attempt.value++
        }

        /** The 80/85/90/100% group (ADR-0026: it sets the target tick). */
        internal fun setTarget(pct: Int) {
            viewModelScope.launch { charging.setTarget(pct) }
        }

        /**
         * True while the screen is resumed: the collector samples at 1 Hz while plugged with the
         * screen on (SPEC 7.3); false goes back to the rate of any Coulomb screen.
         */
        internal fun visible(shown: Boolean) {
            collector.visibleScreen = if (shown) VisibleScreen.CHARGING else VisibleScreen.COULOMB
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

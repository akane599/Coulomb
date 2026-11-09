// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.data.ChargeSessionDetail
import io.github.akane599.coulomb.core.data.ChargeSessionRow
import io.github.akane599.coulomb.core.data.HealthRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** A screen's read in the states of SPEC 10.11. */
internal sealed interface Read<out T> {
    data object Loading : Read<Nothing>

    data object Error : Read<Nothing>

    @Immutable
    data class Ready<T>(
        val value: T,
    ) : Read<T>
}

private const val STOP_TIMEOUT_MS = 5_000L

/** Every closed charge session, newest first ("See all", SPEC 10.8 item 6). */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
public class ChargeSessionsViewModel
    @Inject
    constructor(
        health: HealthRepository,
    ) : ViewModel() {
        private val attempt = MutableStateFlow(0)

        internal val state: StateFlow<Read<List<ChargeSessionRow>>> =
            attempt
                .flatMapLatest {
                    health.allSessions
                        .map<List<ChargeSessionRow>, Read<List<ChargeSessionRow>>> { Read.Ready(it) }
                        .catch { emit(Read.Error) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Read.Loading)

        internal fun retry() {
            attempt.value++
        }
    }

/** One charge session with its curve (SPEC 10.8 item 6); null in [Read.Ready] when it is gone. */
@HiltViewModel(assistedFactory = ChargeSessionViewModel.Factory::class)
@OptIn(ExperimentalCoroutinesApi::class)
public class ChargeSessionViewModel
    @AssistedInject
    constructor(
        @Assisted public val sessionId: Long,
        health: HealthRepository,
    ) : ViewModel() {
        private val attempt = MutableStateFlow(0)

        internal val state: StateFlow<Read<ChargeSessionDetail?>> =
            attempt
                .flatMapLatest {
                    health
                        .session(sessionId)
                        .map<ChargeSessionDetail?, Read<ChargeSessionDetail?>> { Read.Ready(it) }
                        .catch { emit(Read.Error) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Read.Loading)

        internal fun retry() {
            attempt.value++
        }

        /** Creates the screen's model for one session (the app passes the id, not a route). */
        @AssistedFactory
        public interface Factory {
            public fun create(sessionId: Long): ChargeSessionViewModel
        }
    }

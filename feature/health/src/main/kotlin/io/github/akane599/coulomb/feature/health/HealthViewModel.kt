// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.collect.HealthFacts
import io.github.akane599.coulomb.core.data.HealthReport
import io.github.akane599.coulomb.core.data.HealthRepository
import io.github.akane599.coulomb.core.model.BatteryDates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** What Health shows (SPEC 10.8), in the states of SPEC 10.11. */
internal sealed interface HealthUiState {
    /** Nothing read yet: the still skeleton. */
    data object Loading : HealthUiState

    /** The records could not be read. */
    data object Error : HealthUiState

    @Immutable
    data class Ready(
        val report: HealthReport,
        /** The battery's dates, read live (ADR-0044 §4); [BatteryDates.NONE] until read or where none is reported. */
        val dates: BatteryDates,
    ) : HealthUiState
}

/**
 * Health over the stored records ([HealthRepository]), which change after each session closes and
 * each capacity window; the dates are read once per opening (S3's schedule, ADR-0044 §4). Nothing
 * here polls.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
public class HealthViewModel
    @Inject
    constructor(
        private val health: HealthRepository,
        facts: HealthFacts,
    ) : ViewModel() {
        private val attempt = MutableStateFlow(0)

        // A read that fails leaves the dates out; the rest of the screen does not depend on them.
        private val dates: StateFlow<BatteryDates> =
            flow {
                emit(
                    try {
                        facts.dates(LocalDate.now())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
                    ) {
                        BatteryDates.NONE
                    },
                )
            }.stateIn(viewModelScope, SharingStarted.Lazily, BatteryDates.NONE)

        internal val state: StateFlow<HealthUiState> =
            attempt
                .flatMapLatest {
                    combine(health.report, dates) { report, read -> HealthUiState.Ready(report, read) as HealthUiState }
                        .catch { emit(HealthUiState.Error) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HealthUiState.Loading)

        /** After [HealthUiState.Error]: reads everything again. */
        internal fun retry() {
            attempt.value++
        }

        /** Names a charger; a blank [name] makes it unnamed again (ADR-0044 §6). */
        internal fun rename(
            chargerId: Long,
            name: String,
        ) {
            viewModelScope.launch { health.renameCharger(chargerId, name) }
        }

        /** The owner's design capacity (ADR-0044 §5); the report follows from the database. */
        internal fun setDesignCapacity(mah: Int) {
            viewModelScope.launch { health.setDesignCapacity(mah) }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.charging

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.collect.Collector
import io.github.akane599.coulomb.core.collect.LiveReads
import io.github.akane599.coulomb.core.collect.engine.VisibleScreen
import io.github.akane599.coulomb.core.data.ChargingNow
import io.github.akane599.coulomb.core.data.ChargingRepository
import io.github.akane599.coulomb.core.insights.InsightStore
import io.github.akane599.coulomb.core.insights.ShownInsight
import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.dumpsys.ThermalDump
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

/** The insights Charging shows, and how many more are open. */
internal data class ChargingInsights(
    val shown: List<ShownInsight> = emptyList(),
    val more: Int = 0,
)

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
        private val insights: InsightStore,
        private val live: LiveReads,
    ) : ViewModel() {
        /** S19 on demand (ADR-0068): the thermal service's sensors now. */
        internal suspend fun readThermal(): ThermalDump? = live.thermal()

        private val attempt = MutableStateFlow(0)

        internal val state: StateFlow<ChargingUiState> =
            attempt
                .flatMapLatest {
                    charging.now
                        .map { now -> now?.let(ChargingUiState::Ready) ?: ChargingUiState.Unplugged }
                        .catch { emit(ChargingUiState.Error) }
                    // Back to Loading once stopped, so a later charge never opens on this one's figures.
                }.stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0),
                    ChargingUiState.Loading,
                )

        /** The alarm at the target as Settings has it; false until read, and when the read fails. */
        internal val targetAlarm: StateFlow<Boolean> =
            charging.targetAlarm
                .catch { emit(false) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        private val refreshing = MutableStateFlow(false)

        /** True from a pull until the next reading arrives, or [REFRESH_WAIT_MS] at most. */
        internal val isRefreshing: StateFlow<Boolean> = refreshing.asStateFlow()

        internal fun refresh() {
            if (refreshing.value) return
            refreshing.value = true
            collector.post(SnapshotTrigger.PULL_TO_REFRESH)
            viewModelScope.launch {
                withTimeoutOrNull(REFRESH_WAIT_MS) { state.drop(1).first() }
                refreshing.value = false
            }
        }

        /** Lite mode; false until read. */
        internal val lite: StateFlow<Boolean> =
            charging.lite
                .catch { emit(false) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        private val allInsights = MutableStateFlow(false)

        /**
         * The open insights as Now shows them (SPEC 10.2): two, highest severity first, and how
         * many more are open. Charging stands in Now's place while plugged in, so without them an
         * insight the digest named could not be read until the phone is unplugged. The flow runs
         * only while Charging is on screen, so what passes through it has been shown.
         */
        internal val shownInsights: StateFlow<ChargingInsights> =
            combine(insights.observeShown(Int.MAX_VALUE), allInsights) { open, all ->
                val shown = if (all) open else open.take(MAX_INSIGHTS)
                ChargingInsights(shown, open.size - shown.size)
            }.onEach { (shown, _) ->
                val now = System.currentTimeMillis()
                shown.forEach { insights.seen(it.insight, now) }
            }.catch { emit(ChargingInsights()) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChargingInsights())

        /** "Show N more": every open insight, until Charging is left. */
        internal fun showAllInsights() {
            allInsights.value = true
        }

        /** Snoozes the insight for [forMs] ([InsightStore.SNOOZE_DAY_MS], [InsightStore.SNOOZE_WEEK_MS]). */
        internal fun snoozeInsight(
            id: Long,
            forMs: Long,
        ) {
            viewModelScope.launch { insights.snooze(id, System.currentTimeMillis(), forMs) }
        }

        internal fun dismissInsight(id: Long) {
            viewModelScope.launch { insights.dismiss(id, System.currentTimeMillis()) }
        }

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
            const val MAX_INSIGHTS = 2
            const val REFRESH_WAIT_MS = 5_000L
        }
    }

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.now

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.collect.Collector
import io.github.akane599.coulomb.core.data.AppIcons
import io.github.akane599.coulomb.core.data.BatteryRepository
import io.github.akane599.coulomb.core.data.ChosenWindow
import io.github.akane599.coulomb.core.data.DrainRepository
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.insights.InsightStore
import io.github.akane599.coulomb.core.insights.ShownInsight
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Now (SPEC 10.2) from the shared read models (ADR-0032). Nothing here polls: each new sample or
 * stored window updates the state.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
public class NowViewModel
    @Inject
    @Suppress("LongParameterList") // One source for each thing Now shows; Hilt supplies them.
    constructor(
        battery: BatteryRepository,
        drains: DrainRepository,
        access: PrivilegedAccess,
        private val icons: AppIcons,
        private val collector: Collector,
        private val window: ChosenWindow,
        private val insights: InsightStore,
    ) : ViewModel() {
        private val attempt = MutableStateFlow(0)
        private val refreshing = MutableStateFlow(false)

        /** True from a pull until the window it asked for arrives, or [REFRESH_WAIT_MS] at most. */
        internal val isRefreshing: StateFlow<Boolean> = refreshing.asStateFlow()

        private val allInsights = MutableStateFlow(false)

        private val windows = drains.sinceUnplugged

        internal val state: StateFlow<NowUiState> =
            attempt
                .flatMapLatest {
                    combine(battery.reading, windows) { reading, window ->
                        reading?.let { r ->
                            NowUiState.Ready(
                                r.battery,
                                r.discharge.takeUnless { r.battery.plugged },
                                r.levels,
                                window,
                                r.live,
                                issue = null,
                            )
                        }
                    }.combine(access.state.map { it.issue }.distinctUntilChanged()) { ready, issue ->
                        ready?.copy(issue = issue)
                    }.combine(shownInsights()) { ready, (shown, more) ->
                        ready?.copy(insights = shown, moreInsights = more) ?: NowUiState.Loading
                    }.catch { emit(NowUiState.Error) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NowUiState.Loading)

        /** After [NowUiState.Error]: reads everything again. */
        internal fun retry() {
            attempt.value++
        }

        /** Pull to refresh: a heavy snapshot, which the collector debounces to one a minute (SPEC 7.2). */
        internal fun refresh() {
            if (refreshing.value) return
            refreshing.value = true
            collector.post(SnapshotTrigger.PULL_TO_REFRESH)
            viewModelScope.launch {
                withTimeoutOrNull(REFRESH_WAIT_MS) { windows.drop(1).first() }
                refreshing.value = false
            }
        }

        /**
         * The insights Now shows. This flow runs only while Now is on screen, so what passes through
         * it has been shown: a new insight becomes seen (SPEC 9.4).
         */
        private fun shownInsights(): Flow<Pair<List<ShownInsight>, Int>> =
            combine(insights.observeShown(Int.MAX_VALUE), allInsights) { open, all ->
                val shown = if (all) open else open.take(NowUiState.MAX_INSIGHTS)
                shown to open.size - shown.size
            }.onEach { (shown, _) ->
                val now = System.currentTimeMillis()
                shown.forEach { insights.seen(it.insight, now) }
            }

        /** "Show N more": every open insight, until Now is left. A digest names up to five; Now starts with two. */
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

        /** "See all": Drain opens on the window Now shows. */
        internal fun seeAllDrains() = window.choose(DrainSpan.SinceUnplugged)

        /** The app's icon at [sizePx], from the shared cache. */
        internal suspend fun icon(
            packageName: String,
            sizePx: Int,
        ): ImageBitmap? = icons.icon(packageName, sizePx)?.asImageBitmap()

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val REFRESH_WAIT_MS = 5_000L
        }
    }

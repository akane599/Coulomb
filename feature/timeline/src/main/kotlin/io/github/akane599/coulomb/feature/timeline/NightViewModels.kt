// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.data.ChosenWindow
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.DrainRepository
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.data.NightRepository
import io.github.akane599.coulomb.core.data.NightSummary
import io.github.akane599.coulomb.core.data.Timeline
import io.github.akane599.coulomb.core.data.TimelineRepository
import io.github.akane599.coulomb.core.insights.InsightStore
import io.github.akane599.coulomb.core.model.insight.Insight
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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

/** The Night Report's list (SPEC 10.7): screen-off stretches of an hour or more, newest first. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
public class NightReportViewModel
    @Inject
    constructor(
        nights: NightRepository,
    ) : ViewModel() {
        private val attempt = MutableStateFlow(0)

        internal val state: StateFlow<Read<List<NightSummary>>> =
            attempt
                .flatMapLatest {
                    nights.nights
                        .map<List<NightSummary>, Read<List<NightSummary>>> { Read.Ready(it) }
                        .catch { emit(Read.Error) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Read.Loading)

        internal fun retry() {
            attempt.value++
        }
    }

/**
 * One night with what the detail page shows: who used the battery ([report], null when its
 * snapshots are gone), its [timeline] (null when the session's history cannot be read) and the
 * open insights its story may end on.
 */
@Immutable
internal data class NightDetail(
    val night: NightSummary,
    val report: DrainReport?,
    val timeline: Timeline?,
    val insights: List<Insight> = emptyList(),
)

/** One night (SPEC 10.7); null in [Read.Ready] when the session is gone. */
@HiltViewModel(assistedFactory = NightViewModel.Factory::class)
@OptIn(ExperimentalCoroutinesApi::class)
public class NightViewModel
    @AssistedInject
    constructor(
        @Assisted public val sessionId: Long,
        nights: NightRepository,
        drains: DrainRepository,
        timelines: TimelineRepository,
        insights: InsightStore,
        private val window: ChosenWindow,
    ) : ViewModel() {
        private val attempt = MutableStateFlow(0)

        internal val state: StateFlow<Read<NightDetail?>> =
            attempt
                .flatMapLatest {
                    // A closed session's history does not change: one read.
                    val timeline = flow { emit(timelines.night(sessionId)) }
                    combine(nights.night(sessionId), drains.report(DrainSpan.Night(sessionId)), timeline, insights.observeOpen()) {
                        night,
                        report,
                        read,
                        open,
                        ->
                        night?.let { NightDetail(it, report, read, open) }
                    }.map<NightDetail?, Read<NightDetail?>> { Read.Ready(it) }
                        .catch { emit(Read.Error) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Read.Loading)

        internal fun retry() {
            attempt.value++
        }

        /** Drain opens on this night: its apps, components and every wake record. */
        internal fun showInDrain() = window.choose(DrainSpan.Night(sessionId))

        /** Creates the screen's model for one night (the app passes the id, not a route). */
        @AssistedFactory
        public interface Factory {
            public fun create(sessionId: Long): NightViewModel
        }
    }

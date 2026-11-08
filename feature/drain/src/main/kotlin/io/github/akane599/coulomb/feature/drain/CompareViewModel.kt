// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.common.ZoneSource
import io.github.akane599.coulomb.core.data.AppIcons
import io.github.akane599.coulomb.core.data.DrainRepository
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.datastore.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Compare two periods (ADR-0038): the two chosen ranges, and their comparison once both are read. */
@Immutable
internal data class CompareUiState(
    val first: DrainSpan.Days,
    val second: DrainSpan.Days,
    /** The last day a period may end on, for the pickers. */
    val today: LocalDate,
    /** Null while the periods are read, and after an error. */
    val comparison: Comparison? = null,
    val includeSystem: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Compare two periods (ADR-0038). The periods open as yesterday and today and survive process
 * death. Each report is read again after every heavy snapshot, as on Drain; nothing here polls.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
public class CompareViewModel
    @Inject
    constructor(
        drains: DrainRepository,
        private val icons: AppIcons,
        private val settings: SettingsStore,
        private val saved: SavedStateHandle,
        zone: ZoneSource,
    ) : ViewModel() {
        private val today = LocalDate.now(zone.zone())
        private val attempt = MutableStateFlow(0)
        private val periods =
            MutableStateFlow(
                restore(FIRST) ?: today.minusDays(1).let { DrainSpan.Days(it, it) },
            ).let { first -> first to MutableStateFlow(restore(SECOND) ?: DrainSpan.Days(today, today)) }

        internal val state: StateFlow<CompareUiState> =
            combine(attempt, periods.first, periods.second) { _, first, second -> first to second }
                .flatMapLatest { (first, second) ->
                    val loading = CompareUiState(first, second, today)
                    combine(drains.report(first), drains.report(second), settings.settings) { a, b, prefs ->
                        loading.copy(
                            comparison = compare(Period(first, a), Period(second, b), prefs.showSystemApps),
                            includeSystem = prefs.showSystemApps,
                        )
                    }.onStart { emit(loading) }
                        .catch { emit(loading.copy(failed = true)) }
                }.stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    CompareUiState(periods.first.value, periods.second.value, today),
                )

        internal fun chooseFirst(days: DrainSpan.Days) = choose(FIRST, days, periods.first)

        internal fun chooseSecond(days: DrainSpan.Days) = choose(SECOND, days, periods.second)

        internal fun includeSystem(include: Boolean) {
            viewModelScope.launch { settings.showSystemApps(include) }
        }

        /** After an error: reads both periods again. */
        internal fun retry() {
            attempt.value++
        }

        /** The app's icon at [sizePx], from the shared cache. */
        internal suspend fun icon(
            packageName: String,
            sizePx: Int,
        ): ImageBitmap? = icons.icon(packageName, sizePx)?.asImageBitmap()

        private fun choose(
            key: String,
            days: DrainSpan.Days,
            into: MutableStateFlow<DrainSpan.Days>,
        ) {
            saved[key] = longArrayOf(days.first.toEpochDay(), days.last.toEpochDay())
            into.value = days
        }

        private fun restore(key: String): DrainSpan.Days? {
            val (first, last) = saved.get<LongArray>(key)?.takeIf { it.size == 2 }?.map(LocalDate::ofEpochDay) ?: return null
            return if (last.isBefore(first)) null else DrainSpan.Days(first, last)
        }

        private companion object {
            const val FIRST = "compare_first"
            const val SECOND = "compare_second"
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

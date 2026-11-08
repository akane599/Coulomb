// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.collect.Collector
import io.github.akane599.coulomb.core.data.AppIcons
import io.github.akane599.coulomb.core.data.ChosenWindow
import io.github.akane599.coulomb.core.data.DrainRepository
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.datastore.SettingsStore
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Drain (SPEC 10.3) for the window chosen across screens ([ChosenWindow]). A report is read again
 * after every heavy snapshot (ADR-0036); nothing here polls. The two switches are SPEC 14.4
 * settings, kept in [SettingsStore]. A privilege issue shows as Now's does (SPEC 10.11 partial).
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
public class DrainViewModel
    @Inject
    constructor(
        drains: DrainRepository,
        private val window: ChosenWindow,
        private val icons: AppIcons,
        private val settings: SettingsStore,
        access: PrivilegedAccess,
        private val collector: Collector,
    ) : ViewModel() {
        private val attempt = MutableStateFlow(0)
        private val refreshing = MutableStateFlow(false)

        /** True from a pull until a new report arrives, or [REFRESH_WAIT_MS] at most. */
        internal val isRefreshing: StateFlow<Boolean> = refreshing.asStateFlow()

        internal val state: StateFlow<DrainUiState> =
            attempt
                .flatMapLatest {
                    val reports = window.span.flatMapLatest { span -> drains.report(span).map { span to it } }
                    val issues = access.state.map { it.issue }.distinctUntilChanged()
                    combine(window.span, reports, settings.settings, issues) { chosen, (span, report), prefs, issue ->
                        DrainUiState.Ready(
                            chosen = chosen,
                            report = report,
                            current = span == chosen,
                            includeSystem = prefs.showSystemApps,
                            scaled = prefs.scaleToMeasured,
                            issue = issue,
                        ) as DrainUiState
                    }.catch { emit(DrainUiState.Error) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DrainUiState.Loading)

        internal fun choose(span: DrainSpan) = window.choose(span)

        internal fun includeSystem(include: Boolean) {
            viewModelScope.launch { settings.showSystemApps(include) }
        }

        internal fun scale(scaled: Boolean) {
            viewModelScope.launch { settings.scaleToMeasured(scaled) }
        }

        /**
         * Pull to refresh: a heavy snapshot, which the collector debounces to one a minute (SPEC 7.2).
         * The report is read again when its window closes, as after any heavy snapshot.
         */
        internal fun refresh() {
            if (refreshing.value) return
            refreshing.value = true
            collector.post(SnapshotTrigger.PULL_TO_REFRESH)
            viewModelScope.launch {
                withTimeoutOrNull(REFRESH_WAIT_MS) { state.drop(1).first() }
                refreshing.value = false
            }
        }

        /** After [DrainUiState.Error]: reads everything again. */
        internal fun retry() {
            attempt.value++
        }

        /** The app's icon at [sizePx], from the shared cache. */
        internal suspend fun icon(
            packageName: String,
            sizePx: Int,
        ): ImageBitmap? = icons.icon(packageName, sizePx)?.asImageBitmap()

        /** The app's icon if the cache already holds it, so a row scrolled back shows it at once. */
        internal fun peekIcon(
            packageName: String,
            sizePx: Int,
        ): ImageBitmap? = icons.peek(packageName, sizePx)?.asImageBitmap()

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val REFRESH_WAIT_MS = 5_000L
        }
    }

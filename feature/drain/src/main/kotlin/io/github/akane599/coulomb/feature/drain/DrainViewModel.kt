// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.data.AppIcons
import io.github.akane599.coulomb.core.data.ChosenWindow
import io.github.akane599.coulomb.core.data.DrainRepository
import io.github.akane599.coulomb.core.data.DrainSpan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Drain (SPEC 10.3) for the window chosen across screens ([ChosenWindow]). A report is read again
 * after every heavy snapshot (ADR-0036); nothing here polls.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
public class DrainViewModel
    @Inject
    constructor(
        drains: DrainRepository,
        private val window: ChosenWindow,
        private val icons: AppIcons,
        private val saved: SavedStateHandle,
    ) : ViewModel() {
        private val attempt = MutableStateFlow(0)
        private val includeSystem = saved.getStateFlow(KEY_SYSTEM, true)
        private val scaled = saved.getStateFlow(KEY_SCALED, false)

        internal val state: StateFlow<DrainUiState> =
            attempt
                .flatMapLatest {
                    val reports = window.span.flatMapLatest { span -> drains.report(span).map { span to it } }
                    combine(window.span, reports, includeSystem, scaled) { chosen, (span, report), system, scale ->
                        DrainUiState.Ready(chosen, report, current = span == chosen, includeSystem = system, scaled = scale)
                            as DrainUiState
                    }.catch { emit(DrainUiState.Error) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DrainUiState.Loading)

        internal fun choose(span: DrainSpan) = window.choose(span)

        internal fun includeSystem(include: Boolean) {
            saved[KEY_SYSTEM] = include
        }

        internal fun scale(scaled: Boolean) {
            saved[KEY_SCALED] = scaled
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

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val KEY_SYSTEM = "includeSystem"
            const val KEY_SCALED = "scaled"
        }
    }

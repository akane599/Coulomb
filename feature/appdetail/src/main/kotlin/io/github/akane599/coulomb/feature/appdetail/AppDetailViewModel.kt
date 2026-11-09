// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.data.AppDetailReport
import io.github.akane599.coulomb.core.data.AppIcons
import io.github.akane599.coulomb.core.data.ChosenWindow
import io.github.akane599.coulomb.core.data.DrainRepository
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.datastore.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What App detail shows (SPEC 10.4), in the states of SPEC 10.11. */
internal sealed interface AppDetailUiState {
    /** Nothing read yet: the still skeleton. */
    data object Loading : AppDetailUiState

    /** The records could not be read. */
    data object Error : AppDetailUiState

    @Immutable
    data class Ready(
        val span: DrainSpan,
        /** Null: the window has no two ends yet. */
        val detail: AppDetailReport?,
        /** Drain's "scale to measured" setting, so the figure is the one on the Drain row (ADR-0041 §1). */
        val scaled: Boolean,
    ) : AppDetailUiState
}

/**
 * App detail for one UID over the window chosen across screens ([ChosenWindow], SPEC 10.1). Like
 * Drain, it is read again after every heavy snapshot (ADR-0036 §6); nothing here polls.
 */
@HiltViewModel(assistedFactory = AppDetailViewModel.Factory::class)
@OptIn(ExperimentalCoroutinesApi::class)
public class AppDetailViewModel
    @AssistedInject
    constructor(
        @Assisted public val uid: Int,
        drains: DrainRepository,
        window: ChosenWindow,
        settings: SettingsStore,
        private val icons: AppIcons,
    ) : ViewModel() {
        private val attempt = MutableStateFlow(0)

        internal val state: StateFlow<AppDetailUiState> =
            attempt
                .flatMapLatest {
                    val details = window.span.flatMapLatest { span -> drains.app(span, uid).map { span to it } }
                    val scaled = settings.settings.map { it.scaleToMeasured }.distinctUntilChanged()
                    combine(details, scaled) { (span, detail), scale ->
                        AppDetailUiState.Ready(span, detail, scale) as AppDetailUiState
                    }.catch { emit(AppDetailUiState.Error) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppDetailUiState.Loading)

        /** After [AppDetailUiState.Error]: reads everything again. */
        internal fun retry() {
            attempt.value++
        }

        /** The app's icon at [sizePx], from the shared cache. */
        internal suspend fun icon(
            packageName: String,
            sizePx: Int,
        ): ImageBitmap? = icons.icon(packageName, sizePx)?.asImageBitmap()

        /** The icon if the cache already holds it, so it shows from the first frame. */
        internal fun peekIcon(
            packageName: String,
            sizePx: Int,
        ): ImageBitmap? = icons.peek(packageName, sizePx)?.asImageBitmap()

        /** Creates the screen's model for one UID (SPEC 15.2: the app passes the UID, not a route). */
        @AssistedFactory
        public interface Factory {
            public fun create(uid: Int): AppDetailViewModel
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

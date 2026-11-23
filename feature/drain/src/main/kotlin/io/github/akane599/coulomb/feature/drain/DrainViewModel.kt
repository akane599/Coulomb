// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.collect.Collector
import io.github.akane599.coulomb.core.collect.LiveReads
import io.github.akane599.coulomb.core.collect.SnapshotOutcome
import io.github.akane599.coulomb.core.data.AppIcons
import io.github.akane599.coulomb.core.data.ChosenWindow
import io.github.akane599.coulomb.core.data.DrainRepository
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.data.NamedReference
import io.github.akane599.coulomb.core.data.References
import io.github.akane599.coulomb.core.data.launchWrite
import io.github.akane599.coulomb.core.datastore.SettingsStore
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.dumpsys.PowerDump
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.ui.PullRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/**
 * Drain (SPEC 10.3) for the window chosen across screens ([ChosenWindow]). A report is read again
 * after every heavy snapshot (ADR-0036); nothing here polls. The two switches are SPEC 14.4
 * settings, kept in [SettingsStore]. A privilege issue shows as Now's does (SPEC 10.11 partial).
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
public class DrainViewModel
    @Suppress("LongParameterList") // One source for each thing Drain shows; Hilt supplies them.
    @Inject
    constructor(
        drains: DrainRepository,
        private val window: ChosenWindow,
        private val icons: AppIcons,
        private val settings: SettingsStore,
        access: PrivilegedAccess,
        private val collector: Collector,
        private val namedReferences: References,
        private val live: LiveReads,
    ) : ViewModel() {
        private val attempt = MutableStateFlow(0)

        private val pull = PullRefresh(viewModelScope, REFRESH_WAIT_MS) { collector.snapshot(SnapshotTrigger.PULL_TO_REFRESH) }

        /** True from a pull until the collector has handled it, or [REFRESH_WAIT_MS] at most (audit BUG-017). */
        internal val isRefreshing: StateFlow<Boolean> = pull.isRefreshing

        internal val state: StateFlow<DrainUiState> =
            attempt
                .flatMapLatest {
                    val reports = window.span.flatMapLatest { span -> drains.report(span).map { span to it } }
                    val issues = access.state.map { it.issue to (it.permission == ManagerPermission.GRANTED) }.distinctUntilChanged()
                    combine(window.span, reports, settings.settings, issues) { chosen, (span, report), prefs, (issue, allowed) ->
                        DrainUiState.Ready(
                            chosen = chosen,
                            report = report,
                            current = span == chosen,
                            includeSystem = prefs.showSystemApps,
                            scaled = prefs.scaleToMeasured,
                            issue = issue,
                            issueAllowed = allowed,
                        ) as DrainUiState
                    }.catch { emit(DrainUiState.Error) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DrainUiState.Loading)

        internal fun choose(span: DrainSpan) = window.choose(span)

        /** The user's named references, newest first (SPEC 7.6). */
        internal val references: StateFlow<List<NamedReference>> =
            namedReferences.named.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        /** "Mark this moment" (SPEC 7.6). */
        internal val marks = ReferenceMarks(viewModelScope, collector, namedReferences)

        /** S12 on demand (ADR-0068): the wakelocks held now. */
        internal suspend fun readPower(): PowerDump? = live.power()

        internal fun deleteReferences(ids: List<Long>) {
            // A span on a deleted reference would keep its name, and retention may take its snapshot.
            val open = window.span.value as? DrainSpan.Between
            val gone = references.value.filter { it.id in ids }.map { it.mark }
            if (open != null && (open.from in gone || open.to in gone)) window.choose(DrainSpan.SinceUnplugged)
            // A delete that fails leaves the list as stored (audit BUG-024).
            viewModelScope.launchWrite(TAG) { ids.forEach { namedReferences.delete(it) } }
        }

        internal fun includeSystem(include: Boolean) = viewModelScope.writeSetting { settings.showSystemApps(include) }

        internal fun scale(scaled: Boolean) = viewModelScope.writeSetting { settings.scaleToMeasured(scaled) }

        /**
         * Pull to refresh: a heavy snapshot, which the collector debounces to one a minute (SPEC 7.2).
         * The report is read again when its window closes, as after any heavy snapshot.
         */
        internal fun refresh() = pull.pull()

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

/**
 * A switch's settings write. One that fails (a full disk) is logged, and the switch, which shows the
 * stored value, stays as it was (audit BUG-049).
 */
internal fun CoroutineScope.writeSetting(block: suspend () -> Unit) {
    launchWrite(TAG, block = block)
}

private const val TAG = "Drain"

/**
 * "Mark this moment": a heavy snapshot first, which the collector debounces to one a minute
 * (SPEC 7.2), so the reference is at most that old; then the name on the latest one. A failed
 * snapshot, or none that recent, marks nothing and says so (audit BUG-001).
 */
internal class ReferenceMarks(
    private val scope: CoroutineScope,
    private val collector: Collector,
    private val references: References,
) {
    private val unmarked = MutableStateFlow(false)

    /** The last mark found no snapshot of the moment, so it marked nothing. */
    val failed: StateFlow<Boolean> = unmarked.asStateFlow()

    fun mark(name: String) {
        unmarked.value = false
        // A reference that could not be stored is "nothing was marked" too (audit BUG-024).
        scope.launchWrite(TAG, failed = { unmarked.value = true }) {
            val stored = collector.snapshot(SnapshotTrigger.PULL_TO_REFRESH) != SnapshotOutcome.FAILED
            unmarked.value = !stored || references.mark(name, System.currentTimeMillis()) == null
        }
    }

    /** The sheet that said so has closed. */
    fun seen() {
        unmarked.value = false
    }
}

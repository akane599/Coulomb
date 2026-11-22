// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.data.NightRepository
import io.github.akane599.coulomb.core.data.NightSummary
import io.github.akane599.coulomb.core.data.Timeline
import io.github.akane599.coulomb.core.data.TimelineRepository
import io.github.akane599.coulomb.core.data.TimelineTrack
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Timeline shows (SPEC 10.6). */
@Immutable
internal data class TimelineUiState(
    val window: TimeWindow,
    val nowMs: Long,
    /** What was read: more than the window, so a drag has something to show. Null before the first read. */
    val timeline: Timeline? = null,
    val failed: Boolean = false,
    val tracks: Set<TimelineTrack> = TimelineTrack.entries.toSet(),
    /** Where the scrub rule stands; null reads the latest moment in view. */
    val cursorMs: Long? = null,
    /** The marks the last tap landed on. */
    val picked: Picked? = null,
    val latestNight: NightSummary? = null,
    val labels: Map<Int, String> = emptyMap(),
    /** A read for [window] is due or running: what shows of it is not yet what there is. */
    val pending: Boolean = false,
    /** Coulomb cannot read the battery history now (SPEC 5, S7 needs the worker). */
    val locked: Boolean = false,
    /** SPEC 4.5's row, when access was lost rather than never set up. */
    val issue: PrivilegeIssue? = null,
    /** Shizuku has allowed Coulomb: a hang in [issue] is the helper's (ADR-0047). */
    val issueAllowed: Boolean = false,
) {
    /** The tracks shown, in the order of SPEC 10.6. */
    val shown: List<TimelineTrack> get() = TimelineTrack.entries.filter { it in tracks }
}

/**
 * The Timeline's window, cursor and tracks, and the history read for them. A read covers three
 * windows' length; another follows only when the window leaves it, zooms far into it, or a new
 * snapshot brings more history. Nothing here polls, and nothing is read while the screen is not
 * shown: the reads run inside [state], which stops five seconds after its last collector.
 */
@HiltViewModel
public class TimelineViewModel
    @Inject
    constructor(
        private val timelines: TimelineRepository,
        nights: NightRepository,
        access: PrivilegedAccess,
    ) : ViewModel() {
        /** What a read is for: the snapshots stored and the retries asked for when it ran. */
        private data class Stamp(
            val snapshots: Int,
            val attempt: Int,
        )

        private class Loaded(
            val range: TimeWindow,
            val timeline: Timeline,
            val stamp: Stamp,
        ) {
            /** The window lies in what was read, and is not so short in it that the current is too thin. */
            fun covers(window: TimeWindow): Boolean = window in range && range.spanMs <= window.spanMs * MAX_LOADED_WINDOWS
        }

        private val view = MutableStateFlow(now().let { TimelineUiState(TimeWindow.latest(it), it) })
        private val loaded = MutableStateFlow<Loaded?>(null)
        private val failed = MutableStateFlow(false)
        private val attempt = MutableStateFlow(0)
        private val snapshots = timelines.changes.catch { emit(0) }

        internal val state: StateFlow<TimelineUiState> =
            channelFlow {
                launch {
                    val stamps = combine(snapshots, attempt, ::Stamp)
                    combine(view.map { it.window }.distinctUntilChanged(), stamps) { window, stamp -> window to stamp }
                        .collectLatest { (window, stamp) -> read(window, stamp) }
                }
                // New history, or the screen shown again: a window that ended at now moves along to now.
                launch { snapshots.collect { follow() } }
                val read =
                    combine(view, loaded, failed) { view, loaded, failed ->
                        view.copy(timeline = loaded?.timeline, failed = failed, pending = !failed && loaded?.covers(view.window) != true)
                    }
                combine(
                    read,
                    nights.nights.map { it.firstOrNull() }.catch { emit(null) },
                    timelines.labels.catch { emit(emptyMap()) },
                    access.state
                        .map {
                            Access(
                                it.issue,
                                it.tier < AccessTier.T2_PRIVILEGED_WORKER,
                                it.permission == ManagerPermission.GRANTED,
                            )
                        }.distinctUntilChanged(),
                ) { state, night, labels, access ->
                    state.copy(
                        latestNight = night,
                        labels = labels,
                        issue = access.issue,
                        locked = access.locked,
                        issueAllowed = access.allowed,
                    )
                }.collect { send(it) }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), view.value)

        private suspend fun read(
            window: TimeWindow,
            stamp: Stamp,
        ) {
            val have = loaded.value
            if (have != null && have.stamp == stamp && have.covers(window)) return
            // While fingers move the window, wait for them to settle; the first read does not wait.
            if (have != null) delay(SETTLE_MS)
            val range = window.padded(now())
            flow { emit(timelines.read(range.fromMs, range.toMs)) }
                .catch { failed.value = true }
                .collect {
                    loaded.value = Loaded(range, it, stamp)
                    failed.value = false
                }
        }

        private fun follow() {
            val nowMs = now()
            view.update { it.copy(window = if (it.window.follows(it.nowMs)) it.window.toNow(nowMs) else it.window, nowMs = nowMs) }
        }

        /** After a failed read: reads again. */
        internal fun retry() {
            failed.value = false
            attempt.update { it + 1 }
        }

        internal fun transform(
            zoom: Float,
            focus: Float,
            pan: Float,
        ) {
            val nowMs = now()
            view.update { it.copy(window = it.window.moved(zoom, focus, pan, nowMs), nowMs = nowMs) }
        }

        /** Moves the window by [windows] of its length, earlier below zero: the way without two fingers. */
        internal fun shift(windows: Float) = transform(zoom = 1f, focus = 0f, pan = -windows)

        // A scrub leaves the last pick behind: the panel would otherwise describe marks the cursor has left.
        internal fun scrub(atMs: Long) = view.update { it.copy(cursorMs = atMs, picked = null) }

        /** A tap moves the cursor and picks what is under it on [track]; above the bands it only moves the cursor. */
        internal fun tap(
            track: TimelineTrack?,
            atMs: Long,
        ) {
            val timeline = loaded.value?.timeline
            view.update { state ->
                val picked = track?.let { timeline?.pick(it, atMs, state.window.spanMs / REACH_SHARE) }
                state.copy(cursorMs = atMs, picked = picked)
            }
        }

        internal fun dismissPicked() = view.update { it.copy(picked = null) }

        internal fun setRange(spanMs: Long) {
            val nowMs = now()
            view.update { it.copy(window = it.window.spanning(spanMs, nowMs), nowMs = nowMs) }
        }

        /** "Now": the same length ending now, and the readout back on the latest moment. */
        internal fun jumpToNow() {
            val nowMs = now()
            view.update { it.copy(window = it.window.toNow(nowMs), nowMs = nowMs, cursorMs = null, picked = null) }
        }

        internal fun setTrack(
            track: TimelineTrack,
            shown: Boolean,
        ) = view.update { it.copy(tracks = if (shown) it.tracks + track else it.tracks - track) }

        private fun now(): Long = System.currentTimeMillis()

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L

            /** A read is kept while it is at most this many windows long: past it the current is too thin. */
            const val MAX_LOADED_WINDOWS = 8
            const val SETTLE_MS = 150L

            /** A tap reaches this share of the window to each side: about 6 dp on a phone. */
            const val REACH_SHARE = 60
        }
    }

/** What the Timeline needs from the privilege state. */
private data class Access(
    val issue: PrivilegeIssue?,
    val locked: Boolean,
    val allowed: Boolean,
)

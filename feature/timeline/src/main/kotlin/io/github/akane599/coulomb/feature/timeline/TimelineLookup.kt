// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import androidx.compose.runtime.Immutable
import io.github.akane599.coulomb.core.data.Timeline
import io.github.akane599.coulomb.core.data.TimelineTrack
import io.github.akane599.coulomb.core.data.TrackSpan
import kotlin.math.abs

/** What the history says at one moment; a null is "not known", never "off". */
@Immutable
internal data class Moment(
    val atMs: Long,
    /** False where the history has nothing: then every state below is null. */
    val known: Boolean,
    val levelPct: Int?,
    val screenOn: Boolean?,
    val charging: Boolean?,
    /** [TrackSpan.DOZE_LIGHT], [TrackSpan.DOZE_DEEP] or 0 for none. */
    val doze: Int?,
    val currentMa: Double?,
)

/** The marks of one track near a tap: one stretch, or several too close to tell apart. */
@Immutable
internal data class Picked(
    val track: TimelineTrack,
    val spans: List<TrackSpan>,
) {
    val fromMs: Long get() = spans.first().fromWallMs
    val toMs: Long get() = spans.maxOf { it.toWallMs }
    val totalMs: Long get() = spans.sumOf { it.durationMs }

    /** The names behind the stretches (wakeup reasons, wakelocks, apps), most frequent first. */
    fun names(): List<Pair<String, Int>> =
        spans
            .mapNotNull { it.tag }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
}

/** A current reading further than this from the cursor is not "at" it. */
private const val CURRENT_REACH_MS = 90_000L
private const val MS_PER_HOUR = 3_600_000.0

internal fun Timeline.moment(atMs: Long): Moment {
    val known = atMs in fromWallMs..toWallMs && unknown.none { atMs >= it.fromWallMs && atMs < it.toWallMs }
    if (!known) return Moment(atMs, false, null, null, null, null, null)

    fun on(track: TimelineTrack): TrackSpan? = tracks[track].orEmpty().at(atMs)
    val reading = current.nearest(atMs)
    return Moment(
        atMs = atMs,
        known = true,
        levelPct = level.lastOrNull { it.wallMs <= atMs }?.levelPct,
        screenOn = on(TimelineTrack.SCREEN) != null,
        charging = on(TimelineTrack.CHARGING) != null,
        doze = on(TimelineTrack.DOZE)?.value ?: 0,
        currentMa = reading,
    )
}

private fun List<TrackSpan>.at(atMs: Long): TrackSpan? {
    // Spans are in time order and do not overlap: the last that starts by the moment is the candidate.
    var low = 0
    var high = size - 1
    var found: TrackSpan? = null
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid].fromWallMs <= atMs) {
            found = this[mid]
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found?.takeIf { atMs < it.toWallMs }
}

private fun List<io.github.akane599.coulomb.core.data.CurrentPoint>.nearest(atMs: Long): Double? {
    if (isEmpty()) return null
    val index = binarySearchBy(atMs) { it.wallMs }.let { if (it >= 0) it else -it - 1 }
    return listOfNotNull(getOrNull(index - 1), getOrNull(index))
        .minByOrNull { abs(it.wallMs - atMs) }
        ?.takeIf { abs(it.wallMs - atMs) <= CURRENT_REACH_MS }
        ?.currentMa
}

/**
 * The stretches of [track] within [reachMs] of [atMs]: what a tap on a mark, a tick or a density
 * span means. Null when nothing is there.
 */
internal fun Timeline.pick(
    track: TimelineTrack,
    atMs: Long,
    reachMs: Long,
): Picked? {
    val near = tracks[track].orEmpty().filter { it.toWallMs >= atMs - reachMs && it.fromWallMs <= atMs + reachMs }
    // Under the finger wins over beside it: a long stretch is itself, not it and its neighbours.
    val under = near.filter { atMs >= it.fromWallMs && atMs < it.toWallMs && it.durationMs > 2 * reachMs }
    val spans = under.ifEmpty { near }
    return if (spans.isEmpty()) null else Picked(track, spans)
}

/** Whether a stretch of one of [tracks] begins between two cursor positions: one haptic tick (SPEC 10.6). */
internal fun Timeline.crosses(
    a: Long,
    b: Long,
    tracks: Collection<TimelineTrack>,
): Boolean {
    val from = minOf(a, b)
    val to = maxOf(a, b)
    return tracks.any { track ->
        val spans = this.tracks[track].orEmpty()
        val index = spans.binarySearchBy(from + 1) { it.fromWallMs }.let { if (it >= 0) it else -it - 1 }
        index < spans.size && spans[index].fromWallMs <= to
    }
}

/** This timeline cut to a window: what is on screen, for the table, the caption and the summary. */
internal fun Timeline.cut(window: TimeWindow): Timeline {
    val from = maxOf(window.fromMs, fromWallMs)
    val to = minOf(window.toMs, toWallMs)
    // A window that does not touch what was read shows nothing of it.
    if (from >=
        to
    ) {
        return Timeline(
            window.fromMs,
            window.toMs,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyMap(),
            listOf(TrackSpan(window.fromMs, window.toMs)),
            null,
        )
    }

    fun List<TrackSpan>.inside(): List<TrackSpan> =
        mapNotNull { span ->
            val start = maxOf(span.fromWallMs, from)
            val end = minOf(span.toWallMs, to)
            if (start < end) span.copy(fromWallMs = start, toWallMs = end) else null
        }
    val before = level.lastOrNull { it.wallMs < from }
    val carried = if (before != null && moment(from).known) listOf(before.copy(wallMs = from, startsSegment = false)) else emptyList()
    return Timeline(
        fromWallMs = window.fromMs,
        toWallMs = window.toMs,
        level = carried + level.filter { it.wallMs in from..to },
        current = current.filter { it.wallMs in from..to },
        temperature = temperature.filter { it.wallMs in from..to },
        tracks = tracks.mapValues { (_, spans) -> spans.inside() },
        unknown =
            unknown.inside() +
                listOfNotNull(
                    TrackSpan(window.fromMs, from).takeIf { from > window.fromMs },
                    TrackSpan(to, window.toMs).takeIf { to < window.toMs },
                ),
        knownUntilWallMs = knownUntilWallMs?.let { minOf(it, to) },
    )
}

/** The time of this timeline the history covers. */
internal val Timeline.knownMs: Long
    get() = (toWallMs - fromWallMs - unknown.sumOf { it.durationMs }).coerceAtLeast(0)

/** How often the phone woke, per hour of known history; null when there is too little to say. */
internal fun Timeline.wakeupsPerHour(): Double? {
    val hours = knownMs / MS_PER_HOUR
    val wakeups = tracks[TimelineTrack.AWAKE].orEmpty().size
    return if (hours < MIN_RATE_HOURS || wakeups == 0) null else wakeups / hours
}

private const val MIN_RATE_HOURS = 0.25

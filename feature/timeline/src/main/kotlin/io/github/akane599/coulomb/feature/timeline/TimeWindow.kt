// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import androidx.compose.runtime.Immutable

/**
 * The stretch of time the Timeline shows (SPEC 10.6): between 15 minutes and 7 days long, never
 * past now and never before the 30 days history is kept (SPEC 7.7).
 */
@Immutable
internal data class TimeWindow(
    val fromMs: Long,
    val toMs: Long,
) {
    val spanMs: Long get() = toMs - fromMs

    operator fun contains(other: TimeWindow): Boolean = other.fromMs >= fromMs && other.toMs <= toMs

    /**
     * This window after a two-finger move: zoomed by [zoom] about [focus], a share of its width from
     * its start, and dragged by [pan], a share of its width (towards the end shows earlier times).
     */
    fun moved(
        zoom: Float,
        focus: Float,
        pan: Float,
        nowMs: Long,
    ): TimeWindow {
        val span = (spanMs / zoom.coerceAtLeast(MIN_ZOOM).toDouble()).toLong().coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)
        val anchor = fromMs + (focus * spanMs).toLong()
        val from = anchor - (focus * span).toLong() - (pan * span).toLong()
        return TimeWindow(from, from + span).within(nowMs)
    }

    /** A preset's length, ending where this window ends. */
    fun spanning(
        spanMs: Long,
        nowMs: Long,
    ): TimeWindow = TimeWindow(toMs - spanMs, toMs).within(nowMs)

    /** The same length, ending now. */
    fun toNow(nowMs: Long): TimeWindow = TimeWindow(nowMs - spanMs, nowMs)

    /** Whether the window ends at now, so new history should move it along. */
    fun follows(nowMs: Long): Boolean = toMs >= nowMs - FOLLOW_SLACK_MS

    /** Moved, at the same length, into the time history is kept for. */
    fun within(nowMs: Long): TimeWindow {
        val to = toMs.coerceIn(nowMs - KEPT_MS + spanMs, nowMs)
        return TimeWindow(to - spanMs, to)
    }

    /** What to read for this window: a window's length more on each side, so a drag has something to show. */
    fun padded(nowMs: Long): TimeWindow = TimeWindow(fromMs - spanMs, minOf(toMs + spanMs, nowMs))

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
        private const val DAY_MS = 24 * HOUR_MS
        const val MIN_SPAN_MS: Long = 15 * MINUTE_MS
        const val MAX_SPAN_MS: Long = 7 * DAY_MS
        const val DEFAULT_SPAN_MS: Long = DAY_MS

        /** The range presets of the toolbar (design lab: 15 min, 6 h, 24 h, 7 d). */
        val PRESETS_MS: List<Long> = listOf(MIN_SPAN_MS, 6 * HOUR_MS, DAY_MS, MAX_SPAN_MS)

        /** History events are kept 30 days (SPEC 7.7). */
        private const val KEPT_MS = 30 * DAY_MS
        private const val FOLLOW_SLACK_MS = MINUTE_MS
        private const val MIN_ZOOM = 0.01f

        /** The last [spanMs] up to now. */
        fun latest(
            nowMs: Long,
            spanMs: Long = DEFAULT_SPAN_MS,
        ): TimeWindow = TimeWindow(nowMs - spanMs, nowMs)
    }
}

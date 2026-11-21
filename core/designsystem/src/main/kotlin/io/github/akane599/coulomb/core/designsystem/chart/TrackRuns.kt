// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.chart

/** What a track draws for a stretch of its marks, in pixels across the plot. */
internal sealed interface TrackRun {
    val from: Float
    val to: Float

    /** A state long enough to see: solid, or a 20% tint with an outline when [light]. */
    data class Span(
        override val from: Float,
        override val to: Float,
        val light: Boolean,
    ) : TrackRun

    /** One event too short to have a width. */
    data class Tick(
        val at: Float,
    ) : TrackRun {
        override val from: Float get() = at
        override val to: Float get() = at
    }

    /** [count] events too close to draw one by one (DESIGN.md, Charts): a tint with an outline. */
    data class Dense(
        override val from: Float,
        override val to: Float,
        val count: Int,
    ) : TrackRun
}

/**
 * Lays a track's marks out at the current zoom (SPEC 10.6, ADR-0023). A mark narrower than
 * [minSpan] is an event, drawn as a tick; events closer together than [merge] become one density
 * span, which zooming in splits back into ticks. Pure.
 */
internal object TrackRuns {
    /**
     * [marks] in time order, each as its pixel ends and whether it is a light state; the result is
     * clipped to `0..width`.
     */
    fun of(
        marks: List<Triple<Float, Float, Boolean>>,
        width: Float,
        minSpan: Float,
        merge: Float,
    ): List<TrackRun> {
        val out = ArrayList<TrackRun>()
        var first = 0f
        var last = 0f
        var count = 0

        fun flush() {
            if (count ==
                1
            ) {
                out += TrackRun.Tick(first)
            } else if (count > 1) {
                out += TrackRun.Dense(first, maxOf(last, first + minSpan), count)
            }
            count = 0
        }
        for ((start, end, light) in marks) {
            if (end < 0f || start > width) continue
            val from = start.coerceAtLeast(0f)
            val to = end.coerceAtMost(width)
            if (to - from >= minSpan) {
                flush()
                out += TrackRun.Span(from, to, light)
            } else {
                val at = (from + to) / 2
                if (count > 0 && at - last >= merge) flush()
                if (count == 0) first = at
                last = at
                count++
            }
        }
        flush()
        return out
    }
}

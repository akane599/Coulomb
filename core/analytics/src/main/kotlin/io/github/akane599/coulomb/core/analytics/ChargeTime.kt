// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import io.github.akane599.coulomb.core.model.Provenance

/** A charge's level at a moment on the elapsed clock, from one boot. */
public data class ChargeLevel(
    val elapsedNs: Long,
    val levelPct: Int,
)

/** A past charge's level line in time order, and whether it was on the charger now plugged in. */
public data class PastCharge(
    val sameCharger: Boolean,
    val points: List<ChargeLevel>,
)

/** Which past charges an estimate comes from. */
public enum class ChargeTimeBasis {
    /** At least [ChargeTimes.MIN_CHARGES] charges on the charger now plugged in. */
    SAME_CHARGER,

    /** Too few on this charger, so charges on any charger, scaled to this one's pace. */
    ANY_CHARGER,
}

/**
 * Time to a level as a range, never one number (SPEC 10.9, as SPEC 6.8 for time to empty):
 * [shortestMin] is always below [longestMin]. A model's output, so Estimated (SPEC 6.7).
 */
public data class ChargeTime(
    val shortestMin: Double,
    val longestMin: Double,
    val basis: ChargeTimeBasis,
    /** How many past charges the range comes from. */
    val charges: Int,
) {
    val provenance: Provenance get() = Provenance.ESTIMATED
}

/**
 * ADR-0046 §3: time to a target level from how past charges went from here to there.
 *
 * - A charge's shape above the taper is the battery's, not the charger's, so the past charges that
 *   ran from the current level to the target say how long the rest takes. Each one counts its
 *   minutes from the level to the target, scaled by this charge's pace against its own over the
 *   last steps both crossed: at least two and at most [PACE_STEPS], and at least [PACE_MIN_MINUTES]
 *   long, so minute-stamped history cannot swing it. A slower charger or a warm battery stretches
 *   every past charge alike. The scale is held to [MIN_SCALE]–[MAX_SCALE].
 * - A past charge counts only if it started below the first level it is compared over (its first
 *   step was part-done), reached the target, and did not pause on the way: a step of
 *   [HOLD_STEP_MIN] or more and [HOLD_STEP_SHARE] times its median, as at an overnight hold.
 * - Charges on the charger now plugged in are used when there are [MIN_CHARGES]; otherwise
 *   charges on any charger, which the pace scale matters more for.
 * - The range is the middle half of the scaled times from five charges up, else their whole spread,
 *   widened to at least ±[MIN_HALF_WIDTH] of its middle. The time already spent at the current
 *   level comes off both ends, but never more than the step usually takes.
 * - Null while the level is below this charge's highest so far: the phone is using more than the
 *   charger gives, and past charges say nothing about that.
 * - Null below [MIN_CHARGES] charges that ran from here to the target: Coulomb says it needs more
 *   charges rather than guess at a taper it has not seen.
 */
public object ChargeTimes {
    /**
     * Time from the last of [current], this charge's level line, to [target]. [history] is newest
     * first; a past charge counts only if it started below the level the pace is read from (the
     * current level without a pace), reached [target] and did not pause on the way.
     */
    public fun toLevel(
        current: List<ChargeLevel>,
        history: List<PastCharge>,
        target: Int,
    ): ChargeTime? {
        val now = current.lastOrNull() ?: return null
        val level = now.levelPct
        if (level >= target || level < current.maxOf { it.levelPct }) return null
        val pace = pace(current, level)
        // The last arrival at this level, which is the highest so far (checked above).
        val reachedLevelNs = current[current.indexOfLast { it.levelPct < level } + 1].elapsedNs
        val spentMin = (now.elapsedNs - reachedLevelNs) / NS_PER_MIN
        val covering = history.filter { covers(it.points, pace?.fromLevel ?: level, target) && !paused(it.points, level, target) }
        val same = covering.filter { it.sameCharger }
        val (pool, basis) =
            when {
                same.size >= MIN_CHARGES -> same to ChargeTimeBasis.SAME_CHARGER
                covering.size >= MIN_CHARGES -> covering to ChargeTimeBasis.ANY_CHARGER
                else -> return null
            }
        val used = pool.take(MAX_CHARGES)
        val scales = used.map { past -> pace?.let { scaleAgainst(it, past.points) } ?: 1.0 }
        val minutes = used.mapIndexed { i, past -> scales[i] * minutesBetween(past.points, level, target) }
        // Time at this level beyond what the step usually takes is not taken off twice.
        val step = median(used.mapIndexed { i, past -> scales[i] * minutesBetween(past.points, level, level + 1) })
        return range(minutes.sorted(), spentMin.coerceAtMost(step), basis)
    }

    /**
     * This charge's minutes over its last whole steps up to [level]: the fewest, from two, that span
     * [PACE_MIN_MINUTES], at most [PACE_STEPS]; null if none does.
     */
    private fun pace(
        current: List<ChargeLevel>,
        level: Int,
    ): Pace? {
        // The first level was already part-way through its step when the charge began.
        val first = current.first().levelPct + 1
        for (from in level - MIN_PACE_STEPS downTo maxOf(first, level - PACE_STEPS)) {
            val minutes = minutesBetween(current, from, level)
            if (minutes >= PACE_MIN_MINUTES) return Pace(from, level, minutes)
        }
        return null
    }

    private fun scaleAgainst(
        pace: Pace,
        past: List<ChargeLevel>,
    ): Double {
        val theirs = minutesBetween(past, pace.fromLevel, pace.toLevel)
        if (theirs <= 0) return 1.0
        return (pace.minutes / theirs).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /** A past charge that started below [from], so its step into [from] was whole, and reached [to]. */
    private fun covers(
        points: List<ChargeLevel>,
        from: Int,
        to: Int,
    ): Boolean = points.isNotEmpty() && points.first().levelPct < from && points.any { it.levelPct >= to }

    /** A step from [from] to [to] of at least [HOLD_STEP_MIN] and [HOLD_STEP_SHARE] times the median step. */
    private fun paused(
        points: List<ChargeLevel>,
        from: Int,
        to: Int,
    ): Boolean {
        val steps = (from until to).map { minutesBetween(points, it, it + 1) }
        val usual = median(steps)
        return steps.any { it >= HOLD_STEP_MIN && it >= HOLD_STEP_SHARE * usual }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Minutes from first reaching [from] to first reaching [to]. */
    private fun minutesBetween(
        points: List<ChargeLevel>,
        from: Int,
        to: Int,
    ): Double {
        val start = arrival(points, from) ?: return 0.0
        val end = arrival(points, to) ?: return 0.0
        return ((end - start) / NS_PER_MIN).coerceAtLeast(0.0)
    }

    /** When the level first reached [level] or above; null if it never did. */
    private fun arrival(
        points: List<ChargeLevel>,
        level: Int,
    ): Long? = points.firstOrNull { it.levelPct >= level }?.elapsedNs

    private fun range(
        sorted: List<Double>,
        spentMin: Double,
        basis: ChargeTimeBasis,
    ): ChargeTime {
        var low = if (sorted.size >= QUARTILE_FROM) quantile(sorted, LOWER_QUARTILE) else sorted.first()
        var high = if (sorted.size >= QUARTILE_FROM) quantile(sorted, UPPER_QUARTILE) else sorted.last()
        val middle = (low + high) / 2
        if (high - low < 2 * MIN_HALF_WIDTH * middle) {
            low = middle * (1 - MIN_HALF_WIDTH)
            high = middle * (1 + MIN_HALF_WIDTH)
        }
        val shortest = (low - spentMin).coerceAtLeast(0.0)
        val longest = (high - spentMin).coerceAtLeast(shortest + MIN_SPREAD_MIN)
        return ChargeTime(shortest, longest, basis, sorted.size)
    }

    /** Linear interpolation between the order statistics of [sorted]. */
    private fun quantile(
        sorted: List<Double>,
        q: Double,
    ): Double {
        val at = q * (sorted.size - 1)
        val below = at.toInt()
        val above = minOf(below + 1, sorted.size - 1)
        return sorted[below] + (sorted[above] - sorted[below]) * (at - below)
    }

    private class Pace(
        val fromLevel: Int,
        val toLevel: Int,
        val minutes: Double,
    )

    /** Past charges a range needs. */
    public const val MIN_CHARGES: Int = 3

    /** The latest past charges a range uses. */
    public const val MAX_CHARGES: Int = 10

    public const val PACE_STEPS: Int = 10
    private const val MIN_PACE_STEPS = 2

    /** The shortest span a pace is read over: minute-stamped history is a minute off at most. */
    public const val PACE_MIN_MINUTES: Double = 5.0

    /** A step this long, and [HOLD_STEP_SHARE] times the usual, is a pause. */
    public const val HOLD_STEP_MIN: Double = 30.0
    public const val HOLD_STEP_SHARE: Double = 4.0
    public const val MIN_SCALE: Double = 0.5
    public const val MAX_SCALE: Double = 2.0
    public const val MIN_HALF_WIDTH: Double = 0.1
    private const val QUARTILE_FROM = 5
    private const val LOWER_QUARTILE = 0.25
    private const val UPPER_QUARTILE = 0.75
    private const val MIN_SPREAD_MIN = 1.0
    private const val NS_PER_MIN = 60_000_000_000.0
}

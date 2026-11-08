// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import io.github.akane599.coulomb.core.model.Provenance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One context's drain over a stretch of time: the current session's time in that context, or one
 * past window in it. A context is the screen state, and for past windows also the hour of day
 * (SPEC 6.8); the caller picks the rates of the context the phone is in now.
 */
public data class ContextRate(
    /** The shown %/h of SPEC 6.2, positive while discharging. */
    val pctPerHour: Double,
    val hours: Double,
    /** 0 to 1 (SPEC 6.7). */
    val quality: Double,
)

/**
 * SPEC 6.8: time to empty as a range, never one number; [shortestHours] is always below
 * [longestHours]. It is a model's output, so it is Estimated (SPEC 6.7).
 */
public data class TimeLeft(
    val shortestHours: Double,
    val longestHours: Double,
) {
    val provenance: Provenance get() = Provenance.ESTIMATED
}

/**
 * SPEC 6.8 time remaining: a blend of the current session's rate and the personal history for the
 * same context.
 *
 * - The history gives a band, its weighted lower and upper quartile; each window weighs its hours
 *   times its quality. Few windows say little about the next hours, so the band is at least
 *   [UNTRUSTED_SPREAD] / √n around the median for `n` effective windows: one past day is as wide
 *   as an untrusted session.
 * - The session gives its rate with a spread that narrows as trust in it grows. Trust is its
 *   quality times `h / (h + 1)` for `h` hours, so one hour of perfect data earns half the weight.
 * - The band is the trust-weighted blend of the two, widened on each side by
 *   `trust × (1 − trust) × |session − history|`, so a session that disagrees with the history
 *   widens the range instead of landing between the two. It stays within the two bands' outer
 *   edges and never gets narrower than ±10% around the blended rate, so the range never collapses
 *   to one number.
 * - Time left is the level over the band's upper and lower rate.
 */
public object TimeRemaining {
    /** Hours of perfect session data that earn the session half the weight. */
    public const val SESSION_HALF_TRUST_HOURS: Double = 1.0

    /** The narrowest band, as a share of the blended rate on each side. */
    public const val MIN_SPREAD: Double = 0.10

    /** The session rate's spread when nothing backs it; it narrows to [MIN_SPREAD] at full trust. */
    public const val UNTRUSTED_SPREAD: Double = 0.50

    private const val LOWER_QUARTILE = 0.25
    private const val MEDIAN = 0.5
    private const val UPPER_QUARTILE = 0.75
    private const val BOUNDARY_TOLERANCE = 1e-9

    /**
     * Time to empty from [levelPct], or null when there is nothing to estimate from: no usable rate,
     * or an empty battery. Rates, hours and qualities that are not positive and finite are ignored.
     */
    public fun estimate(
        levelPct: Int,
        session: ContextRate?,
        history: List<ContextRate>,
    ): TimeLeft? {
        if (levelPct <= 0) return null
        val current = session?.takeIf { it.usable() }
        val past = history.filter { it.usable() }
        val band =
            when {
                current == null && past.isEmpty() -> return null
                current == null -> historyBand(past)
                past.isEmpty() -> sessionBand(current, trust(current))
                else -> blend(sessionBand(current, trust(current)), historyBand(past), trust(current))
            }
        val low = minOf(band.low, band.mid * (1 - MIN_SPREAD))
        val high = maxOf(band.high, band.mid * (1 + MIN_SPREAD))
        return TimeLeft(shortestHours = levelPct / high, longestHours = levelPct / low)
    }

    /** How far the session's rate is trusted, 0 to below 1. */
    internal fun trust(session: ContextRate): Double =
        session.quality.coerceIn(0.0, 1.0) * session.hours / (session.hours + SESSION_HALF_TRUST_HOURS)

    private fun sessionBand(
        session: ContextRate,
        trust: Double,
    ): Band {
        val spread = UNTRUSTED_SPREAD - (UNTRUSTED_SPREAD - MIN_SPREAD) * trust
        val rate = session.pctPerHour
        return Band(low = rate * (1 - spread), mid = rate, high = rate * (1 + spread))
    }

    private fun historyBand(history: List<ContextRate>): Band {
        val weighted = history.map { it.pctPerHour to it.hours * it.quality.coerceIn(0.0, 1.0) }
        val mid = weightedQuantile(weighted, MEDIAN)
        val spread = UNTRUSTED_SPREAD / sqrt(effectiveCount(weighted.map { it.second }))
        return Band(
            low = min(weightedQuantile(weighted, LOWER_QUARTILE), mid * (1 - spread)),
            mid = mid,
            high = max(weightedQuantile(weighted, UPPER_QUARTILE), mid * (1 + spread)),
        )
    }

    private fun blend(
        session: Band,
        history: Band,
        trust: Double,
    ): Band {
        val disagreement = trust * (1 - trust) * abs(session.mid - history.mid)
        return Band(
            low = max(trust * session.low + (1 - trust) * history.low - disagreement, min(session.low, history.low)),
            mid = trust * session.mid + (1 - trust) * history.mid,
            high = min(trust * session.high + (1 - trust) * history.high + disagreement, max(session.high, history.high)),
        )
    }

    /** Kish's effective sample size: `n` for `n` equal weights, near 1 when one weight dominates. */
    internal fun effectiveCount(weights: List<Double>): Double {
        val squares = weights.sumOf { it * it }
        return if (squares > 0) weights.sum().let { it * it } / squares else weights.size.toDouble()
    }

    /**
     * The value at [q] of the weighted distribution: the smallest value whose cumulative weight
     * reaches [q] of the total, or the midpoint of two values when [q] falls exactly between them,
     * as the median of an even count. Values with no weight count by rank when every weight is zero.
     */
    internal fun weightedQuantile(
        values: List<Pair<Double, Double>>,
        q: Double,
    ): Double {
        val sorted = values.sortedBy { it.first }
        val total = sorted.sumOf { it.second }
        if (total <= 0) return sorted[((sorted.size - 1) * q).toInt()].first
        val target = q * total
        val tolerance = total * BOUNDARY_TOLERANCE
        var cumulative = 0.0
        for ((index, entry) in sorted.withIndex()) {
            cumulative += entry.second
            if (cumulative >= target - tolerance) {
                val next = sorted.getOrNull(index + 1)
                return if (next != null && abs(cumulative - target) <= tolerance) (entry.first + next.first) / 2 else entry.first
            }
        }
        return sorted.last().first
    }

    private fun ContextRate.usable(): Boolean =
        pctPerHour.isFinite() && pctPerHour > 0 && hours.isFinite() && hours > 0 && quality.isFinite() && quality > 0

    private data class Band(
        val low: Double,
        val mid: Double,
        val high: Double,
    )
}

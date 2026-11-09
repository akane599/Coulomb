// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

/** Where a charge is, in plain words (SPEC 10.9; constant current and voltage stay in the glossary). */
public enum class ChargePhase {
    /** The charger's full current. */
    FULL_SPEED,

    /** The current is tapering as the battery nears full. */
    SLOWING,

    /** The level has stopped rising while plugged in, as at a charge limit. */
    HOLDING,

    /** At 100%. */
    FULL,

    /** Plugged in, but the phone uses more than the charger gives: the current flows out. */
    DRAWING,
}

/**
 * ADR-0046 §2: the live phase from this charge's samples, by ADR-0043's taper rule.
 *
 * - FULL at 100%.
 * - HOLDING when the level has not moved for [HOLD_MS] and the current is at most [HOLD_SHARE] of
 *   full speed, or at most [HOLD_MAX_UA] when full speed is not known.
 * - SLOWING when the last [Charging.MIN_POWER_POINTS] / 2 powered samples are all below
 *   [Charging.TAPER_SHARE] of full speed at 50% or above. Full speed is the median current below
 *   50%, as [Charging.figures] takes it; a charge that started higher has none, so the usual taper
 *   level of past charges on the same plug ([usualTaperLevel]) stands in.
 * - DRAWING when the median of the last [Charging.MIN_POWER_POINTS] / 2 currents is zero or below.
 * - FULL_SPEED otherwise.
 * - Null without a calibrated current in the latest sample, the phase being read from the current;
 *   and null for a charge with no full speed and no usual taper level, which cannot tell the two
 *   apart (estimator review: never "full speed" by default).
 */
public object ChargePhases {
    public fun of(
        samples: List<ChargeSample>,
        usualTaperLevel: Int?,
    ): ChargePhase? {
        val last = samples.lastOrNull() ?: return null
        if (last.levelPct >= FULL) return ChargePhase.FULL
        val currentUa = last.currentUa ?: return null
        val fullSpeedUa = fullSpeedUa(samples)
        if (holding(samples, currentUa, fullSpeedUa)) return ChargePhase.HOLDING
        val recent = samples.mapNotNull { it.currentUa }.takeLast(RECENT_POINTS).sorted()
        if (recent[recent.size / 2] <= 0) return ChargePhase.DRAWING
        return when (slowing(samples, fullSpeedUa, usualTaperLevel)) {
            true -> ChargePhase.SLOWING
            false -> ChargePhase.FULL_SPEED
            null -> null
        }
    }

    /** The median current below 50%, with enough samples there; else null. */
    public fun fullSpeedUa(samples: List<ChargeSample>): Double? {
        val early =
            samples.filter { it.levelPct < Charging.TAPER_SEARCH_FROM }.mapNotNull {
                it.currentUa
                    ?.takeIf { ua ->
                        ua > 0
                    }?.toDouble()
            }
        if (early.size < Charging.MIN_POWER_POINTS) return null
        val sorted = early.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun holding(
        samples: List<ChargeSample>,
        currentUa: Long,
        fullSpeedUa: Double?,
    ): Boolean {
        val last = samples.last()
        val since = samples.lastOrNull { it.levelPct != last.levelPct }?.elapsedNs ?: samples.first().elapsedNs
        if ((last.elapsedNs - since) / NS_PER_MS < HOLD_MS) return false
        val limit = fullSpeedUa?.let { it * HOLD_SHARE } ?: HOLD_MAX_UA.toDouble()
        return currentUa <= limit
    }

    private fun slowing(
        samples: List<ChargeSample>,
        fullSpeedUa: Double?,
        usualTaperLevel: Int?,
    ): Boolean? {
        val level = samples.last().levelPct
        if (fullSpeedUa == null) return usualTaperLevel?.let { level >= it }
        if (level < Charging.TAPER_SEARCH_FROM) return false
        val recent = samples.mapNotNull { it.currentUa }.takeLast(RECENT_POINTS)
        return recent.size == RECENT_POINTS && recent.all { it < fullSpeedUa * Charging.TAPER_SHARE }
    }

    private const val FULL = 100
    private const val RECENT_POINTS = Charging.MIN_POWER_POINTS / 2
    public const val HOLD_MS: Long = 10 * 60_000L
    public const val HOLD_SHARE: Double = 0.1
    public const val HOLD_MAX_UA: Long = 100_000L
    private const val NS_PER_MS = 1_000_000L
}

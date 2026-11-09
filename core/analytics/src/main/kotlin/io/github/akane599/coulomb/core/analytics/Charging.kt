// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import kotlin.math.abs

/** One sample of a charge session, with its calibrated current (positive into the battery). */
public data class ChargeSample(
    val elapsedNs: Long,
    val levelPct: Int,
    val currentUa: Long?,
    val voltageMv: Int,
    val tempDeciC: Int,
    val plugType: Int,
)

/**
 * What a closed charge session's samples say about its power (ADR-0043 §1). Power figures are null
 * without [Charging.MIN_POWER_POINTS] samples that have a calibrated current.
 */
public data class ChargeFigures(
    /** The plug type of most samples (`BatteryManager.BATTERY_PLUGGED_*`). */
    val plugType: Int,
    val peakW: Double?,
    /** Median power before the current tapers ([ccToCvLevel]), or over the session without a taper. */
    val fullSpeedW: Double?,
    /** The level from which the current stays below [Charging.TAPER_SHARE] of full speed. */
    val ccToCvLevel: Int?,
    val minutesAbove38C: Int,
)

/** A stored charger profile, as [Charging.match] needs it. */
public data class ChargerBaseline(
    val id: Long,
    val plugType: Int,
    val baselineW: Double,
)

/**
 * ADR-0043: a charge session's power figures, the point where its current tapers, and which
 * charger it was. Android exposes no charger identity, so a charger is its plug type and its
 * full-speed power: the same plug within [MATCH_TOLERANCE] of a profile's baseline is that
 * profile. Everything here is Estimated.
 */
public object Charging {
    public fun figures(points: List<ChargeSample>): ChargeFigures {
        val plug =
            points
                .filter { it.plugType != 0 }
                .groupingBy { it.plugType }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: 0
        val powered =
            points.mapNotNull { p ->
                p.currentUa?.takeIf { it > 0 }?.let { ua -> Powered(p.levelPct, ua, ua.toDouble() * p.voltageMv / NW_PER_W) }
            }
        val minutesHot = minutesWhere(points) { it.tempDeciC > HOT_DECI_C }
        if (powered.size < MIN_POWER_POINTS) return ChargeFigures(plug, null, null, null, minutesHot)
        val taper = taperLevel(powered)
        val fullSpeed = powered.filter { taper == null || it.levelPct < taper }.map { it.watts }
        return ChargeFigures(
            plugType = plug,
            peakW = powered.maxOf { it.watts },
            fullSpeedW = fullSpeed.takeIf { it.size >= MIN_POWER_POINTS }?.let(::median),
            ccToCvLevel = taper,
            minutesAbove38C = minutesHot,
        )
    }

    /**
     * The profile [plugType] and [fullSpeedW] belong to: the same plug type and the nearest
     * baseline, within [MATCH_TOLERANCE] of it. Null means a new charger.
     */
    public fun match(
        plugType: Int,
        fullSpeedW: Double,
        profiles: List<ChargerBaseline>,
    ): Long? =
        profiles
            .filter { it.plugType == plugType && it.baselineW > 0 }
            .minByOrNull { abs(fullSpeedW - it.baselineW) / it.baselineW }
            ?.takeIf { abs(fullSpeedW - it.baselineW) / it.baselineW <= MATCH_TOLERANCE }
            ?.id

    /** A profile's baseline: the median full-speed power of its latest [BASELINE_SESSIONS] sessions. */
    public fun baseline(latestFullSpeedW: List<Double>): Double = median(latestFullSpeedW.take(BASELINE_SESSIONS))

    /**
     * Full speed is the median current below [TAPER_SEARCH_FROM] (the first third of the samples
     * when the charge started higher). The taper is the lowest level above that from which every
     * later sample's current stays below [TAPER_SHARE] of it, with at least [MIN_TAPER_POINTS] such
     * samples; a dip that recovers, as under brief heat, is not a taper.
     */
    private fun taperLevel(powered: List<Powered>): Int? {
        val early = powered.filter { it.levelPct < TAPER_SEARCH_FROM }.ifEmpty { powered.take(powered.size / EARLY_SHARE_DIVISOR) }
        if (early.isEmpty()) return null
        val fullSpeedUa = median(early.map { it.currentUa.toDouble() })
        val limit = fullSpeedUa * TAPER_SHARE
        // Walk back from the end while the current stays low; the first high sample ends the taper.
        val tail = powered.asReversed().takeWhile { it.currentUa < limit }
        if (tail.size < MIN_TAPER_POINTS) return null
        val level = tail.minOf { it.levelPct }
        return level.takeIf { it > early.maxOf { e -> e.levelPct } }
    }

    /** A sample with a current into the battery, and its power. */
    private class Powered(
        val levelPct: Int,
        val currentUa: Long,
        val watts: Double,
    )

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Time from each sample to the next while [test] holds of the first; a gap counts at most two minutes. */
    private fun minutesWhere(
        points: List<ChargeSample>,
        test: (ChargeSample) -> Boolean,
    ): Int {
        val ns =
            points
                .zipWithNext()
                .filter { (a, _) -> test(a) }
                .sumOf { (a, b) -> (b.elapsedNs - a.elapsedNs).coerceIn(0, MAX_SAMPLE_SPAN_NS) }
        return (ns / NS_PER_MIN).toInt()
    }

    /** Fewer samples with a current than this give no power figures. */
    public const val MIN_POWER_POINTS: Int = 10

    /** Below this share of full-speed current the charge is tapering. */
    public const val TAPER_SHARE: Double = 0.7

    /** A session within this share of a profile's baseline is that charger. */
    public const val MATCH_TOLERANCE: Double = 0.25

    /** A profile's baseline is over this many of its latest sessions. */
    public const val BASELINE_SESSIONS: Int = 10

    private const val TAPER_SEARCH_FROM = 50
    private const val EARLY_SHARE_DIVISOR = 3
    private const val MIN_TAPER_POINTS = 3
    private const val HOT_DECI_C = 380
    private const val NW_PER_W = 1e9
    private const val MAX_SAMPLE_SPAN_NS = 120_000_000_000L
    private const val NS_PER_MIN = 60_000_000_000L
}

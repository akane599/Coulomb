// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/** One continuous charge or discharge a capacity estimate may come from (SPEC 6.3). */
public data class CapacityWindow(
    val charging: Boolean,
    val deltaSocPct: Double,
    val deltaChargeUah: Long,
    val minTempDeciC: Int,
    val maxTempDeciC: Int,
    val meanTempDeciC: Int,
    /** False when the window spans a reboot. */
    val sameBoot: Boolean,
    val counterReliable: Boolean,
)

/** Why a window gives no capacity estimate. */
public enum class CapacityRejection {
    SOC_TOO_SMALL,
    TEMPERATURE,
    REBOOT,
    COUNTER,
    NO_CHARGE,

    /** The charge or the level moved against the window's direction: the counter cannot be right. */
    DIRECTION,
}

/** One stored estimate as the estimator reads it back. */
public data class CapacityReading(
    val capacityMah: Double,
    /** ΔSoC × temperature factor, without recency. */
    val staticWeight: Double,
    val ageDays: Double,
)

/** SPEC 6.3: the capacity estimate with its 95% interval, rounded for display. */
public data class CapacityEstimate(
    val estimateMah: Double,
    val ciLowMah: Double,
    val ciHighMah: Double,
    /** Estimate over design capacity, in percent; null without a design capacity. */
    val healthPct: Double?,
    val windows: Int,
    /** 0 to 1 (SPEC 6.7): the number of windows and the interval's width. */
    val quality: Double,
)

/** SPEC 6.3 capacity and state of health. All results are Estimated. */
public object Capacity {
    public const val MIN_CHARGE_SOC: Double = 40.0
    public const val MIN_DISCHARGE_SOC: Double = 30.0
    public const val MAX_WINDOWS: Int = 10
    public const val BOOTSTRAP_RESAMPLES: Int = 1_000
    public const val HALF_LIFE_DAYS: Double = 60.0

    private const val MIN_TEMP_DECI_C = 150
    private const val MAX_TEMP_DECI_C = 400
    private const val FLAT_LOW_DECI_C = 200
    private const val FLAT_HIGH_DECI_C = 300
    private const val EDGE_FACTOR = 0.5
    private const val ROUND_MAH = 10.0
    private const val MIN_INTERVAL_SHARE = 0.01
    private const val LOW_QUANTILE = 0.025
    private const val HIGH_QUANTILE = 0.975
    private const val FULL_QUALITY_WINDOWS = 3.0
    private const val ZERO_QUALITY_SHARE = 0.10
    private const val TIE_TOLERANCE = 1e-9

    /** Null when [window] qualifies, else the first reason it does not. */
    public fun rejection(window: CapacityWindow): CapacityRejection? =
        when {
            abs(window.deltaSocPct) < if (window.charging) MIN_CHARGE_SOC else MIN_DISCHARGE_SOC -> CapacityRejection.SOC_TOO_SMALL
            window.minTempDeciC < MIN_TEMP_DECI_C || window.maxTempDeciC > MAX_TEMP_DECI_C -> CapacityRejection.TEMPERATURE
            !window.sameBoot -> CapacityRejection.REBOOT
            !window.counterReliable -> CapacityRejection.COUNTER
            window.deltaChargeUah == 0L -> CapacityRejection.NO_CHARGE
            wrongDirection(window) -> CapacityRejection.DIRECTION
            else -> null
        }

    /** Charging must add charge and level; discharging must take both away. */
    private fun wrongDirection(window: CapacityWindow): Boolean {
        val way = if (window.charging) 1 else -1
        return window.deltaChargeUah * way < 0 || window.deltaSocPct * way < 0
    }

    /** `C_i = ΔQ / (ΔSoC / 100)` in mAh, for a qualifying [window]. */
    public fun estimateMah(window: CapacityWindow): Double = abs(window.deltaChargeUah / UAH_PER_MAH) / (abs(window.deltaSocPct) / PERCENT)

    /** ΔSoC × temperature factor: 1.0 at 20–30 °C, falling linearly to 0.5 at 15 and 40 °C. */
    public fun staticWeight(window: CapacityWindow): Double = abs(window.deltaSocPct) * temperatureFactor(window.meanTempDeciC)

    public fun temperatureFactor(tempDeciC: Int): Double =
        when {
            tempDeciC in FLAT_LOW_DECI_C..FLAT_HIGH_DECI_C -> 1.0
            tempDeciC < FLAT_LOW_DECI_C -> edge(tempDeciC, MIN_TEMP_DECI_C, FLAT_LOW_DECI_C)
            else -> edge(tempDeciC, MAX_TEMP_DECI_C, FLAT_HIGH_DECI_C)
        }

    /** Recency: the weight halves every [HALF_LIFE_DAYS]. */
    public fun recency(ageDays: Double): Double = 0.5.pow(max(ageDays, 0.0) / HALF_LIFE_DAYS)

    /**
     * The weighted median of the latest [MAX_WINDOWS] [readings] (newest first), with a 95%
     * interval from [BOOTSTRAP_RESAMPLES] resamples. The resampling is seeded from the windows'
     * capacities and static weights, not their ages, so the same windows give the same interval
     * on every screen opening. Capacity is rounded to 10 mAh; the interval is widened outward to
     * 10 mAh steps after rounding, and never narrower than ±1% of [designMah] (of the estimate when
     * there is no design capacity) around the rounded value.
     */
    public fun estimate(
        readings: List<CapacityReading>,
        designMah: Double?,
    ): CapacityEstimate? {
        val used = readings.take(MAX_WINDOWS)
        if (used.isEmpty()) return null
        val values = used.map { it.capacityMah }
        val weights = used.map { it.staticWeight * recency(it.ageDays) }
        if (weights.sum() <= 0) return null
        val median = weightedMedian(values, weights)
        val random = Random(listOf(values, used.map { it.staticWeight }).hashCode())
        val medians =
            List(BOOTSTRAP_RESAMPLES) {
                val picks = List(used.size) { random.nextInt(used.size) }
                val pickedWeights = picks.map { weights[it] }
                if (pickedWeights.sum() <= 0) median else weightedMedian(picks.map { values[it] }, pickedWeights)
            }.sorted()
        val rounded = round10(median)
        val design = designMah?.takeIf { it > 0 }
        val floor = (design ?: rounded) * MIN_INTERVAL_SHARE
        val low = floor10(minOf(quantile(medians, LOW_QUANTILE), rounded - floor))
        val high = ceil10(maxOf(quantile(medians, HIGH_QUANTILE), rounded + floor))
        return CapacityEstimate(
            estimateMah = rounded,
            ciLowMah = low,
            ciHighMah = high,
            healthPct = design?.let { PERCENT * rounded / it },
            windows = used.size,
            quality = quality(used.size, rounded, low, high),
        )
    }

    /**
     * Windows count up to [FULL_QUALITY_WINDOWS]; the interval's half-width costs quality linearly,
     * reaching 0 at [ZERO_QUALITY_SHARE] of the estimate.
     */
    private fun quality(
        windows: Int,
        estimate: Double,
        low: Double,
        high: Double,
    ): Double {
        if (estimate <= 0) return 0.0
        val count = min(1.0, windows.toDouble() / FULL_QUALITY_WINDOWS)
        val width = ((high - low) / 2 / estimate / ZERO_QUALITY_SHARE).coerceIn(0.0, 1.0)
        return count * (1 - width)
    }

    /**
     * The value at which the cumulative weight reaches half the total, in value order; the midpoint
     * of two values when the weight before the second is exactly half.
     */
    public fun weightedMedian(
        values: List<Double>,
        weights: List<Double>,
    ): Double {
        require(values.size == weights.size && values.isNotEmpty())
        val order = values.indices.sortedBy { values[it] }
        val total = weights.sum()
        val half = total / 2
        var sum = 0.0
        for ((position, i) in order.withIndex()) {
            sum += weights[i]
            if (abs(sum - half) <= total * TIE_TOLERANCE && position < order.lastIndex) {
                return (values[i] + values[order[position + 1]]) / 2
            }
            if (sum >= half) return values[i]
        }
        return values[order.last()]
    }

    private fun quantile(
        sorted: List<Double>,
        q: Double,
    ): Double = sorted[(ceil(q * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]

    private fun edge(
        tempDeciC: Int,
        outer: Int,
        inner: Int,
    ): Double {
        val share = (tempDeciC - outer).toDouble() / (inner - outer)
        return (EDGE_FACTOR + (1 - EDGE_FACTOR) * share).coerceIn(EDGE_FACTOR, 1.0)
    }

    private fun round10(mah: Double): Double = (mah / ROUND_MAH).roundToLong() * ROUND_MAH

    private fun floor10(mah: Double): Double = floor(mah / ROUND_MAH) * ROUND_MAH

    private fun ceil10(mah: Double): Double = ceil(mah / ROUND_MAH) * ROUND_MAH
}

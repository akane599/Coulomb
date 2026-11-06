// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import kotlin.math.abs
import kotlin.math.min

/**
 * One battery reading as the sampler took it (S1, S2), before calibration. [chargeUah] is null when
 * the device does not report the charge counter.
 */
public data class RawReading(
    val elapsedNs: Long,
    val currentRaw: Long,
    val chargeUah: Long?,
    val voltageMv: Int,
    val levelPct: Int,
    val plugged: Boolean,
    val screenOn: Boolean,
)

/** Whether `CHARGE_COUNTER` can be trusted (SPEC 6.1), and why not. */
public enum class ChargeCounterVerdict {
    RELIABLE,

    /** No reading had a charge counter. */
    UNSUPPORTED,

    /** It stayed the same for more than 15 minutes while the level changed. */
    STUCK,

    /** It moves in steps coarser than 1 mAh. */
    COARSE,

    /** Too few readings to tell. */
    UNKNOWN,
}

/** `I_true = sign × scale × I_raw`, in µA, positive while charging (SPEC 6.1). */
public data class CurrentCalibration(
    val sign: Int,
    val scale: Int,
    val chargeCounter: ChargeCounterVerdict,
    /** 0 to 1 (SPEC 6.7): how well integrated current matched the charge counter. */
    val quality: Double,
) {
    public fun currentUa(raw: Long): Long = sign * scale * raw
}

/**
 * SPEC 6.1: the sign, scale and charge-counter checks that turn raw `CURRENT_NOW` readings into
 * calibrated current. Readings are in time order and from one boot. Pure functions; the collector
 * stores the result with the build fingerprint and runs them again after an OS update.
 */
public object Calibration {
    /** Readings that must agree before a sign is chosen. */
    public const val SIGN_SAMPLES: Int = 20

    /** The shortest window the scale is chosen over. */
    public const val SCALE_WINDOW_NS: Long = 10 * 60 * NS_PER_S

    private const val STUCK_NS = 15 * 60 * NS_PER_S
    private const val MAX_QUANTUM_UAH = 1_000L
    private const val MIN_COUNTER_STEPS = 5
    private const val MIN_POWER_W = 0.05
    private const val MAX_POWER_W = 25.0
    private val SCALES = listOf(1, 1_000)

    /**
     * The sign that makes current negative while unplugged with the screen on, and positive while
     * plugged with the level rising, once the last [SIGN_SAMPLES] readings that show either agree.
     * Null until then. A reading of zero says nothing.
     */
    public fun sign(readings: List<RawReading>): Int? {
        val implied = mutableListOf<Int>()
        var lowestPluggedLevel: Int? = null
        for (r in readings) {
            lowestPluggedLevel = if (r.plugged) min(lowestPluggedLevel ?: r.levelPct, r.levelPct) else null
            val raw = r.currentRaw.sign()
            if (raw == 0) continue
            when {
                !r.plugged && r.screenOn -> implied += -raw
                r.plugged && r.levelPct > (lowestPluggedLevel ?: r.levelPct) -> implied += raw
            }
        }
        val last = implied.takeLast(SIGN_SAMPLES)
        return last.firstOrNull()?.takeIf { last.size == SIGN_SAMPLES && last.all { s -> s == it } }
    }

    /**
     * Whether the charge counter can be trusted over [readings]: present, never stuck for more than
     * 15 minutes while the level changed, and moving in steps of at most 1 mAh. The step is the
     * greatest common divisor of the counter's changes, so real drain between readings does not
     * look like coarse steps.
     */
    public fun chargeCounter(readings: List<RawReading>): ChargeCounterVerdict {
        val counted = readings.mapNotNull { r -> r.chargeUah?.let { r to it } }
        val steps = counted.zipWithNext { a, b -> abs(b.second - a.second) }.filter { it > 0 }
        return when {
            counted.isEmpty() -> if (readings.isEmpty()) ChargeCounterVerdict.UNKNOWN else ChargeCounterVerdict.UNSUPPORTED
            isStuck(counted) -> ChargeCounterVerdict.STUCK
            steps.size < MIN_COUNTER_STEPS -> ChargeCounterVerdict.UNKNOWN
            steps.reduce(::gcd) > MAX_QUANTUM_UAH -> ChargeCounterVerdict.COARSE
            else -> ChargeCounterVerdict.RELIABLE
        }
    }

    /** The counter kept one value for more than 15 minutes while the level changed. */
    private fun isStuck(counted: List<Pair<RawReading, Long>>): Boolean {
        var since = counted.first()
        for (next in counted.drop(1)) {
            val (r, charge) = next
            if (charge != since.second) {
                since = next
            } else if (r.levelPct != since.first.levelPct && r.elapsedNs - since.first.elapsedNs > STUCK_NS) {
                return true
            }
        }
        return false
    }

    /**
     * The scale, µA per raw unit, for [readings] with [sign] (SPEC 6.1). With a reliable charge
     * counter over at least [SCALE_WINDOW_NS], it is the one whose integrated current comes closest
     * to the counter's change; otherwise, the only one whose mean power falls between 0.05 W and
     * 25 W. Either way the chosen scale must pass that power check. Null when no scale can be chosen.
     */
    public fun calibrate(
        readings: List<RawReading>,
        sign: Int,
    ): CurrentCalibration? {
        if (readings.size < 2) return null
        val counter = chargeCounter(readings)
        val plausible = SCALES.filter { plausiblePower(readings, sign, it) }
        val deltaUah = comparableCharge(readings, counter)
        if (deltaUah != null) {
            val errors = SCALES.associateWith { abs(integratedUah(readings, sign, it) - deltaUah) / abs(deltaUah.toDouble()) }
            val best = errors.minBy { it.value }
            if (best.key !in plausible) return null
            return CurrentCalibration(sign, best.key, counter, quality = (1 - best.value).coerceIn(0.0, 1.0))
        }
        // Without a counter to compare with, the power check alone picks the scale, at lower quality.
        return plausible.singleOrNull()?.let { CurrentCalibration(sign, it, counter, quality = FALLBACK_QUALITY) }
    }

    /** The counter's change over [readings] when it can choose the scale: reliable, moved, over a long enough window. */
    private fun comparableCharge(
        readings: List<RawReading>,
        counter: ChargeCounterVerdict,
    ): Long? {
        val span = readings.last().elapsedNs - readings.first().elapsedNs
        if (counter != ChargeCounterVerdict.RELIABLE || span < SCALE_WINDOW_NS) return null
        return readings
            .last()
            .chargeUah
            ?.let { last -> readings.first().chargeUah?.let { last - it } }
            ?.takeIf { it != 0L }
    }

    /** Charge in µAh from integrating calibrated current over time (trapezoids). */
    public fun integratedUah(
        readings: List<RawReading>,
        sign: Int,
        scale: Int,
    ): Double =
        readings
            .zipWithNext { a, b ->
                val meanUa = sign * scale * (a.currentRaw + b.currentRaw) / 2.0
                meanUa * (b.elapsedNs - a.elapsedNs) / NS_PER_S / SECONDS_PER_HOUR
            }.sum()

    private fun plausiblePower(
        readings: List<RawReading>,
        sign: Int,
        scale: Int,
    ): Boolean {
        val watts = readings.map { abs(sign * scale * it.currentRaw.toDouble()) / UA_PER_A * it.voltageMv / MV_PER_V }.average()
        return watts in MIN_POWER_W..MAX_POWER_W
    }

    private fun gcd(
        a: Long,
        b: Long,
    ): Long = if (b == 0L) a else gcd(b, a % b)

    private fun Long.sign(): Int = java.lang.Long.signum(this)

    private const val FALLBACK_QUALITY = 0.4
}

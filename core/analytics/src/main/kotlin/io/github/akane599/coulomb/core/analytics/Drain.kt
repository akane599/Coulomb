// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import kotlin.math.abs

/** The battery at one end of a window: a snapshot's or a sample's light data. */
public data class ChargePoint(
    val bootSeq: Int,
    val elapsedNs: Long,
    val levelPct: Int,
    val chargeUah: Long?,
)

/** SPEC 6.2: a window's drain, or why it shows "Collecting". */
public sealed interface DrainRate {
    /** Too short or too small to show a rate: under 15 minutes and under 20 mAh. */
    public data object Collecting : DrainRate

    /** The ends are in different boots: elapsed time does not span a reboot (SPEC 7.8). */
    public data object AcrossReboot : DrainRate

    public data class Rate(
        /** Mean current, negative while discharging; null when only levels are known. */
        val avgCurrentMa: Double?,
        /** Against the estimated real capacity; null until there is an estimate or a charge. */
        val pctPerHourOfCapacity: Double?,
        /** From the level change alone. */
        val pctPerHourOfLevel: Double,
        /** Which of the two the UI shows: the level-based one while calibration is incomplete. */
        val shown: Shown,
        val chargeSource: ChargeSource,
        /** Of [avgCurrentMa]: Measured only from the charge counter. */
        val currentProvenance: Provenance,
        /**
         * Of the shown %/h. Always Estimated: one rate divides by the estimated capacity, the other
         * comes from the fuel gauge's level (SPEC 6.7).
         */
        val rateProvenance: Provenance,
        /** 0 to 1 (SPEC 6.7): the charge source times how finely the change was resolved. */
        val quality: Double,
        val hours: Double,
    ) : DrainRate

    public enum class Shown { CAPACITY, LEVEL }
}

/** SPEC 6.2 drain rates. */
public object Drain {
    public const val MIN_WINDOW_NS: Long = 15 * 60 * NS_PER_S
    public const val MIN_CHARGE_UAH: Long = 20_000

    /** The charge counter's resolution, and a level step, as the smallest change each can show. */
    private const val COUNTER_RESOLUTION_UAH = 1_000.0
    private const val LEVEL_RESOLUTION_PCT = 1.0
    private val SOURCE_QUALITY =
        mapOf(
            ChargeSource.CHARGE_COUNTER to 1.0,
            ChargeSource.INTEGRATED_CURRENT to 0.8,
            ChargeSource.LEVEL_DELTA to 0.5,
        )

    /**
     * The drain from [a] to [b]: ΔQ / Δt from the charge counter when [counterReliable], else from
     * [integratedUah] (integrated current while sampling), else from the level alone. A charge
     * that is not the counter's lowers the provenance to Estimated (SPEC 6.1).
     */
    public fun rate(
        a: ChargePoint,
        b: ChargePoint,
        counterReliable: Boolean,
        calibrated: Boolean,
        capacityMah: Double?,
        integratedUah: Double? = null,
    ): DrainRate {
        if (a.bootSeq != b.bootSeq) return DrainRate.AcrossReboot
        val dtNs = b.elapsedNs - a.elapsedNs
        if (dtNs <= 0) return DrainRate.Collecting
        val (deltaUah, source) = charge(a, b, counterReliable, calibrated, integratedUah)
        if (dtNs < MIN_WINDOW_NS && abs(deltaUah ?: 0.0) < MIN_CHARGE_UAH) return DrainRate.Collecting
        val hours = dtNs.toDouble() / NS_PER_S / SECONDS_PER_HOUR
        val avgMa = deltaUah?.let { it / UAH_PER_MAH / hours }
        val ofCapacity = avgMa?.let { ma -> capacityMah?.takeIf { it > 0 }?.let { PERCENT * abs(ma) / it } }
        val shown = if (calibrated && ofCapacity != null) DrainRate.Shown.CAPACITY else DrainRate.Shown.LEVEL
        val levelDelta = abs(b.levelPct - a.levelPct).toDouble()
        return DrainRate.Rate(
            avgCurrentMa = avgMa,
            pctPerHourOfCapacity = ofCapacity,
            pctPerHourOfLevel = levelDelta / hours,
            shown = shown,
            chargeSource = source,
            currentProvenance = if (source == ChargeSource.CHARGE_COUNTER) Provenance.MEASURED else Provenance.ESTIMATED,
            rateProvenance = Provenance.ESTIMATED,
            quality = quality(shown, source, deltaUah, levelDelta),
            hours = hours,
        )
    }

    /**
     * The shown rate's quality: its charge source (the level when the level-based rate is shown)
     * times how finely the change was resolved, one counter step or one level step against it.
     */
    private fun quality(
        shown: DrainRate.Shown,
        source: ChargeSource,
        deltaUah: Double?,
        levelDelta: Double,
    ): Double =
        if (shown == DrainRate.Shown.CAPACITY && deltaUah != null) {
            val step = if (source == ChargeSource.CHARGE_COUNTER) COUNTER_RESOLUTION_UAH else 0.0
            SOURCE_QUALITY.getValue(source) * resolution(abs(deltaUah), step)
        } else {
            SOURCE_QUALITY.getValue(ChargeSource.LEVEL_DELTA) * resolution(levelDelta, LEVEL_RESOLUTION_PCT)
        }

    /** 1 minus the share of [change] that one step of [step] would be; 0 when nothing changed. */
    private fun resolution(
        change: Double,
        step: Double,
    ): Double = if (change <= 0) 0.0 else (1 - step / change).coerceIn(0.0, 1.0)

    /** ΔQ in µAh and where it came from: the counter, else integrated current, else nothing. */
    private fun charge(
        a: ChargePoint,
        b: ChargePoint,
        counterReliable: Boolean,
        calibrated: Boolean,
        integratedUah: Double?,
    ): Pair<Double?, ChargeSource> {
        val qa = a.chargeUah?.takeIf { it > 0 }
        val qb = b.chargeUah?.takeIf { it > 0 }
        val counter = if (counterReliable && qa != null && qb != null) (qb - qa).toDouble() else null
        return when {
            counter != null -> counter to ChargeSource.CHARGE_COUNTER
            integratedUah != null && calibrated -> integratedUah to ChargeSource.INTEGRATED_CURRENT
            else -> null to ChargeSource.LEVEL_DELTA
        }
    }
}

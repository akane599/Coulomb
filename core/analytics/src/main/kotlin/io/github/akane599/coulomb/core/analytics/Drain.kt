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
        val provenance: Provenance,
        val hours: Double,
    ) : DrainRate

    public enum class Shown { CAPACITY, LEVEL }
}

/** SPEC 6.2 drain rates. */
public object Drain {
    public const val MIN_WINDOW_NS: Long = 15 * 60 * NS_PER_S
    public const val MIN_CHARGE_UAH: Long = 20_000

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
        return DrainRate.Rate(
            avgCurrentMa = avgMa,
            pctPerHourOfCapacity = ofCapacity,
            pctPerHourOfLevel = abs(b.levelPct - a.levelPct) / hours,
            shown = if (calibrated && ofCapacity != null) DrainRate.Shown.CAPACITY else DrainRate.Shown.LEVEL,
            chargeSource = source,
            provenance = if (source == ChargeSource.CHARGE_COUNTER) Provenance.MEASURED else Provenance.ESTIMATED,
            hours = hours,
        )
    }

    /** ΔQ in µAh and where it came from: the counter, else integrated current, else nothing. */
    private fun charge(
        a: ChargePoint,
        b: ChargePoint,
        counterReliable: Boolean,
        calibrated: Boolean,
        integratedUah: Double?,
    ): Pair<Double?, ChargeSource> {
        val counter = if (counterReliable) b.chargeUah?.let { qb -> a.chargeUah?.let { (qb - it).toDouble() } } else null
        return when {
            counter != null -> counter to ChargeSource.CHARGE_COUNTER
            integratedUah != null && calibrated -> integratedUah to ChargeSource.INTEGRATED_CURRENT
            else -> null to ChargeSource.LEVEL_DELTA
        }
    }
}

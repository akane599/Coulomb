// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.analytics.ChargePhase
import io.github.akane599.coulomb.core.analytics.ChargeTime

/**
 * The charge in progress, as the Charging screen shows it (SPEC 10.9, ADR-0046). Power is Derived
 * from the calibrated current and the voltage; current, voltage and temperature are Measured; the
 * phase, the charger and the times are Estimated.
 */
public data class ChargingNow(
    val levelPct: Int,
    /** `BatteryManager.BATTERY_PLUGGED_*` of the latest sample. */
    val plugType: Int,
    /** Into the battery, negative while the phone draws on it (DRAWING); null before calibration. */
    val watts: Double?,
    /** Positive into the battery; null before calibration. */
    val currentMa: Double?,
    val voltageMv: Int,
    val tempDeciC: Int,
    /** Since the charge began; null with one sample. */
    val tempRiseDeciC: Int?,
    /** Null without a calibrated current. */
    val phase: ChargePhase?,
    val charger: LiveCharger,
    /** The owner's charge target (SPEC 14.4). */
    val targetPct: Int,
    /** The level has fallen below this charge's highest, so no time to a level is given (ADR-0046 §3). */
    val belowHighest: Boolean,
    /** Null at or above the target, or with too few past charges to it. */
    val toTarget: ChargeTime?,
    /** Null at 100%, or with too few past charges to full. */
    val toFull: ChargeTime?,
    val startWallMs: Long,
    val startLevelPct: Int,
    /** The session so far, a point a sample; the chart thins it. */
    val curve: List<ChargeCurvePoint>,
    val wallMs: Long,
)

/** Which charger this is, as far as the charge so far can tell (ADR-0043, ADR-0046 §5). */
public sealed interface LiveCharger {
    public val plugType: Int

    /** A stored profile: the same plug, full speed within 25% of its baseline. */
    public data class Known(
        val charger: Charger,
        /** This charge's full speed against the profile's baseline: 1.0 is the same. */
        val shareOfBaseline: Double,
    ) : LiveCharger {
        override val plugType: Int get() = charger.plugType
    }

    /** Full speed is known and matches no profile: the profile is stored when the charge ends. */
    public data class New(
        override val plugType: Int,
        val fullSpeedW: Double,
    ) : LiveCharger

    /** Below 50%, with too few calibrated readings yet to know full speed. */
    public data class Learning(
        override val plugType: Int,
    ) : LiveCharger

    /**
     * At 50% or above without enough readings below it, as a charge that started high: this charge
     * cannot say its full speed (ADR-0043 gate review M3).
     */
    public data class StartedHigh(
        override val plugType: Int,
    ) : LiveCharger
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * SPEC 8 `BatterySample`. `elapsedNs` restarts at every boot, so the key is the boot with it
 * (ADR-0015); rows order by the same pair (SPEC 7.8).
 */
@Entity(tableName = "battery_sample", primaryKeys = ["bootSeq", "elapsedNs"], indices = [Index("wallMs")])
public data class BatterySampleEntity(
    val bootSeq: Int,
    val elapsedNs: Long,
    val wallMs: Long,
    val levelPct: Int,
    val chargeUah: Long?,
    /** Calibrated current, positive while charging (SPEC 6.1); null before calibration. */
    val currentUa: Long?,
    /** `CURRENT_NOW` as read, kept so a later calibration can be applied again. */
    val currentRaw: Long?,
    val voltageMv: Int,
    val tempDeciC: Int,
    /** `BatteryManager.BATTERY_PLUGGED_*`, 0 when unplugged. */
    val plugType: Int,
    /** `BatteryManager.BATTERY_STATUS_*`. */
    val status: Int,
    val screenOn: Boolean,
    /** 0 off, 1 light Doze, 2 deep Doze. */
    val dozeState: Int,
    /** `PowerManager.THERMAL_STATUS_*`. */
    val thermalStatus: Int,
)

/**
 * Compacted samples (SPEC 7.7): raw samples become one-minute rows after 14 days, and those become
 * hourly rows after 180 days. A bucket never spans a boot. Not in the SPEC 8 list; added by
 * ADR-0015.
 */
@Entity(
    tableName = "sample_aggregate",
    primaryKeys = ["resolutionS", "bootSeq", "startElapsedNs"],
    indices = [Index("resolutionS", "startWallMs")],
)
public data class SampleAggregateEntity(
    /** 60 or 3600. */
    val resolutionS: Int,
    val bootSeq: Int,
    /** A multiple of the resolution, counted from the boot. */
    val startElapsedNs: Long,
    val startWallMs: Long,
    val sampleCount: Int,
    val levelFirstPct: Int,
    val levelLastPct: Int,
    val chargeFirstUah: Long?,
    val chargeLastUah: Long?,
    val currentMinUa: Long?,
    val currentMeanUa: Long?,
    val currentMaxUa: Long?,
    val voltageMeanMv: Int,
    val tempMinDeciC: Int,
    val tempMeanDeciC: Int,
    val tempMaxDeciC: Int,
    /** Share of the samples taken with the screen on, 0 to 1. */
    val screenOnShare: Double,
    /** `plugType` of the last sample. */
    val plugType: Int,
)

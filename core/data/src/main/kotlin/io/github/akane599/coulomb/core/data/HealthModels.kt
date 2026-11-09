// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.analytics.CapacityEstimate
import io.github.akane599.coulomb.core.analytics.StressLevel
import io.github.akane599.coulomb.core.model.DesignCapacity
import io.github.akane599.coulomb.core.model.dumpsys.OemHealthValue
import java.time.LocalDate

/** Everything the Health screen shows from the database (SPEC 10.8, ADR-0044). */
public data class HealthReport(
    /** SPEC 6.3's estimate from the stored windows as of the read; null before the first one. */
    val capacity: CapacityEstimate?,
    val design: DesignCapacity?,
    val change: CapacityChange?,
    val crossCheck: CrossCheck,
    val cycles: Cycles,
    val trend: CapacityTrend,
    /** Null without a closed charge in [ChargeHabits.DAYS] days. */
    val habits: ChargeHabits?,
    /** The latest closed charge sessions, newest first, at most [HealthRepository.RECENT_SESSIONS]. */
    val sessions: List<ChargeSessionRow>,
    /** Whether more closed charge sessions exist than [sessions] holds. */
    val moreSessions: Boolean,
    /** Newest seen first. */
    val chargers: List<Charger>,
)

/**
 * The estimate now against the first settled one (ADR-0044 §1, gate review): [deltaMah] is now minus
 * then, and [marginMah] how far the two estimates could be off together, √(h₁² + h₂²) of their
 * half-widths, to say whether the change is more than that.
 */
public data class CapacityChange(
    val firstDay: LocalDate,
    val deltaMah: Double,
    val marginMah: Double,
)

/** The system's and the OEM's health values beside Coulomb's (SPEC 6.3), each from its latest day. */
public data class CrossCheck(
    val systemSohPct: Int?,
    val oemHealth: List<OemHealthValue>,
)

/**
 * SPEC 10.8 item 2 (ADR-0044 §3). [platformCount] is the latest `EXTRA_CYCLE_COUNT` stored. With
 * two days of counts, [coulombCount] and [platformCounted] cover the same days from [since];
 * otherwise [coulombCount] is the whole running sum and [platformCounted] is null.
 */
public data class Cycles(
    val platformCount: Int?,
    val coulombCount: Double,
    val since: LocalDate?,
    val platformCounted: Int?,
)

/** One capacity window's reading (a cross on the chart). */
public data class EstimatePoint(
    val wallMs: Long,
    val mah: Double,
)

/** One day's estimate with its 95% interval (the line and its band). */
public data class EstimateDay(
    val day: LocalDate,
    val mah: Double,
    val lowMah: Double,
    val highMah: Double,
)

public data class CapacityTrend(
    val points: List<EstimatePoint>,
    val days: List<EstimateDay>,
)

/** SPEC 10.8 item 5 over the last [DAYS] days (ADR-0044 §2). */
public data class ChargeHabits(
    /** Closed charge sessions in the window. */
    val sessions: Int,
    /** The mean of 100 minus each charge's start level. */
    val depthPct: Double,
    /** Time at 80% or more per watched day, in minutes; null with less than a day watched. */
    val highMinutesPerDay: Double?,
    /** The time-weighted mean of the charges' mean temperatures, in 0.1 °C; null without one. */
    val chargeTempDeciC: Double?,
    val stress: Map<StressLevel, Int>,
) {
    public companion object {
        public const val DAYS: Int = 30
    }
}

/** A closed charge session in the list (SPEC 10.8 item 6). */
public data class ChargeSessionRow(
    val id: Long,
    val startWallMs: Long,
    val endWallMs: Long,
    val fromPct: Int,
    val toPct: Int,
    val cycles: Double?,
    val stress: StressLevel?,
    /** 0 to 1 (SPEC 6.7). */
    val quality: Double,
)

/** A charger profile (SPEC 10.8 item 7); an empty [name] is not named yet. */
public data class Charger(
    val id: Long,
    val name: String,
    /** `BatteryManager.BATTERY_PLUGGED_*`. */
    val plugType: Int,
    val baselineW: Double,
    val firstSeenWallMs: Long,
    val lastSeenWallMs: Long,
)

/** One point of a charge's curve: level, power into the battery and temperature. */
public data class ChargeCurvePoint(
    val wallMs: Long,
    val levelPct: Int,
    /** Null before calibration, when the current's scale is not known. */
    val watts: Double?,
    val tempDeciC: Int,
)

/** A charge session with its figures and curve (SPEC 10.8 item 6, ADR-0043). */
public data class ChargeSessionDetail(
    val row: ChargeSessionRow,
    /** Null when the session has no charge detail, as when it closed before ADR-0043. */
    val plugType: Int?,
    val charger: Charger?,
    val peakW: Double?,
    val fullSpeedW: Double?,
    val ccToCvLevel: Int?,
    val minutesAtOrAbove80: Int?,
    val minutesAbove38C: Int?,
    val meanTempDeciC: Int?,
    val maxTempDeciC: Int,
    val curve: List<ChargeCurvePoint>,
)

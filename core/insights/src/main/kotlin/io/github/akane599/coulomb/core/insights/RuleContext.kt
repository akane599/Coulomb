// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

/** A stretch of wall-clock time an input covers; evidence shows it (SPEC 9.4). */
public data class Span(
    val fromWallMs: Long,
    val toWallMs: Long,
)

/**
 * What a rule reads (SPEC 9.1 "input window type"). The collector says which of these changed, and
 * only the rules reading one of them run again (SPEC 7.5).
 */
public enum class RuleInput {
    /** Per-app and device counters over the last day. */
    DAY_USE,

    /** Wakelock tags' hold times per session. */
    SESSION_WAKELOCKS,

    /** Closed screen-off sessions on battery. */
    SCREEN_OFF_SESSIONS,

    /** Discharge windows with measured and modeled charge. */
    DISCHARGE_WINDOWS,

    /** Closed charge sessions and their temperature samples. */
    CHARGE_SESSIONS,

    /** Battery temperature readings. */
    TEMPERATURE,

    /** Daily capacity estimates. */
    HEALTH,

    /** Standby buckets and last use per app. */
    APP_STANDBY,

    /** Per-app modeled charge by day, with the app's last update. */
    APP_TRENDS,
}

/**
 * One app's counters over [DayUse.span]. Times are milliseconds. The screen-off figures count only
 * windows in which the phone was on battery with the screen off throughout. [abandonedJobs] is null
 * where the source does not give stop reasons.
 */
public data class AppDayUse(
    val uid: Int,
    val partialWakelockMs: Long = 0,
    val alarmWakeups: Long = 0,
    val gnssScreenOffMs: Long = 0,
    val sensorScreenOffMs: Long = 0,
    val jobCount: Long = 0,
    val abandonedJobs: Long? = null,
)

/** Time in each mobile signal bin, weakest first, and the modem's share of modeled drain (0 to 1). */
public data class SignalUse(
    val binMs: List<Long>,
    val modemShare: Double?,
)

/** Coulomb's own modeled charge over the day, against the battery's capacity. */
public data class SelfCost(
    val ownMah: Double,
    val capacityMah: Double,
)

/**
 * The last day's counters. [screenOffMs] is on-battery screen-off time in [span]; the scan times
 * are device totals, not sums of per-app timers, which overlap.
 */
public data class DayUse(
    val span: Span,
    val screenOffMs: Long,
    val apps: List<AppDayUse> = emptyList(),
    val wifiScanMs: Long = 0,
    val bleScanMs: Long = 0,
    val signal: SignalUse? = null,
    val self: SelfCost? = null,
) {
    val durationMs: Long get() = span.toWallMs - span.fromWallMs
}

/** How long one wakelock tag was held within one session. */
public data class TagHold(
    val tag: String,
    val uid: Int?,
    val heldMs: Long,
    val span: Span,
    val windowId: Long? = null,
)

/**
 * A closed screen-off session on battery. [durationMs] comes from the elapsed clock, not from the
 * span. A null field is one the phone's sources could not give for this session.
 */
public data class ScreenOffSession(
    val id: Long,
    val span: Span,
    val durationMs: Long,
    val awakeRatio: Double? = null,
    val ratePctPerHour: Double? = null,
    /** Whole level points lost over the session; a rate from one or two points is mostly rounding. */
    val levelDropPct: Int? = null,
    val deepIdleReached: Boolean? = null,
    val windowId: Long? = null,
)

/** A discharge window's measured charge and the part no app or component accounts for. */
public data class DischargeWindow(
    val windowId: Long,
    val span: Span,
    val durationMs: Long,
    val measuredMah: Double,
    val unattributedMah: Double,
)

/** One battery temperature reading. [charging] is whether the phone was taking charge then. */
public data class TempSample(
    val wallMs: Long,
    val tempC: Double,
    val charging: Boolean = false,
    /** The app using the most charge around the reading, where known. */
    val topUid: Int? = null,
)

/**
 * A closed charge session. [avgW] is its power before the taper and [baselineW] what the charger it
 * is compared with usually gives; [baselineName] is that charger's name, where the user gave one.
 * [protectionOn] is whether the phone's battery protection limited the charge; null where that
 * could not be read.
 */
public data class ChargeSession(
    val id: Long,
    val span: Span,
    val samples: List<TempSample> = emptyList(),
    val avgW: Double? = null,
    val baselineW: Double? = null,
    val chargerId: Long? = null,
    val baselineName: String? = null,
    val minutesAtFull: Int = 0,
    val protectionOn: Boolean? = null,
)

/** A day's settled capacity estimate as a percentage of design capacity. */
public data class HealthPoint(
    val wallMs: Long,
    val healthPct: Double,
)

/** An app's standby bucket and when it was last opened; null where the phone has no record. */
public data class AppStandby(
    val uid: Int,
    val active: Boolean,
    val lastOpenedWallMs: Long?,
)

/** An app's modeled charge on one day. */
public data class DailyMah(
    val dayStartWallMs: Long,
    val mah: Double,
)

/** An app's daily charge, oldest first, and when it was last updated. */
public data class AppTrend(
    val uid: Int,
    val updatedWallMs: Long?,
    val daily: List<DailyMah>,
)

/**
 * Everything the rules read, loaded from stored data. A part that is null or empty is one the
 * phone has no data for, and the rules reading it find nothing. Lists are oldest first.
 */
public data class RuleContext(
    val nowWallMs: Long,
    val day: DayUse? = null,
    val tagHolds: List<TagHold> = emptyList(),
    val screenOffSessions: List<ScreenOffSession> = emptyList(),
    val dischargeWindows: List<DischargeWindow> = emptyList(),
    val chargeSessions: List<ChargeSession> = emptyList(),
    val temperatures: List<TempSample> = emptyList(),
    val health: List<HealthPoint> = emptyList(),
    val standby: List<AppStandby> = emptyList(),
    val trends: List<AppTrend> = emptyList(),
)

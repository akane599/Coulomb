// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind

/**
 * SPEC 8 `Session`. A session is open while [closeReason] is null; a reboot closes every open one
 * (SPEC 7.8). The SPEC 8.1 index `(kind, startElapsedNs)` carries the boot between the two, because
 * `startElapsedNs` restarts at every boot (ADR-0015). The temperature and stress columns are kept
 * here because the samples they come from are compacted after 14 days (SPEC 6.3, 6.4).
 */
@Entity(
    tableName = "session",
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["startSnapshotId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["endSnapshotId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("kind", "bootSeq", "startElapsedNs"), Index("startSnapshotId"), Index("endSnapshotId")],
)
public data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: SessionKind,
    val bootSeq: Int,
    val startElapsedNs: Long,
    val endElapsedNs: Long?,
    val startWall: Long,
    val endWall: Long?,
    /** The heavy snapshots at the ends; null in Lite mode or when none was taken. */
    val startSnapshotId: Long?,
    val endSnapshotId: Long?,
    val startLevel: Int,
    val endLevel: Int?,
    val startChargeUah: Long?,
    val endChargeUah: Long?,
    /** Where the session's charge came from, which sets its provenance (SPEC 6.1). */
    val chargeSource: ChargeSource?,
    val avgCurrentMa: Double?,
    val peakCurrentMa: Double?,
    val minTempDeciC: Int?,
    val meanTempDeciC: Int?,
    val maxTempDeciC: Int,
    /** Share of the session the CPU was awake, 0 to 1. */
    val awakeRatio: Double?,
    /** Whether deep Doze was reached; null when it is not known. */
    val deepIdleReached: Boolean?,
    /** Time from the session's start until deep Doze was first reached. */
    val deepIdleReachedMs: Long?,
    val minutesAtOrAbove80: Int?,
    val minutesAt100: Int?,
    /** SPEC 6.4 wear stress index and cycle equivalents of this session. */
    val stressIndex: Double?,
    val cycleEquivalents: Double?,
    val closeReason: SessionCloseReason?,
    /** 0 to 1 (SPEC 6.7); below 0.5 the session's numbers show as low confidence. */
    val quality: Double,
)

/** SPEC 8 `ChargerProfile`: a charger recognised by its plug type and baseline power. */
@Entity(tableName = "charger_profile")
public data class ChargerProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val plugType: Int,
    val baselineW: Double,
    val firstSeenWall: Long,
    val lastSeenWall: Long,
    val notes: String?,
)

/** SPEC 8 `ChargeDetail`: the charge-only part of a [SessionKind.CHARGE] session. */
@Entity(
    tableName = "charge_detail",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChargerProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["chargerProfileId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("chargerProfileId")],
)
public data class ChargeDetailEntity(
    @PrimaryKey val sessionId: Long,
    val plugType: Int,
    val chargerProfileId: Long?,
    /** Null before calibration, when the current's scale is not known. */
    val peakW: Double?,
    val avgW: Double?,
    /** Level where constant current gave way to constant voltage. */
    val ccToCvLevel: Int?,
    val minutesAbove80: Int,
    val minutesAbove38C: Int,
    /** Charge limit in force, such as 80 or 85. */
    val targetLevel: Int?,
    /** The OEM's battery protection mode as it names it. */
    val protectionMode: String?,
)

/**
 * SPEC 8 `CapacityEstimate`: one capacity reading from a qualifying window of a session (SPEC 6.3).
 * [weight] is the static part (window size, temperature); recency is applied when estimates are
 * combined.
 */
@Entity(
    tableName = "capacity_estimate",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("sessionId")],
)
public data class CapacityEstimateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val capacityMah: Double,
    val weight: Double,
    /** The window the estimate came from: ΔSoC and ΔQ. */
    val deltaSocPct: Double,
    val deltaChargeMah: Double,
    val tempMeanDeciC: Int,
    val createdWall: Long,
)

/**
 * SPEC 8 `HealthSummary`: one row per local day, keyed by its epoch day so travel across time zones
 * cannot split a day. The estimate is null until the first capacity estimate exists.
 */
@Entity(tableName = "health_summary")
public data class HealthSummaryEntity(
    @PrimaryKey val epochDay: Int,
    val estimateMah: Double?,
    val ciLowMah: Double?,
    val ciHighMah: Double?,
    val healthPct: Double?,
    /**
     * `BatteryManager.BATTERY_PROPERTY_STATE_OF_HEALTH` where the device reports it
     * (core/java/android/os/BatteryManager.java, android-16.0.0_r4 L489, android-17.0.0_r1 L518).
     */
    val systemSohPct: Int?,
    /** The OEM's own health label or value, as reported. */
    val oemHealth: String?,
    val cycleCount: Int?,
    val cycleEquivalentsSum: Double,
)

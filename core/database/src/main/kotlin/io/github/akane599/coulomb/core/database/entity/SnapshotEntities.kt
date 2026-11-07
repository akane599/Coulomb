// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.ReferenceOrigin
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WakeKind

/**
 * SPEC 8 `Snapshot` (SPEC 7.2). The row stays 30 days, and for good while a session, a reference or a
 * window points to it (ADR-0021), with the battery values the light data gave, so those records'
 * coulomb deltas (SPEC 6.2) outlive the samples. Its per-UID rows are
 * cumulative counters and stay only while a window can still start at the snapshot: the latest
 * snapshot, a referenced one, or the start of an open session (ADR-0015). The blob goes after 30
 * days (SPEC 7.7). Per-snapshot tables reference `snapshot.id` with `ON DELETE CASCADE`.
 */
@Entity(tableName = "snapshot", indices = [Index("bootSeq", "elapsedNs"), Index("wallMs")])
public data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bootSeq: Int,
    val elapsedNs: Long,
    val wallMs: Long,
    val trigger: SnapshotTrigger,
    val kind: SnapshotKind,
    // The light data (S1, S2) at the snapshot; null where the read failed.
    val levelPct: Int?,
    val chargeUah: Long?,
    val currentRaw: Long?,
    val voltageMv: Int?,
    val tempDeciC: Int?,
    val plugType: Int?,
    val status: Int?,
    /**
     * Wall time the platform's statistics started counting from (`start_clock_time_ms`). It moves
     * with every wall-clock change, so it alone cannot tell a reset (ADR-0017).
     */
    val statsStartWall: Long?,
    /** Realtime the statistics have counted since they started (`total_realtime_ms`); restarts at a reset (ADR-0018). */
    val statsRealtimeMs: Long?,
    /** The history cursor after this snapshot's read: the `NEXT:` value (ADR-0014). */
    val historyCursorMs: Long?,
    /**
     * The highest `history_event.id` once this snapshot's history read was stored, or null when
     * the read failed. Every event before this snapshot has an id up to it; every later event, a
     * higher one (ADR-0015).
     */
    val historyEventId: Long?,
    /**
     * Each source's outcome as a JSON object, such as `{"S5":"ok","S10":"failed"}`. A table
     * without rows for a failed source means "not read", never "zero".
     */
    val sourceStatusJson: String,
    /** Compressed raw dumps, relative to the app's files directory. */
    val blobPath: String?,
    /** Some sources failed or were cut short; [sourceStatusJson] says which. */
    val partial: Boolean,
    /** The per-UID and device counters were deleted once no window could start here any more. */
    val countersPruned: Boolean,
    val durationMs: Long,
)

/**
 * SPEC 8 `UidPower`: one UID's energy by power component and process state. [component] and
 * [processState] keep the platform's names; [processState] is [ANY_STATE] where the source does
 * not split by process state. A component has either one [ANY_STATE] row or rows per process
 * state, never both. [DEVICE_UID] rows hold the device's total per standard component, apps
 * included (the platform's device-scope aggregate): never add them to the UID rows.
 */
@Entity(
    tableName = "uid_power",
    primaryKeys = ["snapshotId", "uid", "component", "processState"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("uid", "snapshotId")],
)
public data class UidPowerEntity(
    val snapshotId: Long,
    val uid: Int,
    val component: String,
    val processState: String,
    val mAh: Double,
    val provenance: Provenance,
) {
    public companion object {
        public const val ANY_STATE: String = "any"

        /** The device's per-component totals, apps included. */
        public const val DEVICE_UID: Int = -1
    }
}

/** SPEC 8 `WakeStat`. [uid] is null for kernel wakelocks and wakeup reasons. */
@Entity(
    tableName = "wake_stat",
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("snapshotId"), Index("uid", "snapshotId")],
)
public data class WakeStatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val snapshotId: Long,
    val uid: Int?,
    val tag: String,
    val kind: WakeKind,
    val totalMs: Long,
    val count: Int,
    val maxMs: Long?,
    val screenOffMs: Long?,
)

/** SPEC 8 `AlarmStat`: alarms of one UID since its counter started. [total] is null where only wakeups are reported (S5). */
@Entity(
    tableName = "alarm_stat",
    primaryKeys = ["snapshotId", "uid"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("uid", "snapshotId")],
)
public data class AlarmStatEntity(
    val snapshotId: Long,
    val uid: Int,
    val wakeups: Int,
    val total: Int?,
)

/** SPEC 8 `JobStat`. [abandoned] counts `timeout_abandoned` stops. Null: not reported. */
@Entity(
    tableName = "job_stat",
    primaryKeys = ["snapshotId", "uid"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("uid", "snapshotId")],
)
public data class JobStatEntity(
    val snapshotId: Long,
    val uid: Int,
    val jobs: Int,
    val runtimeMs: Long?,
    val abandoned: Int?,
    val topStopReason: String?,
)

/** SPEC 8 `NetStat`, in bytes. Null: not reported, never zero. */
@Entity(
    tableName = "net_stat",
    primaryKeys = ["snapshotId", "uid"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("uid", "snapshotId")],
)
public data class NetStatEntity(
    val snapshotId: Long,
    val uid: Int,
    val mobileRxB: Long?,
    val mobileTxB: Long?,
    val wifiRxB: Long?,
    val wifiTxB: Long?,
    val radioActiveMs: Long?,
)

/** SPEC 8 `RailEnergy`: one power rail's cumulative energy (S16), read at [readElapsedNs]. */
@Entity(
    tableName = "rail_energy",
    primaryKeys = ["snapshotId", "rail"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
public data class RailEnergyEntity(
    val snapshotId: Long,
    val rail: String,
    val energyUws: Long,
    /** The reading's own time, which can lag the snapshot's. */
    val readElapsedNs: Long?,
)

/**
 * Other per-UID counters a rule needs (GNSS and sensor time, scans, wakelock time without overlap,
 * background time), keyed by the source's own name. Not in the SPEC 8 list; added by ADR-0015.
 */
@Entity(
    tableName = "uid_stat",
    primaryKeys = ["snapshotId", "uid", "key"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("uid", "snapshotId")],
)
public data class UidStatEntity(
    val snapshotId: Long,
    val uid: Int,
    val key: String,
    val value: Long,
)

/** Device-level counters (signal bins, Doze time, the platform's learned capacity), as [UidStatEntity]. */
@Entity(
    tableName = "device_stat",
    primaryKeys = ["snapshotId", "key"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
public data class DeviceStatEntity(
    val snapshotId: Long,
    val key: String,
    val value: Long,
)

/** SPEC 8 `UidMeta`. Icons are never stored (SPEC 8.1); [iconCacheKey] names the in-memory entry. */
@Entity(tableName = "uid_meta")
public data class UidMetaEntity(
    @PrimaryKey val uid: Int,
    /** Package names sharing the UID, as a JSON array. */
    val packagesJson: String,
    val label: String,
    val isSystem: Boolean,
    val iconCacheKey: String,
    val updatedWall: Long,
)

/**
 * Which packages a UID belonged to and when, so a window kept for good keeps its app's name after
 * the UID is freed and given to a later install. Not in the SPEC 8 list; added by ADR-0015.
 */
@Entity(tableName = "uid_package", primaryKeys = ["uid", "packageName"])
public data class UidPackageEntity(
    val uid: Int,
    val packageName: String,
    val firstSeenWall: Long,
    val lastSeenWall: Long,
)

/**
 * SPEC 8 `Reference` (SPEC 7.6): a named snapshot that later windows measure from. An automatic
 * reference has one row per [autoKind], moved to a new snapshot rather than added again. A snapshot
 * with a reference cannot be deleted.
 */
@Entity(
    tableName = "reference",
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("snapshotId"), Index("autoKind", unique = true)],
)
public data class ReferenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The user's name; null for an automatic reference, which the UI names in its language. */
    val name: String?,
    val snapshotId: Long,
    val origin: ReferenceOrigin,
    val autoKind: AutoReference?,
    val createdWall: Long,
)

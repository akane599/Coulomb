// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import io.github.akane599.coulomb.core.model.Breakdown
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage

/**
 * SPEC 8 `WindowResult`: the drain between two snapshots (SPEC 6.2, 6.5). [measuredMah] comes from
 * the charge, by [chargeSource]; [modeledMah] from the platform model, null where no model was read
 * (T0, Lite mode); [unattributedMah] is their difference where both exist.
 *
 * Day and session windows stay for good, consecutive windows 30 days (ADR-0021). Their per-UID rows
 * ([WindowUidEntity], [WindowWakeEntity], [WindowStatEntity]) stay as long as the retention policy
 * keeps them for the window's [kind] (ADR-0015); [detailPruned] then tells "pruned" apart from
 * "nothing happened".
 */
@Entity(
    tableName = "window_result",
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["fromSnapshotId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["toSnapshotId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("fromSnapshotId"), Index("toSnapshotId"), Index("sessionId"), Index("kind", "toSnapshotId")],
)
@TypeConverters(KernelCodes::class, BreakdownCodes::class)
public data class WindowResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: WindowKind,
    /** The session a [WindowKind.SESSION] window covers. */
    val sessionId: Long?,
    val fromSnapshotId: Long,
    val toSnapshotId: Long,
    val measuredMah: Double?,
    /** Where [measuredMah] came from, which sets its provenance (SPEC 6.1). */
    val chargeSource: ChargeSource?,
    val modeledMah: Double?,
    val unattributedMah: Double?,
    val screenOnMs: Long,
    val screenOffMs: Long,
    val awakeMsScreenOff: Long?,
    /** 0 to 1 (SPEC 6.7). */
    val quality: Double,
    val detailPruned: Boolean,
    /**
     * Which root kernel sources the window's rows cover, and how much (ADR-0029 §4). Null when no
     * kernel source was collected for the window.
     */
    val kernelCoverage: KernelCoverage? = null,
    /**
     * Consecutive windows only: broken down by S4, or the first rule that failed (ADR-0035 §5). Null
     * on other kinds and on windows from before schema 4.
     */
    val breakdown: Breakdown? = null,
)

/**
 * SPEC 8 `WindowUid`: one UID's share of a window, named as in [UidPowerEntity]. [process] splits a
 * shared UID by process (SPEC 6.5) and is [WHOLE_UID] otherwise; like the process states, a UID's
 * component has either one [WHOLE_UID] row or rows per process, never both. UIDs below the
 * retention policy's threshold are summed per component into an [OTHER_UID] row (ADR-0015).
 * [provenance] is stored as a [DetailCodes] code (ADR-0021).
 *
 * The component and process-state names are [StatKeyEntity] ids without a foreign key, like
 * `window_stat`'s keys (ADR-0021 margin step 1, taken by ADR-0035); `WindowDao` reads and writes
 * them as text, as [WindowUid].
 */
@Entity(
    tableName = "window_uid",
    primaryKeys = ["windowId", "uid", "componentId", "processStateId", "process"],
    foreignKeys = [
        ForeignKey(
            entity = WindowResultEntity::class,
            parentColumns = ["id"],
            childColumns = ["windowId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("uid", "windowId")],
)
@TypeConverters(DetailCodes::class)
public data class WindowUidEntity(
    val windowId: Long,
    val uid: Int,
    val componentId: Int,
    val processStateId: Int,
    val process: String,
    val mAh: Double,
    val provenance: Provenance,
) {
    public companion object {
        public const val WHOLE_UID: String = ""

        /** The sum of the UIDs not stored one by one. */
        public const val OTHER_UID: Int = -2
    }
}

/** A [WindowUidEntity] with its component and process state as text. [windowId] is ignored on insert. */
@TypeConverters(DetailCodes::class)
public data class WindowUid(
    val windowId: Long,
    val uid: Int,
    val component: String,
    val processState: String,
    val process: String,
    val mAh: Double,
    val provenance: Provenance,
)

/**
 * A wakelock, kernel wakelock, wakeup reason or root kernel cause's change over a window, so the
 * screen-off causes (SPEC 6.5) outlive the snapshots' counters. For the root kinds a missing row
 * means zero only where the window's [WindowResultEntity.kernelCoverage] is whole for its source.
 * Not in the SPEC 8 list; added by ADR-0015. The tag is a [WakeTagEntity] id without a foreign key
 * (ADR-0021); `WindowDao` reads and writes it as text.
 */
@Entity(
    tableName = "window_wake",
    foreignKeys = [
        ForeignKey(
            entity = WindowResultEntity::class,
            parentColumns = ["id"],
            childColumns = ["windowId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("windowId")],
)
public data class WindowWakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val windowId: Long,
    val uid: Int?,
    val tagId: Long,
    val kind: WakeKind,
    val totalMs: Long,
    val count: Int,
    val screenOffMs: Long?,
    /** [WakeKind.WAKEUP_SOURCE] rows only: the change of the source's wakeup count (ADR-0029). */
    val wakeups: Long? = null,
)

/**
 * Any other counter's change over a window: alarms, jobs, network bytes, rail energy and the
 * [UidStatEntity] and [DeviceStatEntity] keys, keyed by the same names. Device-level rows use
 * [UidPowerEntity.DEVICE_UID]. Not in the SPEC 8 list; added by ADR-0015.
 *
 * The table holds most of the database's detail rows, so it is kept narrow (ADR-0021): the key is a
 * [StatKeyEntity] id without a foreign key, [provenance] a [DetailCodes] code, and the primary key is
 * the only index. `WindowDao` reads and writes the key as text.
 */
@Entity(
    tableName = "window_stat",
    primaryKeys = ["windowId", "uid", "keyId"],
    foreignKeys = [
        ForeignKey(
            entity = WindowResultEntity::class,
            parentColumns = ["id"],
            childColumns = ["windowId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
@TypeConverters(DetailCodes::class)
public data class WindowStatEntity(
    val windowId: Long,
    val uid: Int,
    val keyId: Int,
    val value: Long,
    val provenance: Provenance,
)

/** A window counter's name, stored once (ADR-0021). The set is small and only grows: never pruned. */
@Entity(tableName = "stat_key", indices = [Index("name", unique = true)])
public data class StatKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
)

/** A window wakelock's tag, stored once (ADR-0021); tags no window uses are pruned with the detail. */
@Entity(tableName = "wake_tag", indices = [Index("tag", unique = true)])
public data class WakeTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tag: String,
)

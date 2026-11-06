// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind

/**
 * SPEC 8 `WindowResult`: the drain between two snapshots (SPEC 6.2, 6.5). [measuredMah] comes from
 * the charge, by [chargeSource]; [modeledMah] from the platform model, null where no model was read
 * (T0, Lite mode); [unattributedMah] is their difference where both exist.
 *
 * The row stays for good. Its per-UID rows ([WindowUidEntity], [WindowWakeEntity],
 * [WindowStatEntity]) stay as long as the retention policy keeps them for its [kind] (ADR-0015);
 * [detailPruned] then tells "pruned" apart from "nothing happened".
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
)

/**
 * SPEC 8 `WindowUid`: one UID's share of a window, named as in [UidPowerEntity]. [process] splits a
 * shared UID by process (SPEC 6.5) and is [WHOLE_UID] otherwise; like the process states, a UID's
 * component has either one [WHOLE_UID] row or rows per process, never both. UIDs below the
 * retention policy's threshold are summed per component into an [OTHER_UID] row (ADR-0015).
 */
@Entity(
    tableName = "window_uid",
    primaryKeys = ["windowId", "uid", "component", "processState", "process"],
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
public data class WindowUidEntity(
    val windowId: Long,
    val uid: Int,
    val component: String,
    val processState: String,
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

/**
 * A wakelock, kernel wakelock or wakeup reason's change over a window, so the screen-off causes
 * (SPEC 6.5) outlive the snapshots' counters. Not in the SPEC 8 list; added by ADR-0015.
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
    indices = [Index("windowId"), Index("uid", "windowId")],
)
public data class WindowWakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val windowId: Long,
    val uid: Int?,
    val tag: String,
    val kind: WakeKind,
    val totalMs: Long,
    val count: Int,
    val screenOffMs: Long?,
)

/**
 * Any other counter's change over a window: alarms, jobs, network bytes, rail energy and the
 * [UidStatEntity] and [DeviceStatEntity] keys, keyed by the same names. Device-level rows use
 * [UidPowerEntity.DEVICE_UID]. Not in the SPEC 8 list; added by ADR-0015.
 */
@Entity(
    tableName = "window_stat",
    primaryKeys = ["windowId", "uid", "key"],
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
public data class WindowStatEntity(
    val windowId: Long,
    val uid: Int,
    val key: String,
    val value: Long,
    val provenance: Provenance,
)

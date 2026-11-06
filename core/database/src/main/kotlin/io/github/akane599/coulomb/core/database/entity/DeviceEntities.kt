// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.CapabilityState
import io.github.akane599.coulomb.core.model.FixAction

/**
 * SPEC 8 `DeviceProfile`: one row per OS build (`Build.FINGERPRINT`). The calibration columns are
 * null until calibration completes (SPEC 6.1), when values show as "Calibrating".
 */
@Entity(tableName = "device_profile")
public data class DeviceProfileEntity(
    @PrimaryKey val fingerprint: String,
    val model: String,
    val oem: String,
    val sdkInt: Int,
    val sdkIntFull: Int,
    val designCapacityMah: Int?,
    /** Where [designCapacityMah] came from, such as `power_profile` or `sysfs`. */
    val designCapacitySource: String?,
    /** µA per raw `CURRENT_NOW` unit: 1 or 1000. */
    val currentScale: Int?,
    /** +1 or -1, so that charging is positive. */
    val currentSign: Int?,
    val chargeCounterReliable: Boolean?,
    val calibratedAtWall: Long?,
    /** 0 to 1. */
    val calibrationQuality: Double?,
)

/**
 * One boot of the device, numbered by Coulomb (ADR-0015). Every `bootSeq` column refers to it: the
 * sequence starts at `Settings.Global.BOOT_COUNT` and goes on from the highest stored value when the
 * count does not fit, as after an import (SPEC 14.5) or a reset count, so a new boot always sorts
 * after every stored one.
 */
@Entity(tableName = "boot")
public data class BootEntity(
    @PrimaryKey val bootSeq: Int,
    /** `Settings.Global.BOOT_COUNT` as read; null when it could not be read. */
    val rawBootCount: Int?,
    val fingerprint: String,
    /** The first wall and elapsed times Coulomb saw in this boot. */
    val firstWallMs: Long,
    val firstElapsedNs: Long,
    /**
     * `MonotonicClock` time minus `elapsedRealtime`, in ms, as used to place this boot's history
     * events (ADR-0014). Kept so the mapping can be redone: monotonic = elapsed + offset.
     */
    val historyOffsetMs: Long?,
    /** Came from an imported file, not from this device. */
    val imported: Boolean,
)

/** SPEC 8 `Capability` (SPEC 4.6). [id] is `CapabilityId.key`, which never changes. */
@Entity(tableName = "capability")
public data class CapabilityEntity(
    @PrimaryKey val id: String,
    val state: CapabilityState,
    val tier: AccessTier,
    val reason: String?,
    val fixAction: FixAction?,
    val probedAtWall: Long,
    val fingerprint: String,
    /** The probe's extra findings as a JSON object of strings. */
    val detailJson: String,
)

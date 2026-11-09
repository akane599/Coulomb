// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.TypeConverter
import io.github.akane599.coulomb.core.model.Breakdown
import io.github.akane599.coulomb.core.model.stats.ComponentPower
import io.github.akane599.coulomb.core.model.stats.PowerColumn

/**
 * The 20 S4 columns of ADR-0035 §4 as stored, in deci-coulombs: whole numbers, so sums are exact.
 * Most are 0, which SQLite stores in no bytes.
 */
public data class PowerColumns(
    val screen: Long = 0,
    val cpu: Long = 0,
    val bluetooth: Long = 0,
    val camera: Long = 0,
    val audio: Long = 0,
    val video: Long = 0,
    val flashlight: Long = 0,
    val mobileRadio: Long = 0,
    val sensors: Long = 0,
    val gnss: Long = 0,
    val wifi: Long = 0,
    val wakelock: Long = 0,
    val phone: Long = 0,
    val ambientDisplay: Long = 0,
    val custom: Long = 0,
    val other: Long = 0,
    val foreground: Long = 0,
    val background: Long = 0,
    val foregroundService: Long = 0,
    val cached: Long = 0,
) {
    public fun toPower(): ComponentPower =
        ComponentPower.of(
            mapOf(
                PowerColumn.SCREEN to screen,
                PowerColumn.CPU to cpu,
                PowerColumn.BLUETOOTH to bluetooth,
                PowerColumn.CAMERA to camera,
                PowerColumn.AUDIO to audio,
                PowerColumn.VIDEO to video,
                PowerColumn.FLASHLIGHT to flashlight,
                PowerColumn.MOBILE_RADIO to mobileRadio,
                PowerColumn.SENSORS to sensors,
                PowerColumn.GNSS to gnss,
                PowerColumn.WIFI to wifi,
                PowerColumn.WAKELOCK to wakelock,
                PowerColumn.PHONE to phone,
                PowerColumn.AMBIENT_DISPLAY to ambientDisplay,
                PowerColumn.CUSTOM to custom,
                PowerColumn.OTHER to other,
                PowerColumn.FOREGROUND to foreground,
                PowerColumn.BACKGROUND to background,
                PowerColumn.FOREGROUND_SERVICE to foregroundService,
                PowerColumn.CACHED to cached,
            ),
        )

    public companion object {
        public fun of(p: ComponentPower): PowerColumns =
            PowerColumns(
                screen = p[PowerColumn.SCREEN],
                cpu = p[PowerColumn.CPU],
                bluetooth = p[PowerColumn.BLUETOOTH],
                camera = p[PowerColumn.CAMERA],
                audio = p[PowerColumn.AUDIO],
                video = p[PowerColumn.VIDEO],
                flashlight = p[PowerColumn.FLASHLIGHT],
                mobileRadio = p[PowerColumn.MOBILE_RADIO],
                sensors = p[PowerColumn.SENSORS],
                gnss = p[PowerColumn.GNSS],
                wifi = p[PowerColumn.WIFI],
                wakelock = p[PowerColumn.WAKELOCK],
                phone = p[PowerColumn.PHONE],
                ambientDisplay = p[PowerColumn.AMBIENT_DISPLAY],
                custom = p[PowerColumn.CUSTOM],
                other = p[PowerColumn.OTHER],
                foreground = p[PowerColumn.FOREGROUND],
                background = p[PowerColumn.BACKGROUND],
                foregroundService = p[PowerColumn.FOREGROUND_SERVICE],
                cached = p[PowerColumn.CACHED],
            )
    }
}

/**
 * S4's cumulative values at a heavy snapshot, one row per consumer, the device under
 * [UidPowerEntity.DEVICE_UID] (ADR-0035 §6). Written only when S4 is `ok`; pruned with `uid_power`.
 */
@Entity(
    tableName = "uid_component_power",
    primaryKeys = ["snapshotId", "uid"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
public data class UidComponentPowerEntity(
    val snapshotId: Long,
    val uid: Int,
    @Embedded val power: PowerColumns,
)

/**
 * The changes over an S4 interval, a consecutive window broken down by ADR-0035 §5: the UIDs the
 * window keeps in `window_uid`, one [WindowUidEntity.OTHER_UID] row for every other UID and one
 * [UidPowerEntity.DEVICE_UID] row, which marks the window as covered. The rows add up exactly to S4's
 * change.
 */
@Entity(
    tableName = "window_component",
    primaryKeys = ["windowId", "uid"],
    foreignKeys = [
        ForeignKey(
            entity = WindowResultEntity::class,
            parentColumns = ["id"],
            childColumns = ["windowId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
public data class WindowComponentEntity(
    val windowId: Long,
    val uid: Int,
    @Embedded val power: PowerColumns,
)

/** Storage form of [Breakdown] (schema v4, ADR-0035 §6). */
public object BreakdownCodes {
    @TypeConverter
    public fun breakdownCode(breakdown: Breakdown?): Int? = breakdown?.code

    @TypeConverter
    public fun breakdown(code: Int?): Breakdown? = code?.let(Breakdown::ofCode)
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.TypeConverter
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import io.github.akane599.coulomb.core.model.history.HistoryKind.ACTIVE
import io.github.akane599.coulomb.core.model.history.HistoryKind.ALARM
import io.github.akane599.coulomb.core.model.history.HistoryKind.AUDIO
import io.github.akane599.coulomb.core.model.history.HistoryKind.BATTERY_CHARGE
import io.github.akane599.coulomb.core.model.history.HistoryKind.BATTERY_HEALTH
import io.github.akane599.coulomb.core.model.history.HistoryKind.BATTERY_LEVEL
import io.github.akane599.coulomb.core.model.history.HistoryKind.BATTERY_PLUG
import io.github.akane599.coulomb.core.model.history.HistoryKind.BATTERY_STATUS
import io.github.akane599.coulomb.core.model.history.HistoryKind.BATTERY_TEMPERATURE
import io.github.akane599.coulomb.core.model.history.HistoryKind.BATTERY_VOLTAGE
import io.github.akane599.coulomb.core.model.history.HistoryKind.BLE_SCAN
import io.github.akane599.coulomb.core.model.history.HistoryKind.BLUETOOTH
import io.github.akane599.coulomb.core.model.history.HistoryKind.BOOT
import io.github.akane599.coulomb.core.model.history.HistoryKind.CAMERA
import io.github.akane599.coulomb.core.model.history.HistoryKind.CELLULAR_HIGH_TX_POWER
import io.github.akane599.coulomb.core.model.history.HistoryKind.CHARGING
import io.github.akane599.coulomb.core.model.history.HistoryKind.COLLECT_EXTERNAL_STATS
import io.github.akane599.coulomb.core.model.history.HistoryKind.CONNECTIVITY
import io.github.akane599.coulomb.core.model.history.HistoryKind.CPU_RUNNING
import io.github.akane599.coulomb.core.model.history.HistoryKind.DATA_CONNECTION
import io.github.akane599.coulomb.core.model.history.HistoryKind.DEVICE_IDLE
import io.github.akane599.coulomb.core.model.history.HistoryKind.DISPLAY_STATE
import io.github.akane599.coulomb.core.model.history.HistoryKind.FLASHLIGHT
import io.github.akane599.coulomb.core.model.history.HistoryKind.FOREGROUND
import io.github.akane599.coulomb.core.model.history.HistoryKind.GPS
import io.github.akane599.coulomb.core.model.history.HistoryKind.GPS_SIGNAL_QUALITY
import io.github.akane599.coulomb.core.model.history.HistoryKind.JOB
import io.github.akane599.coulomb.core.model.history.HistoryKind.LONG_WAKE_LOCK
import io.github.akane599.coulomb.core.model.history.HistoryKind.MOBILE_RADIO
import io.github.akane599.coulomb.core.model.history.HistoryKind.NR_STATE
import io.github.akane599.coulomb.core.model.history.HistoryKind.OVERFLOW
import io.github.akane599.coulomb.core.model.history.HistoryKind.PACKAGE_ACTIVE
import io.github.akane599.coulomb.core.model.history.HistoryKind.PACKAGE_INACTIVE
import io.github.akane599.coulomb.core.model.history.HistoryKind.PACKAGE_INSTALLED
import io.github.akane599.coulomb.core.model.history.HistoryKind.PACKAGE_UNINSTALLED
import io.github.akane599.coulomb.core.model.history.HistoryKind.PHONE_IN_CALL
import io.github.akane599.coulomb.core.model.history.HistoryKind.PHONE_SCANNING
import io.github.akane599.coulomb.core.model.history.HistoryKind.PHONE_SIGNAL_STRENGTH
import io.github.akane599.coulomb.core.model.history.HistoryKind.PHONE_STATE
import io.github.akane599.coulomb.core.model.history.HistoryKind.PLUGGED
import io.github.akane599.coulomb.core.model.history.HistoryKind.POWER_SAVE
import io.github.akane599.coulomb.core.model.history.HistoryKind.PROCESS
import io.github.akane599.coulomb.core.model.history.HistoryKind.RESET
import io.github.akane599.coulomb.core.model.history.HistoryKind.SCREEN
import io.github.akane599.coulomb.core.model.history.HistoryKind.SCREEN_BRIGHTNESS
import io.github.akane599.coulomb.core.model.history.HistoryKind.SCREEN_DOZE
import io.github.akane599.coulomb.core.model.history.HistoryKind.SCREEN_WAKE_UP
import io.github.akane599.coulomb.core.model.history.HistoryKind.SENSOR
import io.github.akane599.coulomb.core.model.history.HistoryKind.SHUTDOWN
import io.github.akane599.coulomb.core.model.history.HistoryKind.STATE_CHANGE
import io.github.akane599.coulomb.core.model.history.HistoryKind.SYNC
import io.github.akane599.coulomb.core.model.history.HistoryKind.TEMP_ALLOWLIST
import io.github.akane599.coulomb.core.model.history.HistoryKind.TOP_APP
import io.github.akane599.coulomb.core.model.history.HistoryKind.USB_DATA
import io.github.akane599.coulomb.core.model.history.HistoryKind.USER_FOREGROUND
import io.github.akane599.coulomb.core.model.history.HistoryKind.USER_RUNNING
import io.github.akane599.coulomb.core.model.history.HistoryKind.VIDEO
import io.github.akane599.coulomb.core.model.history.HistoryKind.WAKEUP_AP
import io.github.akane599.coulomb.core.model.history.HistoryKind.WAKE_LOCK
import io.github.akane599.coulomb.core.model.history.HistoryKind.WAKE_LOCK_EVENT
import io.github.akane599.coulomb.core.model.history.HistoryKind.WAKE_REASON
import io.github.akane599.coulomb.core.model.history.HistoryKind.WALL_CLOCK
import io.github.akane599.coulomb.core.model.history.HistoryKind.WIFI
import io.github.akane599.coulomb.core.model.history.HistoryKind.WIFI_FULL_LOCK
import io.github.akane599.coulomb.core.model.history.HistoryKind.WIFI_MULTICAST
import io.github.akane599.coulomb.core.model.history.HistoryKind.WIFI_RADIO
import io.github.akane599.coulomb.core.model.history.HistoryKind.WIFI_RUNNING
import io.github.akane599.coulomb.core.model.history.HistoryKind.WIFI_SCAN
import io.github.akane599.coulomb.core.model.history.HistoryKind.WIFI_SIGNAL_STRENGTH
import io.github.akane599.coulomb.core.model.history.HistoryKind.WIFI_SUPPLICANT

/**
 * The history enums are stored as small integer codes, not by name, because the 30-day ring holds
 * millions of rows (ADR-0015). A code is the entry's position in its list: append new entries at
 * the end, never reorder or remove one. `HistoryCodesTest` fails when an entry has no code.
 */
public object HistoryCodes {
    internal val KINDS: List<HistoryKind> =
        listOf(
            BOOT,
            RESET,
            WALL_CLOCK,
            SHUTDOWN,
            OVERFLOW,
            BATTERY_LEVEL,
            BATTERY_STATUS,
            BATTERY_HEALTH,
            BATTERY_PLUG,
            BATTERY_TEMPERATURE,
            BATTERY_VOLTAGE,
            BATTERY_CHARGE,
            CPU_RUNNING,
            WAKE_LOCK,
            SENSOR,
            GPS,
            WIFI_FULL_LOCK,
            WIFI_SCAN,
            WIFI_MULTICAST,
            WIFI_RADIO,
            MOBILE_RADIO,
            PHONE_SCANNING,
            AUDIO,
            SCREEN,
            PLUGGED,
            SCREEN_DOZE,
            POWER_SAVE,
            VIDEO,
            WIFI_RUNNING,
            WIFI,
            FLASHLIGHT,
            CHARGING,
            USB_DATA,
            PHONE_IN_CALL,
            BLUETOOTH,
            CAMERA,
            BLE_SCAN,
            CELLULAR_HIGH_TX_POWER,
            DATA_CONNECTION,
            PHONE_STATE,
            PHONE_SIGNAL_STRENGTH,
            SCREEN_BRIGHTNESS,
            DEVICE_IDLE,
            WIFI_SIGNAL_STRENGTH,
            WIFI_SUPPLICANT,
            GPS_SIGNAL_QUALITY,
            NR_STATE,
            WAKE_REASON,
            PROCESS,
            FOREGROUND,
            TOP_APP,
            SYNC,
            WAKE_LOCK_EVENT,
            JOB,
            USER_RUNNING,
            USER_FOREGROUND,
            CONNECTIVITY,
            ACTIVE,
            PACKAGE_INSTALLED,
            PACKAGE_UNINSTALLED,
            ALARM,
            COLLECT_EXTERNAL_STATS,
            PACKAGE_INACTIVE,
            PACKAGE_ACTIVE,
            TEMP_ALLOWLIST,
            SCREEN_WAKE_UP,
            WAKEUP_AP,
            LONG_WAKE_LOCK,
            STATE_CHANGE,
            DISPLAY_STATE,
        )

    internal val CHANGES: List<HistoryChange> = listOf(HistoryChange.START, HistoryChange.STOP, HistoryChange.SET)

    private val kindCodes = KINDS.withIndex().associate { (code, kind) -> kind to code }

    @TypeConverter
    public fun kindCode(kind: HistoryKind): Int = kindCodes.getValue(kind)

    @TypeConverter
    public fun kind(code: Int): HistoryKind = KINDS.getOrNull(code) ?: error("Unknown history kind code $code")

    @TypeConverter
    public fun changeCode(change: HistoryChange): Int = CHANGES.indexOf(change)

    @TypeConverter
    public fun change(code: Int): HistoryChange = CHANGES.getOrNull(code) ?: error("Unknown history change code $code")
}

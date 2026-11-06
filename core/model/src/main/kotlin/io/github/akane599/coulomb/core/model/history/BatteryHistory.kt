// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.history

import io.github.akane599.coulomb.core.model.stats.StatsHeader

/**
 * One battery history read, `dumpsys batterystats -c --history-start <cursor>` (S7, ADR-0014).
 * The records themselves went to a sink in batches while the read ran.
 *
 * History times are the platform's `MonotonicClock` in milliseconds. It keeps counting across
 * reboots, so it is neither elapsed realtime nor wall time; [HistoryKind.WALL_CLOCK] records pair
 * it with wall time.
 */
public data class BatteryHistory(
    val header: StatsHeader?,
    /** The `NEXT:` value: the cursor for the next read. Null when the read did not end with one. */
    val nextCursorMs: Long?,
    /**
     * Monotonic time of the first record, which [HistoryRecord.offsetMs] counts from. Null when it
     * cannot be known: the read has no `NEXT:` line, a record failed to print, a record's time step
     * was unreadable, or there were no records.
     */
    val firstRecordMs: Long?,
    val recordCount: Long,
    /**
     * The read began at the oldest record the system still keeps, and that record is later than
     * the cursor: records between the cursor and [firstRecordMs] may be lost. Never set on a read
     * from cursor 0.
     */
    val gapBeforeFirstRecord: Boolean,
)

/**
 * One change in a history record. A printed record becomes zero or more of these, all with the
 * same [offsetMs].
 */
public data class HistoryRecord(
    /** Milliseconds after the read's first record; add [BatteryHistory.firstRecordMs]. */
    val offsetMs: Long,
    val kind: HistoryKind,
    val change: HistoryChange,
    /** See [HistoryKind] for each kind's unit; null when the kind has none. */
    val value: Long? = null,
    /** The UID a wakelock, event or wake reason tag belongs to. */
    val uid: Int? = null,
    val tag: String? = null,
    /**
     * The record restates the whole state: it is the read's first state record, or the first after
     * [HistoryKind.BOOT] or [HistoryKind.RESET]. A [HistoryChange.START] here means "on at this
     * time", not "turned on now", and a state or battery value the record does not name is off or
     * zero. Never set on events.
     */
    val baseline: Boolean = false,
)

public enum class HistoryChange {
    /** A state flag or an event turned on. */
    START,

    /** A state flag or an event turned off. */
    STOP,

    /** A value changed, or an event without a duration happened. */
    SET,
}

/**
 * What a [HistoryRecord] reports. The checkin names (`Bl`, `+S`, `Etp`) are those of
 * `BatteryStats.HistoryPrinter` (frameworks/base core/java/android/os/BatteryStats.java,
 * android-16.0.0_r4 and android-17.0.0_r1, which print the same).
 */
public enum class HistoryKind {
    // Markers: no value unless stated.

    /** `START`: the history started again, as after a boot. */
    BOOT,

    /** `RESET:TIME`: the statistics were reset. [HistoryRecord.value] is the wall time in ms. */
    RESET,

    /** `TIME`: [HistoryRecord.value] is the wall time in ms at this point of the history. */
    WALL_CLOCK,

    /** `SHUTDOWN`. */
    SHUTDOWN,

    /** `*OVERFLOW*`: the system's history buffer was full, so records are missing after this. */
    OVERFLOW,

    // Battery values, always SET.

    /** Percent. */
    BATTERY_LEVEL,

    /** `BatteryManager.BATTERY_STATUS_*`: 1 unknown, 2 charging, 3 discharging, 4 not charging, 5 full. */
    BATTERY_STATUS,

    /** `BatteryManager.BATTERY_HEALTH_*`: 1 unknown, 2 good, 3 overheat, 4 dead, 5 over voltage, 6 failure, 7 cold. */
    BATTERY_HEALTH,

    /** `BatteryManager.BATTERY_PLUGGED_*`: 0 none, 1 AC, 2 USB, 4 wireless, 8 dock. */
    BATTERY_PLUG,

    /** Tenths of a degree Celsius. */
    BATTERY_TEMPERATURE,

    /** Millivolts. */
    BATTERY_VOLTAGE,

    /** Remaining charge in mAh, as the fuel gauge reports it. */
    BATTERY_CHARGE,

    // State flags, START or STOP.

    CPU_RUNNING,

    /**
     * A partial wakelock is held. The tag is the first holder's, when the record has one. SET: the
     * state did not change, but the record names a holder.
     */
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

    // State values, SET. The value is the platform's index into the names shown.

    /** `BatteryStats.DATA_CONNECTION_NAMES`: 0 out of service, ..., 13 LTE, ..., 20 NR, 22 other. */
    DATA_CONNECTION,

    /** 0 in service, 1 out of service, 2 emergency only, 3 off. */
    PHONE_STATE,

    /** 0 none to 4 great. */
    PHONE_SIGNAL_STRENGTH,

    /** 0 dark to 4 bright. */
    SCREEN_BRIGHTNESS,

    /** 0 off, 1 light Doze, 2 deep Doze. */
    DEVICE_IDLE,

    /** 0 to 4. */
    WIFI_SIGNAL_STRENGTH,

    /** `BatteryStats.WIFI_SUPPL_STATE_NAMES`: 0 invalid, ..., 10 completed, 11 dormant, 12 uninitialized. */
    WIFI_SUPPLICANT,

    /** 0 poor, 1 good, 2 none. */
    GPS_SIGNAL_QUALITY,

    /** 0 none, 1 restricted, 2 not restricted, 3 connected. */
    NR_STATE,

    /** `wr=`: why the CPU woke up. The tag is the kernel's reason, SET. */
    WAKE_REASON,

    // Events: START and STOP for those with a duration, SET otherwise. The tag and its UID come
    // from the history tag pool.

    PROCESS,
    FOREGROUND,
    TOP_APP,
    SYNC,

    /** Every wakelock in a wake block; off by default on the platform. */
    WAKE_LOCK_EVENT,
    JOB,
    USER_RUNNING,
    USER_FOREGROUND,

    /** The tag is the new state; [HistoryRecord.value] is the network type, and there is no UID. */
    CONNECTIVITY,

    /** The device left idle. */
    ACTIVE,

    /** [HistoryRecord.value] is the low 32 bits of the version code, and there is no UID. */
    PACKAGE_INSTALLED,
    PACKAGE_UNINSTALLED,
    ALARM,
    COLLECT_EXTERNAL_STATS,
    PACKAGE_INACTIVE,
    PACKAGE_ACTIVE,
    TEMP_ALLOWLIST,
    SCREEN_WAKE_UP,

    /** The UID whose network traffic woke the application processor. */
    WAKEUP_AP,

    /** A partial wakelock held for a long time. */
    LONG_WAKE_LOCK,
    STATE_CHANGE,
    DISPLAY_STATE,
}

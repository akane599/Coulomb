// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.dumpsys

/**
 * The parts of `dumpsys alarm` (S10) Coulomb reads, as `AlarmManagerService.dumpImpl` prints them
 * (frameworks/base apex/jobscheduler/service/java/com/android/server/alarm/AlarmManagerService.java,
 * android-16.0.0_r4 L2985-3484, android-17.0.0_r1 L3111-3616). A part is null when it was missing
 * or could not be read whole. The `Top Alarms` list is not kept: it is the ten [AlarmTagStats] with
 * the most running time, all of which [appStats] holds.
 */
public data class AlarmDump(
    /** `nowELAPSED`: elapsed realtime in milliseconds when the dump was taken. */
    val nowElapsedMs: Long?,
    /** Wall-clock milliseconds when the dump was taken. */
    val nowRtcMs: Long?,
    /** Alarms waiting to fire, soonest first. */
    val pending: List<PendingAlarm>?,
    /** Pending alarm count per UID (`Pending alarms per uid`). */
    val pendingPerUid: Map<Int, Int>?,
    /** Alarms delivered since boot, per app (`Alarm Stats`), in the platform's order. */
    val appStats: List<AppAlarmStats>?,
)

/** `Alarm.typeToString` (Alarm.java L301-314, the same file in both tags). */
public enum class AlarmType(
    public val platformName: String,
    public val wakeup: Boolean,
) {
    RTC("RTC", false),
    RTC_WAKEUP("RTC_WAKEUP", true),
    ELAPSED("ELAPSED", false),
    ELAPSED_WAKEUP("ELAPSED_WAKEUP", true),
}

/** One pending alarm: its `Alarm.toString` line (Alarm.java L249-263) and the first line of its tag. */
public data class PendingAlarm(
    /** Null for a type the platform prints as `--unknown--`. */
    val type: AlarmType?,
    /** The app that set the alarm. */
    val packageName: String,
    /** When it is due, in elapsed realtime milliseconds; RTC alarms are converted by the platform. */
    val whenElapsedMs: Long,
    /**
     * The first line of the alarm's tag, `*walarm*:` or `*alarm*:` and then the intent action or
     * listener tag the app chose, so it may hold personal text; null when the line was missing.
     */
    val tag: String?,
)

/** One `BroadcastStats` entry of `Alarm Stats`: an app's delivered alarms since boot. */
public data class AppAlarmStats(
    val uid: Int,
    val packageName: String,
    /** Time its alarm broadcasts ran, in milliseconds. */
    val runningMs: Long,
    /** Deliveries that woke the device. */
    val wakeups: Int,
    /** A delivery is running now (`*ACTIVE*`). */
    val active: Boolean,
    /** Per tag, most running time first. */
    val alarms: List<AlarmTagStats>,
)

/** One `FilterStats` entry: the deliveries of one alarm tag. */
public data class AlarmTagStats(
    /** The full tag, line breaks included; chosen by the app, so it may hold personal text. */
    val tag: String,
    val runningMs: Long,
    val wakeups: Int,
    /** Deliveries, waking or not. */
    val count: Int,
    /** Elapsed milliseconds from the last delivery to the dump; null when the platform printed none. */
    val lastMsBeforeDump: Long?,
    val active: Boolean,
)

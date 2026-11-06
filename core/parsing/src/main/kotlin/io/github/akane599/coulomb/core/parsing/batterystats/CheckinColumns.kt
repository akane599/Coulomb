// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

/*
 * Column order of each `dumpsys batterystats -c` line that Coulomb maps, after the
 * `9,<uid>,<category>,<type>` header. One enum per section, entries in the writer's order.
 *
 * Writers: core/java/android/os/BatteryStats.java
 *   - dumpCheckinLocked(Context, PrintWriter, int, int, boolean, BatteryStatsDumpHelper):
 *     android-17.0.0_r1 L4260-4975 and android-16.0.0_r4 L4239-4954. The two are identical.
 *     The line numbers below are android-17.0.0_r1; add -21 for android-16.0.0_r4.
 *   - dumpCheckin and dumpCheckinLocked(Context, PrintWriter, List, int, BatteryStatsDumpHelper)
 *     (`vers`, `uid`): android-17.0.0_r1 L7832-7909, android-16.0.0_r4 L7800-7877.
 *   - printWakeLockCheckin (one `wl` block, one `kwl` tail): android-17.0.0_r1 L3740-3771.
 *   - dumpTimer(PrintWriter, ...) (`fg`, `fgs`, `vib`, `aud`, `vid`, `cam`, `fla`): L3814-3824.
 *
 * Units are milliseconds unless an entry says otherwise. Values the writer prints as microseconds
 * are marked `_US`.
 */

/** `i,vers` (L7837-7839). */
internal enum class VersCol { CHECKIN_VERSION, PARCEL_VERSION, START_PLATFORM_VERSION, END_PLATFORM_VERSION }

/** `i,uid`: one line per package of an app id, once per app id (L7857-7884). */
internal enum class UidDataCol { APP_ID, PACKAGE }

/** `bt` (L4309-4318). [START_COUNT] is "N/A" outside since-charged dumps. */
internal enum class BtCol {
    START_COUNT,
    BATTERY_REALTIME,
    BATTERY_UPTIME,
    TOTAL_REALTIME,
    TOTAL_UPTIME,
    START_CLOCK_TIME,
    SCREEN_OFF_REALTIME,
    SCREEN_OFF_UPTIME,
    ESTIMATED_CAPACITY_MAH,
    MIN_LEARNED_CAPACITY_UAH,
    MAX_LEARNED_CAPACITY_UAH,
    SCREEN_DOZE,
}

/** `m` (L4380-4394). */
internal enum class MiscCol {
    SCREEN_ON,
    PHONE_ON,
    FULL_WAKELOCK_TOTAL,
    PARTIAL_WAKELOCK_TOTAL,
    MOBILE_RADIO_ACTIVE,
    MOBILE_RADIO_ACTIVE_ADJUSTED,
    INTERACTIVE,
    BATTERY_SAVER,
    CONNECTIVITY_CHANGES,
    DEEP_DOZE_ENABLED,
    DEEP_DOZE_COUNT,
    DEEP_DOZE_IDLING,
    DEEP_DOZE_IDLING_COUNT,
    MOBILE_RADIO_ACTIVE_COUNT,
    MOBILE_RADIO_ACTIVE_UNKNOWN,
    LIGHT_DOZE_ENABLED,
    LIGHT_DOZE_COUNT,
    LIGHT_DOZE_IDLING,
    LIGHT_DOZE_IDLING_COUNT,
    LONGEST_LIGHT_DOZE,
    LONGEST_DEEP_DOZE,
}

/** `dc` (L4468-4474). */
internal enum class DischargeCol {
    LOWER_BOUND_PCT,
    UPPER_BOUND_PCT,
    SCREEN_ON_PCT,
    SCREEN_OFF_PCT,
    TOTAL_MAH,
    SCREEN_OFF_MAH,
    SCREEN_DOZE_PCT,
    SCREEN_DOZE_MAH,
    LIGHT_DOZE_MAH,
    DEEP_DOZE_MAH,
}

/** `kwl`: quoted name, then printWakeLockCheckin without a type letter or total duration (L4477-4485). */
internal enum class KernelWakelockCol { NAME, TOTAL, COUNT, CURRENT, MAX }

/** `wr`: quoted name, rounded total, count (L4486-4497). */
internal enum class WakeupReasonCol { NAME, TOTAL, COUNT }

/** `pws`: charges formatted by formatCharge (L4524-4528). */
internal enum class PowerSummaryCol { CAPACITY_MAH, COMPUTED_MAH, MIN_DRAINED_MAH, MAX_DRAINED_MAH }

/**
 * `pwi`: one line per device power component (label from CHECKIN_POWER_COMPONENT_LABELS, L4234-4252;
 * screen and smear written as "0") and one per app (label `uid`) (L4529-4553).
 */
internal enum class PowerItemCol { LABEL, MAH, HIDDEN, SCREEN_MAH, SMEAR_MAH }

/** `nt` (L4614-4622). [MOBILE_ACTIVE_US] is printed in microseconds. */
internal enum class NetworkCol {
    MOBILE_RX_BYTES,
    MOBILE_TX_BYTES,
    WIFI_RX_BYTES,
    WIFI_TX_BYTES,
    MOBILE_RX_PACKETS,
    MOBILE_TX_PACKETS,
    WIFI_RX_PACKETS,
    WIFI_TX_PACKETS,
    MOBILE_ACTIVE_US,
    MOBILE_ACTIVE_COUNT,
    BT_RX_BYTES,
    BT_TX_BYTES,
    MOBILE_WAKEUPS,
    WIFI_WAKEUPS,
    MOBILE_BG_RX_BYTES,
    MOBILE_BG_TX_BYTES,
    WIFI_BG_RX_BYTES,
    WIFI_BG_TX_BYTES,
    MOBILE_BG_RX_PACKETS,
    MOBILE_BG_TX_PACKETS,
    WIFI_BG_RX_PACKETS,
    WIFI_BG_TX_PACKETS,
}

/** `wfl` (L4630-4646). The first three are printed in microseconds; the actual times are unpooled. */
internal enum class WifiCol {
    FULL_LOCK_US,
    SCAN_US,
    RUNNING_US,
    SCAN_COUNT,
    LEGACY_1,
    LEGACY_2,
    LEGACY_3,
    SCAN_COUNT_BG,
    SCAN_ACTUAL,
    SCAN_ACTUAL_BG,
}

/** `blem` (L4651-4687). The actual and unoptimized times are unpooled, since reset. */
internal enum class BluetoothCol {
    SCAN_TOTAL,
    SCAN_COUNT,
    SCAN_COUNT_BG,
    SCAN_ACTUAL,
    SCAN_ACTUAL_BG,
    RESULT_COUNT,
    RESULT_COUNT_BG,
    UNOPTIMIZED_TOTAL,
    UNOPTIMIZED_TOTAL_BG,
    UNOPTIMIZED_MAX,
    UNOPTIMIZED_MAX_BG,
}

/** `awl` (L4706-4714). */
internal enum class AggregatedWakelockCol { PARTIAL, BACKGROUND_PARTIAL }

/**
 * `wl`: name with `,` `\n` `\r` replaced by `_` (not quoted), then four printWakeLockCheckin blocks
 * of `total,letter,count,current,max,totalDuration`: full `f`, partial `p`, background partial
 * `bp`, window `w` (L4716-4745). A null timer prints zeros.
 */
internal enum class WakelockCol {
    NAME,
    FULL_TOTAL,
    FULL_LETTER,
    FULL_COUNT,
    FULL_CURRENT,
    FULL_MAX,
    FULL_DURATION,
    PARTIAL_TOTAL,
    PARTIAL_LETTER,
    PARTIAL_COUNT,
    PARTIAL_CURRENT,
    PARTIAL_MAX,
    PARTIAL_DURATION,
    BG_PARTIAL_TOTAL,
    BG_PARTIAL_LETTER,
    BG_PARTIAL_COUNT,
    BG_PARTIAL_CURRENT,
    BG_PARTIAL_MAX,
    BG_PARTIAL_DURATION,
    WINDOW_TOTAL,
    WINDOW_LETTER,
    WINDOW_COUNT,
    WINDOW_CURRENT,
    WINDOW_MAX,
    WINDOW_DURATION,
}

/**
 * `sy` and `jb`: quoted name, rounded total, count, background total duration, background count;
 * both background values are -1 without a background timer (L4759-4789). Written only when the
 * total is not zero.
 */
internal enum class NamedTimerCol { NAME, TOTAL, COUNT, BG_DURATION, BG_COUNT }

/** `sr` (L4822-4843). Written only when the total is not zero. */
internal enum class SensorCol { ID, TOTAL, COUNT, BG_COUNT, ACTUAL, BG_ACTUAL }

/** dumpTimer lines: `fg`, `fgs`, `vib`, `aud`, `vid`, `cam`, `fla`. Written when either is not zero. */
internal enum class TimerCol { TOTAL, COUNT }

/** `st`: time in each `Uid.PROCESS_STATE_*`, TOP (0) to CACHED (6), rounded (L4855-4864). */
internal const val PROCESS_STATE_COLUMNS: Int = 7

/** `cpu` (L4866-4871). The third column is a legacy 0. */
internal enum class CpuCol { USER, SYSTEM, LEGACY_POWER }

/** `pr`: quoted name (L4924-4941). Written when any value is not zero. */
internal enum class ProcessCol { NAME, USER, SYSTEM, FOREGROUND, STARTS, ANRS, CRASHES }

/** `wua`: name with `,` replaced by `_` (L4946-4954). */
internal enum class WakeupAlarmCol { NAME, COUNT }

/** `apk`: wakeups of the package, package, service, start time, starts, launches (L4955-4970). */
internal enum class ApkCol { WAKEUPS, PACKAGE, SERVICE, START_TIME, STARTS, LAUNCHES }

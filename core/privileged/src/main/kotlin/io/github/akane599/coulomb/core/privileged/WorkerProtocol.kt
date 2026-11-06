// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged

/*
 * The worker allowlist (SPEC 19.1) as typed ids. The AIDL carries only these integer ids, so the
 * app cannot ask the worker for anything outside this file. Changing an entry needs a spec change
 * and an ADR.
 */

/**
 * `dumpsys batterystats` modes. Flags verified in BatteryStatsService.dumpUnmonitored():
 * android-16.0.0_r4 L3087-3422 (`--history` L3117, `--history-start` L3119, `-c` L3143,
 * `--proto` L3158, `-h` L3217); android-17.0.0_r1 L3139-3470 (L3167, L3169, L3193, L3206, L3265).
 * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/services/core/java/com/android/server/am/BatteryStatsService.java
 *
 * `--checkin` is deliberately absent: it dumps and then deletes the saved checkin file (ADR-0005).
 */
public enum class BatteryStatsMode(
    internal val id: Int,
) {
    HELP(1),
    PROTO(2),

    /** `-c`: current stats in checkin format, without touching the saved checkin file. */
    CHECKIN(3),
    HISTORY(4),

    /** `--history-start <n>`; the start value comes from the previous history read. */
    HISTORY_START(5),
    ;

    /**
     * The proto and checkin branches of `BatteryStatsService.dumpUnmonitored` call
     * `getInstalledApplications(MATCH_ANY_USER | MATCH_ALL)` under the caller's identity, so an
     * in-app dump needs INTERACT_ACROSS_USERS (services/core/java/com/android/server/am/
     * BatteryStatsService.java, android-16.0.0_r4 L3327-3328, L3369-3370; android-17.0.0_r1
     * L3375-3376, L3417-3418). The worker runs as shell or root and is not affected.
     */
    internal val listsAllUsers: Boolean get() = this == PROTO || this == CHECKIN

    internal fun args(historyStart: Long): Array<String> =
        when (this) {
            HELP -> arrayOf("-h")
            PROTO -> arrayOf("--proto")
            CHECKIN -> arrayOf("-c")
            HISTORY -> arrayOf("--history")
            HISTORY_START -> arrayOf("--history-start", historyStart.coerceAtLeast(0L).toString())
        }

    internal companion object {
        fun fromId(id: Int): BatteryStatsMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * System services the worker may dump without arguments (SPEC 19.1 read group). Binder names were
 * checked in `dumpsys -l` on both P0 devices (`fixtures/<device>/<build>/probe/dumpsys-services.txt`);
 * `suspend_control_internal` is a probe and is missing on both (S8).
 */
public enum class SystemService(
    internal val id: Int,
    internal val binderName: String,
) {
    ALARM(1, "alarm"),
    DEVICE_IDLE(2, "deviceidle"),
    POWER(3, "power"),
    JOB_SCHEDULER(4, "jobscheduler"),
    BATTERY(5, "battery"),
    THERMAL(6, "thermalservice"),
    SUSPEND_CONTROL_INTERNAL(7, "suspend_control_internal"),
    ;

    internal companion object {
        fun fromId(id: Int): SystemService? = entries.firstOrNull { it.id == id }
    }
}

/** Fixed read-only `cmd` queries (SPEC 19.1). */
public enum class PrivilegedQuery(
    internal val id: Int,
) {
    /** `am get-standby-bucket` without a package: every app's bucket. */
    STANDBY_BUCKETS(1),

    /** `appops get <package>`. */
    APP_OPS(2),

    /** `cmd deviceidle whitelist`. */
    DEVICE_IDLE_WHITELIST(3),
    ;

    internal companion object {
        fun fromId(id: Int): PrivilegedQuery? = entries.firstOrNull { it.id == id }
    }
}

public enum class SettingsNamespace(
    internal val id: Int,
    internal val cliName: String,
) {
    GLOBAL(1, "global"),
    SECURE(2, "secure"),
    SYSTEM(3, "system"),
    ;

    internal companion object {
        fun fromId(id: Int): SettingsNamespace? = entries.firstOrNull { it.id == id }
    }
}

/** Sysfs roots the worker may read (SPEC 19.1). */
public enum class SysfsRoot(
    internal val id: Int,
    internal val path: String,
) {
    POWER_SUPPLY(1, "/sys/class/power_supply"),
    WAKEUP(2, "/sys/class/wakeup"),
    ;

    internal companion object {
        fun fromId(id: Int): SysfsRoot? = entries.firstOrNull { it.id == id }
    }
}

/** The only permissions the bootstrap may grant, and only to Coulomb (SPEC 4.4, 19.1). */
public enum class DurablePermission(
    internal val id: Int,
    public val permission: String,
) {
    BATTERY_STATS(1, "android.permission.BATTERY_STATS"),
    DUMP(2, "android.permission.DUMP"),

    /** Only when the user turns on the Action Center. */
    WRITE_SECURE_SETTINGS(3, "android.permission.WRITE_SECURE_SETTINGS"),

    /** DumpUtils requires the permission as well as the op (ADR-0010). */
    PACKAGE_USAGE_STATS(4, "android.permission.PACKAGE_USAGE_STATS"),

    /** The batterystats proto and checkin dumps list apps with MATCH_ANY_USER (ADR-0010). */
    INTERACT_ACROSS_USERS(5, "android.permission.INTERACT_ACROSS_USERS"),
    ;

    internal companion object {
        fun fromId(id: Int): DurablePermission? = entries.firstOrNull { it.id == id }
    }
}

/** Status codes returned by the worker's dump and bootstrap methods. */
internal object WorkerStatusCode {
    const val OK = 0
    const val SERVICE_NOT_FOUND = 1
    const val DUMP_FAILED = 2
    const val COMMAND_FAILED = 3
    const val TIMEOUT = 4

    /** EACCES from the kernel (SELinux or DAC); the feature needs root (SPEC 4.5). */
    const val ACCESS_DENIED = 5
    const val INVALID_ARGUMENT = 6
    const val NOT_PERMITTED = 7
    const val IO_ERROR = 8

    /** The path or service does not exist (ENOENT). */
    const val NOT_FOUND = 9
}

internal object WorkerLimits {
    /** Sysfs attributes are a page at most; anything bigger is not an attribute. */
    const val MAX_SYSFS_BYTES = 64 * 1024

    /** `cmd` queries return a few KB; a cap keeps a misbehaving command from filling memory. */
    const val MAX_COMMAND_OUTPUT_BYTES = 4L * 1024 * 1024
    const val MAX_SYSFS_ENTRIES = 4096
    const val COMMAND_TIMEOUT_MS = 10_000L

    /** Upper bound for the transport test stream (P1 gate uses 20 MB). */
    const val MAX_TEST_PATTERN_BYTES = 256L * 1024 * 1024
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged

import io.github.akane599.coulomb.core.model.cmd.StandbyBucket

/**
 * One write of the Action Center (SPEC 13.2, 19.1 "Actions"; ADR-0060). Each is a typed worker
 * method; there is no free-form command. Whether a write did anything is decided by reading the
 * value back, never by what the command printed.
 */
public sealed interface PrivilegedAction {
    /** `am force-stop` for Coulomb's user. */
    public data class ForceStop(
        val packageName: String,
    ) : PrivilegedAction

    /** `appops set <pkg> RUN_ANY_IN_BACKGROUND allow|ignore`. */
    public data class SetRunInBackground(
        val packageName: String,
        val allowed: Boolean,
    ) : PrivilegedAction

    /** `am set-standby-bucket`; [bucket] is one of [BUCKETS]. */
    public data class SetStandbyBucket(
        val packageName: String,
        val bucket: StandbyBucket,
    ) : PrivilegedAction

    /** `cmd deviceidle whitelist +<pkg>` or `-<pkg>`: the user's list only. */
    public data class SetDozeAllowlisted(
        val packageName: String,
        val listed: Boolean,
    ) : PrivilegedAction

    /** `settings put global device_idle_constants <value>`, or `settings delete` for null. */
    public data class PutDozeConstants(
        val value: String?,
    ) : PrivilegedAction

    /** `cmd power set-mode 1|0`. */
    public data class SetBatterySaver(
        val on: Boolean,
    ) : PrivilegedAction

    /** `dumpsys batterystats --reset`. Not revertible. */
    public data object ResetBatteryStats : PrivilegedAction

    public companion object {
        /** The buckets a command may set: `set-standby-bucket` refuses Exempted, and Never is not Coulomb's to give. */
        public val BUCKETS: Set<StandbyBucket> =
            setOf(StandbyBucket.ACTIVE, StandbyBucket.WORKING_SET, StandbyBucket.FREQUENT, StandbyBucket.RARE, StandbyBucket.RESTRICTED)

        public const val DOZE_CONSTANTS_KEY: String = "device_idle_constants"

        /** The op of "Restrict background", by the name `appops` prints. */
        public const val RUN_ANY_IN_BACKGROUND: String = "RUN_ANY_IN_BACKGROUND"
    }
}

/**
 * The argument list of each [PrivilegedAction], as the worker executes it without a shell. The
 * Action Center's preview prints the same list (SPEC 13.1 "show the exact command"), so what is
 * shown is what runs.
 *
 * Verified against AOSP frameworks/base, tags android-16.0.0_r4 / android-17.0.0_r1:
 * - `activity force-stop [--user U] PKG`: ActivityManagerShellCommand.runForceStop() L1945-1959 /
 *   L2093-2107. Without `--user` it stops the package for every user.
 * - `activity set-standby-bucket [--user U] PKG BUCKET`: runSetStandbyBucket() L3591-3627 /
 *   L3739-3775; the bucket is matched by its first two letters.
 * - `appops set [--user U] PKG OP MODE`: AppOpsService.Shell "set" L5845-5876 / L6042-6073. The
 *   mode `default` is stored as a mode of its own and is not "the op's default"; a restore
 *   writes `allow` (AppStateTrackerImpl treats anything but allow as restricted,
 *   apex/jobscheduler/service/java/com/android/server/AppStateTrackerImpl.java L594-619, both tags).
 * - `deviceidle whitelist +PKG|-PKG`: DeviceIdleController shell "whitelist" L4967-5024 /
 *   L5053-5110; `-` removes from the user's list only.
 * - `settings [--user U] put|delete global KEY [VALUE]`: packages/SettingsProvider
 *   SettingsService L266-273, L379-448 (the same file on both tags). The value is one argument,
 *   taken as it is.
 * - `power set-mode 0|1`: PowerManagerShellCommand.runSetMode() L126-137 / L119-130; it calls
 *   `setPowerSaveModeEnabled` and prints nothing, also when the phone is charging and nothing changed.
 * - `dumpsys batterystats --reset`: BatteryStatsService.dump() L3173-3180 / L3221-3228 prints
 *   "Battery stats reset." and nothing else.
 */
public object ActionCommands {
    internal const val CMD: String = "/system/bin/cmd"
    internal const val DUMPSYS: String = "/system/bin/dumpsys"

    /** What `dumpsys batterystats --reset` prints when it has reset the statistics. */
    public const val RESET_DONE: String = "Battery stats reset."

    public fun argv(
        action: PrivilegedAction,
        userId: Int,
    ): List<String> {
        val user = userId.toString()
        return when (action) {
            is PrivilegedAction.ForceStop -> {
                listOf(CMD, "activity", "force-stop", "--user", user, action.packageName)
            }

            is PrivilegedAction.SetRunInBackground -> {
                val mode = if (action.allowed) "allow" else "ignore"
                listOf(CMD, "appops", "set", "--user", user, action.packageName, PrivilegedAction.RUN_ANY_IN_BACKGROUND, mode)
            }

            is PrivilegedAction.SetStandbyBucket -> {
                listOf(CMD, "activity", "set-standby-bucket", "--user", user, action.packageName, action.bucket.cliName)
            }

            is PrivilegedAction.SetDozeAllowlisted -> {
                listOf(CMD, "deviceidle", "whitelist", (if (action.listed) "+" else "-") + action.packageName)
            }

            is PrivilegedAction.PutDozeConstants -> {
                val head = listOf(CMD, "settings", "--user", user)
                val value = action.value
                if (value == null) {
                    head + listOf("delete", "global", PrivilegedAction.DOZE_CONSTANTS_KEY)
                } else {
                    head + listOf("put", "global", PrivilegedAction.DOZE_CONSTANTS_KEY, value)
                }
            }

            is PrivilegedAction.SetBatterySaver -> {
                listOf(CMD, "power", "set-mode", if (action.on) "1" else "0")
            }

            PrivilegedAction.ResetBatteryStats -> {
                listOf(DUMPSYS, "batterystats", "--reset")
            }
        }
    }

    /** The read an action on [packageName]'s background run is checked with: `appops get <pkg> RUN_ANY_IN_BACKGROUND`. */
    internal fun readRunInBackground(
        packageName: String,
        userId: Int,
    ): List<String> = listOf(CMD, "appops", "get", "--user", userId.toString(), packageName, PrivilegedAction.RUN_ANY_IN_BACKGROUND)

    /** `am get-standby-bucket <pkg>`: one app's bucket as its number. */
    internal fun readStandbyBucket(
        packageName: String,
        userId: Int,
    ): List<String> = listOf(CMD, "activity", "get-standby-bucket", "--user", userId.toString(), packageName)

    /**
     * The same command as one `adb shell` line a person can copy: programs by their short names,
     * and an argument a shell would split or expand in single quotes.
     */
    public fun adbLine(argv: List<String>): String =
        (listOf("adb", "shell", argv.first().substringAfterLast('/')) + argv.drop(1).map(::quoted)).joinToString(" ")

    private val PLAIN = Regex("^[A-Za-z0-9_.:+=,/-]+$")

    private fun quoted(argument: String): String = if (PLAIN.matches(argument)) argument else "'" + argument.replace("'", "'\\''") + "'"

    /**
     * Whether [value] may be written to `device_idle_constants` (ADR-0060 §6): empty, or
     * comma-separated `key=value` pairs of printable ASCII. Key names are not limited to AOSP's,
     * so an OEM's own keys survive a restore. One malformed pair would make the system drop every
     * override (KeyValueListParser.setString, core/java/android/util/KeyValueListParser.java
     * L55-69, both tags), so nothing malformed is written.
     */
    public fun isDozeConstants(value: String): Boolean =
        value.length <= MAX_DOZE_CONSTANTS && (value.isEmpty() || value.split(',').all(DOZE_PAIR::matches))

    private const val MAX_DOZE_CONSTANTS = 2_000
    private val DOZE_PAIR = Regex("^[A-Za-z_][A-Za-z0-9_.]*=[A-Za-z0-9_.+-]+$")
}

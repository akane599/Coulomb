// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.cmd

import io.github.akane599.coulomb.core.model.cmd.AppOpMode
import io.github.akane599.coulomb.core.model.cmd.AppOpReading
import io.github.akane599.coulomb.core.model.cmd.DozeAllowlist
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket
import io.github.akane599.coulomb.core.parsing.text.Durations

/**
 * The small outputs of the `cmd` reads an action is checked with (SPEC 13.1: read, write, read
 * back). Each returns null for output it does not fully understand: an action never runs, and is
 * never called verified, on a value Coulomb could not read. Pure.
 *
 * Formats, from AOSP frameworks/base at android-16.0.0_r4 / android-17.0.0_r1:
 * - `appops get <pkg> <op>`: AppOpsService.Shell "get", services/core/java/com/android/server/
 *   appop/AppOpsService.java L5877-5974 / L6074-6171. `No operations.` and `Default mode: <mode>`
 *   when nothing is recorded; else `<OP>: <mode>` with optional `; time=…`, `; rejectTime=…`,
 *   ` (running)`, `; duration=…`, and before a UID-wide entry the prefix `Uid mode: `.
 * - `am get-standby-bucket <pkg>`: the bucket's integer; without a package `<package>: <int>` a
 *   line (ActivityManagerShellCommand.runGetStandbyBucket, services/core/java/com/android/server/
 *   am/ActivityManagerShellCommand.java L3629-3657 / L3777-3805).
 * - `cmd deviceidle whitelist`: `system-excidle,<pkg>,<appId>`, `system,…`, `user,…` a line
 *   (DeviceIdleController shell "whitelist", apex/jobscheduler/service/java/com/android/server/
 *   DeviceIdleController.java L5004-5022 / L5090-5108).
 */
public object CmdOutputs {
    private const val NO_OPERATIONS = "No operations."
    private const val DEFAULT_MODE = "Default mode: "
    private const val UID_MODE = "Uid mode: "
    private val PACKAGE_BUCKET = Regex("""^([A-Za-z][A-Za-z0-9_.]*): (\d{1,3})$""")
    private val ALLOWLIST_LINE = Regex("""^(system-excidle|system|user),([A-Za-z][A-Za-z0-9_.]*),(\d+)$""")

    /** [op] is the op's debug name, as the command prints it (`RUN_ANY_IN_BACKGROUND`). */
    public fun appOp(
        stdout: String,
        op: String,
    ): AppOpReading? {
        val lines = lines(stdout)
        if (lines.firstOrNull() == NO_OPERATIONS) return unset(lines.drop(1))
        val (uid, pkg) = lines.partition { it.startsWith(UID_MODE) }
        val uidModes = uid.map { entryMode(it.removePrefix(UID_MODE), op) }
        val pkgModes = pkg.map { entryMode(it, op) }
        // One op was asked for: at most one entry of each kind, at least one of either, and all of them readable.
        val readable = lines.isNotEmpty() && uid.size <= 1 && pkg.size <= 1 && null !in uidModes && null !in pkgModes
        return if (readable) AppOpReading(uidModes.firstOrNull(), pkgModes.firstOrNull(), null) else null
    }

    /** After `No operations.` comes the op's default mode and nothing else. */
    private fun unset(rest: List<String>): AppOpReading? =
        rest
            .singleOrNull()
            ?.takeIf { it.startsWith(DEFAULT_MODE) }
            ?.let { AppOpMode.of(it.removePrefix(DEFAULT_MODE)) }
            ?.let { AppOpReading(uidMode = null, packageMode = null, defaultMode = it) }

    /** `<OP>: <mode>` and what follows the mode; null for another op or a mode without a name (`mode=7`). */
    private fun entryMode(
        entry: String,
        op: String,
    ): AppOpMode? {
        val prefix = "$op: "
        if (!entry.startsWith(prefix)) return null
        val rest = entry.removePrefix(prefix)
        val end = rest.indexOfFirst { it == ';' || it == ' ' }
        return AppOpMode.of(if (end < 0) rest else rest.substring(0, end))
    }

    public fun standbyBucket(stdout: String): StandbyBucket? =
        lines(stdout)
            .singleOrNull()
            ?.toIntOrNull()
            ?.let(StandbyBucket::of)

    /** Every app's bucket; a bucket value Coulomb does not know is left out, an unreadable line makes it null. */
    public fun standbyBuckets(stdout: String): Map<String, StandbyBucket>? {
        val buckets = LinkedHashMap<String, StandbyBucket>()
        for (line in lines(stdout)) {
            val match = PACKAGE_BUCKET.matchEntire(line) ?: return null
            StandbyBucket.of(match.groupValues[2].toInt())?.let { buckets[match.groupValues[1]] = it }
        }
        return buckets
    }

    public fun dozeAllowlist(stdout: String): DozeAllowlist? {
        val exceptIdle = LinkedHashSet<String>()
        val system = LinkedHashSet<String>()
        val user = LinkedHashSet<String>()
        for (line in lines(stdout)) {
            val match = ALLOWLIST_LINE.matchEntire(line) ?: return null
            when (match.groupValues[1]) {
                "system-excidle" -> exceptIdle
                "system" -> system
                else -> user
            } += match.groupValues[2]
        }
        // Every device lists system entries, so no line at all is a command that said nothing.
        if (exceptIdle.isEmpty() && system.isEmpty() && user.isEmpty()) return null
        return DozeAllowlist(exceptIdle, system, user)
    }

    /**
     * The duration keys of `dumpsys deviceidle`'s settings ([io.github.akane599.coulomb.core.model.dumpsys.DeviceIdleDump.settings])
     * in milliseconds. A key whose value is not a duration (a factor, a switch, a raw number) is
     * left out: a Doze profile changes durations only.
     */
    public fun dozeDurationsMs(settings: Map<String, String>): Map<String, Long> =
        settings.mapNotNull { (key, value) -> Durations.parseMs(value)?.let { key to it } }.toMap()

    private fun lines(stdout: String): List<String> =
        stdout
            .lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() }
            .toList()
}

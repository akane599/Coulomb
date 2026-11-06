// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.system

import io.github.akane599.coulomb.core.privileged.DurablePermission
import io.github.akane599.coulomb.core.privileged.SettingsNamespace

/**
 * Argv builders for the `cmd` operations in the SPEC 19.1 allowlist. `pm`, `am`, `appops` and
 * `settings` are wrappers that exec `cmd <service>`; calling `/system/bin/cmd` directly skips the
 * wrapper shell. Every argument is either a constant, an enum value or a string the caller has
 * validated (package names, settings keys), and nothing passes through a shell.
 *
 * Verified against AOSP (frameworks/base), tags android-16.0.0_r4 / android-17.0.0_r1:
 * - `package grant [--user U] PKG PERM`: PackageManagerShellCommand "grant" L316/L319,
 *   runGrantRevokePermission() L2660-2740 / L2705-2785.
 * - `appops set|get [--user U] PKG [OP] [MODE]`: AppOpsService.Shell.parseUserPackageOp() L5705-5797 /
 *   L5902-5994, onShellCommand "set" L5845-5876 / L6042-6073, "get" L5877-5974 / L6074-6171. `GET_USAGE_STATS` resolves
 *   through AppOpsManager.strOpToOp().
 * - `activity get-standby-bucket [--user U]` (no package: every app): ActivityManagerShellCommand
 *   "get-standby-bucket" L393 / L397, runGetStandbyBucket() L3629-3657 / L3777-3805.
 * - `settings [--user U] get|list NAMESPACE [KEY]`: packages/SettingsProvider SettingsService
 *   "--user" L141, verbs L163-170, GET prints getForUser() (L263-265, L350-377), so an unset key
 *   prints "null" (same lines on both tags).
 * - `deviceidle whitelist` (read form, no arguments): DeviceIdleController.onShellCommand()
 *   "whitelist" L4967-5024 / L5053-5110.
 */
internal object CmdCommands {
    const val CMD = "/system/bin/cmd"

    fun grant(
        packageName: String,
        permission: DurablePermission,
        userId: Int,
    ): List<String> = listOf(CMD, "package", "grant", "--user", userId.toString(), packageName, permission.permission)

    fun allowUsageStats(
        packageName: String,
        userId: Int,
    ): List<String> = listOf(CMD, "appops", "set", "--user", userId.toString(), packageName, "GET_USAGE_STATS", "allow")

    fun appOps(
        packageName: String,
        userId: Int,
    ): List<String> = listOf(CMD, "appops", "get", "--user", userId.toString(), packageName)

    fun standbyBuckets(userId: Int): List<String> = listOf(CMD, "activity", "get-standby-bucket", "--user", userId.toString())

    fun deviceIdleWhitelist(): List<String> = listOf(CMD, "deviceidle", "whitelist")

    fun settingsGet(
        namespace: SettingsNamespace,
        key: String,
        userId: Int,
    ): List<String> = listOf(CMD, "settings", "--user", userId.toString(), "get", namespace.cliName, key)

    fun settingsList(
        namespace: SettingsNamespace,
        userId: Int,
    ): List<String> = listOf(CMD, "settings", "--user", userId.toString(), "list", namespace.cliName)

    /** `settings get` prints the literal "null" for an unset key (see the class comment). */
    fun parseSettingValue(stdout: String): String? = stdout.trimEnd('\n', '\r').takeUnless { it == "null" }

    /** `settings list` prints one `key=value` per line; values may contain '='. */
    fun parseSettingKeys(stdout: String): List<String> =
        stdout
            .lineSequence()
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq > 0) line.substring(0, eq) else null
            }.toList()
}

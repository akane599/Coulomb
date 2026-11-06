// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.privileged.DurablePermission

/** Reads the T1 grants (SPEC 4.4). Re-verified at every start; never cached across starts. */
internal fun interface GrantSource {
    fun read(): GrantStatus
}

/**
 * Public APIs only: Context.checkSelfPermission and AppOpsManager.checkOpNoThrow(String, int, String)
 * with OPSTR_GET_USAGE_STATS (AppOpsManager.java L9576 / L9847; `unsafeCheckOpNoThrow` is deprecated
 * since API 36 per the SDK's api-versions.xml). A usage-stats op left at MODE_DEFAULT falls back to
 * the PACKAGE_USAGE_STATS permission check, as DumpUtils.checkUsageStatsPermission does.
 */
internal class AndroidGrantSource(
    private val context: Context,
) : GrantSource {
    override fun read(): GrantStatus =
        GrantStatus(
            batteryStats = granted(Manifest.permission.BATTERY_STATS),
            dump = granted(Manifest.permission.DUMP),
            usageStats = usageStatsAllowed(),
            usageStatsPermission = granted(Manifest.permission.PACKAGE_USAGE_STATS),
            // Not in the SDK's Manifest.permission (@SystemApi); the string is the platform's name.
            interactAcrossUsers = granted(DurablePermission.INTERACT_ACROSS_USERS.permission),
            writeSecureSettings = granted(Manifest.permission.WRITE_SECURE_SETTINGS),
        )

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun usageStatsAllowed(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return when (appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT -> granted(Manifest.permission.PACKAGE_USAGE_STATS)
            else -> false
        }
    }
}

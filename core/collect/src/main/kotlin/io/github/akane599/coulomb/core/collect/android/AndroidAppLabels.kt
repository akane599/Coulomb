// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import io.github.akane599.coulomb.core.collect.engine.AppLabel
import io.github.akane599.coulomb.core.collect.engine.AppLabels
import io.github.akane599.coulomb.core.collect.engine.AppNaming
import io.github.akane599.coulomb.core.collect.engine.PackageFacts

/**
 * UID names from `PackageManager` (ADR-0032 §5), a public API an app may call on its own; the
 * manifest's `QUERY_ALL_PACKAGES` (SPEC 15.4) makes every package visible. The packages come from
 * `getPackagesForUid`, as in Settings' `BatteryEntry` (see [AppNaming]).
 */
internal class AndroidAppLabels(
    context: Context,
) : AppLabels {
    private val pm = context.packageManager

    override fun label(uid: Int): AppLabel? {
        val names = if (uid == Process.SYSTEM_UID) listOf(AppNaming.SYSTEM_PACKAGE) else pm.getPackagesForUid(uid)?.toList()
        val facts = names.orEmpty().mapNotNull(::facts)
        // isApplicationUid is per app id, so a work profile's app UIDs are apps too. Settings compares
        // the raw UID, which is the same for the main user.
        return AppNaming.name(belowAppRange = !Process.isApplicationUid(uid), packages = facts)
    }

    private fun facts(name: String): PackageFacts? =
        try {
            val info = pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0))
            info.applicationInfo?.let { app ->
                PackageFacts(
                    packageName = name,
                    label = app.loadLabel(pm).toString(),
                    hasIcon = app.icon != 0,
                    sharedUserLabel = sharedUserLabel(name, info.sharedUserLabelRes(), app),
                )
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun sharedUserLabel(
        name: String,
        res: Int,
        app: ApplicationInfo,
    ): String? = res.takeIf { it != 0 }?.let { pm.getText(name, it, app)?.toString() }

    // Shared user ids are deprecated for new apps, but system packages still share them, and
    // Settings reads this field to name them (BatteryEntry L349).
    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.sharedUserLabelRes(): Int = sharedUserLabel
}

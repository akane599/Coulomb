// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.compose.runtime.Immutable
import io.github.akane599.coulomb.core.common.AutomationContract

/** Who may use the Automation API on this phone, read while its switch is on. */
@Immutable
internal data class AutomationAccess(
    /** The apps the user granted the permission, by name. */
    val apps: List<String> = emptyList(),
)

/** The Automation API's permission as Android holds it on this phone (SPEC 12.7, ADR-0065 §1). */
public object AutomationPermission {
    /**
     * Whether the permission is the one this manifest defines: Coulomb's own and `dangerous`.
     * Android refuses to install a package that defines a permission another signer's package
     * already owns (INSTALL_FAILED_DUPLICATE_PERMISSION, InstallPackageHelper android-17.0.0_r1
     * L1974-1991), so only a package signed with Coulomb's own key could hold the name first. The
     * check costs one call and keeps the API closed in that case too (getPermissionInfo,
     * PackageManager android-17.0.0_r1 L6431, android-16.0.0_r4 L6233).
     */
    public fun isOurs(context: Context): Boolean =
        try {
            val info = context.packageManager.getPermissionInfo(AutomationContract.permission(context.packageName), 0)
            info.packageName == context.packageName && info.protection == PermissionInfo.PROTECTION_DANGEROUS
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * The names of the apps the user has granted the permission, in the user's order of letters
     * (getPackagesHoldingPermissions, PackageManager android-17.0.0_r1 L6864, android-16.0.0_r4 L6666).
     */
    public fun holders(context: Context): List<String> {
        val packages = context.packageManager
        val held = arrayOf(AutomationContract.permission(context.packageName))
        return packages
            .getPackagesHoldingPermissions(held, PackageManager.PackageInfoFlags.of(0))
            .filter { it.packageName != context.packageName }
            .map {
                it.applicationInfo
                    ?.loadLabel(packages)
                    ?.toString()
                    ?.takeIf(String::isNotBlank) ?: it.packageName
            }.sortedWith(java.text.Collator.getInstance())
    }
}

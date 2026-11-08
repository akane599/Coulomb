// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

/** App names for UIDs, written to `uid_meta` (ADR-0032 §5). */
internal interface AppLabels {
    /** [uid]'s name, or null when no package of it is installed for this user now. */
    fun label(uid: Int): AppLabel?
}

/** A UID's name, whether it is part of the system, and the package whose icon stands for it. */
internal data class AppLabel(
    val label: String,
    val isSystem: Boolean,
    val iconPackage: String?,
)

/** One package of a UID, as `PackageManager` describes it. */
internal data class PackageFacts(
    val packageName: String,
    val label: String?,
    val hasIcon: Boolean,
    /** The shared user's label, for a package that declares one. */
    val sharedUserLabel: String?,
    val system: Boolean,
)

/**
 * Names a UID the way Android's battery usage screen does: Settings' `BatteryEntry.loadNameAndIcon`
 * (packages/apps/Settings `src/com/android/settings/fuelgauge/batteryusage/BatteryEntry.java`
 * L278-386, the same at android-16.0.0_r4 and android-17.0.0_r1). Pure, so the rule is tested
 * without a device.
 * - The system UID is named by the `android` package (the caller passes only that one).
 * - The icon comes from the first package that has one.
 * - One package: its label, or its name when it has no label.
 * - Several packages: the first shared user label. Settings keeps a placeholder when there is none;
 *   Coulomb takes the icon package's label, then the first label, then the first package name, so a
 *   row never shows a bare UID.
 */
internal object AppNaming {
    /** The package that names Android's system UID (`Process.SYSTEM_UID`), as Settings' `PACKAGE_SYSTEM`. */
    const val SYSTEM_PACKAGE: String = "android"

    fun name(
        systemUid: Boolean,
        packages: List<PackageFacts>,
    ): AppLabel? {
        val first = packages.firstOrNull() ?: return null
        val iconPackage = packages.firstOrNull { it.hasIcon }
        val label =
            if (packages.size == 1) {
                first.label ?: first.packageName
            } else {
                packages.firstNotNullOfOrNull { it.sharedUserLabel }
                    ?: iconPackage?.label
                    ?: packages.firstNotNullOfOrNull { it.label }
                    ?: first.packageName
            }
        return AppLabel(label, systemUid || packages.any { it.system }, iconPackage?.packageName)
    }
}

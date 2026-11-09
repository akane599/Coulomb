// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.privilege

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/** The system screens and pages onboarding and Privileges open. */
public enum class SystemScreen { NOTIFICATION_SETTINGS, USAGE_ACCESS, BATTERY_SETTINGS, SHIZUKU_APP, FORK_PAGE, SUI_PAGE }

/**
 * Opens [screen]. Intents verified in AOSP (android-16.0.0_r4 / android-17.0.0_r1):
 * `Settings.ACTION_APP_NOTIFICATION_SETTINGS` with `EXTRA_APP_PACKAGE` (Settings.java L2506, L2566 /
 * L2551, L2611), `Settings.ACTION_USAGE_ACCESS_SETTINGS` (L578 / L586),
 * `Intent.ACTION_POWER_USAGE_SUMMARY` (Intent.java L1735 / L1744) and, where an OEM lacks that,
 * `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` (Settings.java L1636 / L1682). A screen the phone
 * does not have is logged and nothing opens.
 */
public fun Context.open(
    screen: SystemScreen,
    managerIntent: () -> Intent? = { null },
) {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    val intents =
        when (screen) {
            SystemScreen.NOTIFICATION_SETTINGS -> {
                listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName), details)
            }

            SystemScreen.USAGE_ACCESS -> {
                listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }

            SystemScreen.BATTERY_SETTINGS -> {
                listOf(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), details)
            }

            SystemScreen.SHIZUKU_APP -> {
                listOfNotNull(managerIntent())
            }

            SystemScreen.FORK_PAGE -> {
                listOf(Intent(Intent.ACTION_VIEW, Uri.parse(FORK_URL)))
            }

            SystemScreen.SUI_PAGE -> {
                listOf(Intent(Intent.ACTION_VIEW, Uri.parse(SUI_URL)))
            }
        }
    val opened =
        intents.any { intent ->
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no activity for ${intent.action}", e)
                false
            }
        }
    if (!opened) Log.w(TAG, "could not open $screen")
}

private const val TAG = "SystemScreens"

/** The fork the owner chose to name first (ADR-0028, ADR-0047 §4), and Sui (SPEC 3). */
private const val FORK_URL = "https://github.com/thedjchi/Shizuku"
private const val SUI_URL = "https://github.com/RikkaApps/Sui"

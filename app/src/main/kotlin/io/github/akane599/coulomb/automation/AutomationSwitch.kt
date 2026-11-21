// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.automation

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.github.akane599.coulomb.feature.settings.AutomationPermission

/**
 * Opens and closes the Automation API's two components with the setting (SPEC 12.7, 14.4). Both
 * are disabled in the manifest, so while the setting is off no broadcast is delivered and no
 * query reaches Coulomb. The app applies the stored setting at every start: a state that survived
 * a restore or a data deletion is put right then.
 */
internal object AutomationSwitch {
    private const val TAG = "CoulombAutomation"

    /**
     * Enables the receiver and the provider when [on] and the permission is Coulomb's own
     * ([AutomationPermission.isOurs]); otherwise
     * returns them to the manifest's disabled state. Nothing is written when the state is already
     * so (setComponentEnabledSetting, PackageManager android-17.0.0_r1 L10136, android-16.0.0_r4 L9926).
     */
    fun apply(
        context: Context,
        on: Boolean,
    ) {
        val open = on && AutomationPermission.isOurs(context)
        if (on && !open) Log.w(TAG, "the automation permission is not Coulomb's own; the API stays closed")
        val wanted = if (open) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        val packages = context.packageManager
        for (component in listOf(AutomationReceiver::class.java, AutomationProvider::class.java)) {
            val name = ComponentName(context, component)
            if (packages.getComponentEnabledSetting(name) != wanted) {
                packages.setComponentEnabledSetting(name, wanted, PackageManager.DONT_KILL_APP)
            }
        }
    }
}

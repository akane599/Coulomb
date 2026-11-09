// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui

import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** A charger's [name], or one from its [plugType] while it has none (ADR-0044 §6). */
@Composable
public fun chargerName(
    name: String,
    plugType: Int?,
): String = name.ifBlank { unnamedCharger(plugType) }

/** `BatteryManager.BATTERY_PLUGGED_*` as an unnamed charger's words. */
@Composable
public fun unnamedCharger(plugType: Int?): String =
    stringResource(
        when (plugType) {
            BatteryManager.BATTERY_PLUGGED_AC -> R.string.charger_unnamed_ac
            BatteryManager.BATTERY_PLUGGED_USB -> R.string.charger_unnamed_usb
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> R.string.charger_unnamed_wireless
            BatteryManager.BATTERY_PLUGGED_DOCK -> R.string.charger_unnamed_dock
            else -> R.string.charger_unnamed
        },
    )

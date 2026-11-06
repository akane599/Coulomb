// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import io.github.akane599.coulomb.core.collect.CollectorClock
import io.github.akane599.coulomb.core.collect.snapshot.LightReading
import io.github.akane599.coulomb.core.collect.snapshot.LightSource

/**
 * S1, S2 and device state (SPEC 7.2: under 5 ms). S1 is the sticky `ACTION_BATTERY_CHANGED`,
 * read without a receiver, so it costs no wakeup. S2 is `BatteryManager.getLongProperty`, which
 * returns `Long.MIN_VALUE` for a property the device does not support (BatteryManager
 * android-17.0.0_r1 L422-449, android-16.0.0_r4 L393, L629).
 */
internal class AndroidLightSource(
    private val context: Context,
    private val clock: CollectorClock,
) : LightSource {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    override fun read(): LightReading {
        val elapsedNs = clock.elapsedNs()
        val wallMs = clock.wallMs()
        val sticky =
            ContextCompat.registerReceiver(
                context,
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        return LightReading(
            elapsedNs = elapsedNs,
            wallMs = wallMs,
            levelPct = sticky?.let(::levelPct),
            status = sticky?.intExtra(BatteryManager.EXTRA_STATUS),
            plugType = sticky?.intExtra(BatteryManager.EXTRA_PLUGGED),
            health = sticky?.intExtra(BatteryManager.EXTRA_HEALTH),
            voltageMv = sticky?.intExtra(BatteryManager.EXTRA_VOLTAGE),
            tempDeciC = sticky?.intExtra(BatteryManager.EXTRA_TEMPERATURE),
            cycleCount = sticky?.intExtra(BatteryManager.EXTRA_CYCLE_COUNT),
            currentRaw = property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            currentAverageRaw = property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            chargeUah = property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 },
            energyNwh = property(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
            screenOn = power.isInteractive,
            dozeState =
                when {
                    power.isDeviceIdleMode -> DEEP_DOZE
                    power.isDeviceLightIdleMode -> LIGHT_DOZE
                    else -> 0
                },
            powerSave = power.isPowerSaveMode,
            thermalStatus = power.currentThermalStatus,
        )
    }

    /** Null when unsupported; the call can also fail when the battery service is restarting. */
    private fun property(id: Int): Long? =
        runCatching { battery.getLongProperty(id) }
            .getOrNull()
            ?.takeIf { it != Long.MIN_VALUE }

    private fun Intent.intExtra(name: String): Int? = if (hasExtra(name)) getIntExtra(name, 0) else null

    private fun levelPct(intent: Intent): Int? {
        val level = intent.intExtra(BatteryManager.EXTRA_LEVEL) ?: return null
        val scale = intent.intExtra(BatteryManager.EXTRA_SCALE)?.takeIf { it > 0 } ?: return null
        return level * PERCENT / scale
    }

    private companion object {
        const val PERCENT = 100
        const val LIGHT_DOZE = 1
        const val DEEP_DOZE = 2
    }
}

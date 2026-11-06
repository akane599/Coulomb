// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

/**
 * The light data of SPEC 7.2: S1 (`ACTION_BATTERY_CHANGED`), S2 (`BatteryManager` properties) and
 * screen, Doze and thermal state. A value the device did not report is null; S2's "not supported"
 * marker (`Long.MIN_VALUE`) is already mapped to null.
 */
public data class LightReading(
    val elapsedNs: Long,
    val wallMs: Long,
    val levelPct: Int?,
    /** `BatteryManager.BATTERY_STATUS_*`. */
    val status: Int?,
    /** `BatteryManager.BATTERY_PLUGGED_*`; 0 when unplugged. */
    val plugType: Int?,
    val health: Int?,
    val voltageMv: Int?,
    val tempDeciC: Int?,
    val cycleCount: Int?,
    /** `CURRENT_NOW` as reported, before calibration (SPEC 6.1). */
    val currentRaw: Long?,
    val currentAverageRaw: Long?,
    /** `CHARGE_COUNTER` in µAh; null when unsupported (`Long.MIN_VALUE` or 0 or less). */
    val chargeUah: Long?,
    val energyNwh: Long?,
    val screenOn: Boolean,
    /** 0 not idle, 1 light Doze, 2 deep Doze. */
    val dozeState: Int,
    val powerSave: Boolean,
    /** `PowerManager.THERMAL_STATUS_*`. */
    val thermalStatus: Int,
) {
    val plugged: Boolean get() = (plugType ?: 0) != 0
}

/** Where light readings come from; the Android one reads S1, S2 and `PowerManager`. */
public fun interface LightSource {
    public fun read(): LightReading
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.dumpsys

/**
 * `dumpsys battery` (S21): the battery service's copy of the health HAL's `HealthInfo`, plus
 * whatever the OEM adds.
 *
 * The typed fields are those `BatteryService.dumpInternal` prints (frameworks/base
 * services/core/java/com/android/server/BatteryService.java, android-16.0.0_r4 L1621-1658,
 * android-17.0.0_r1 L1628-1665, the same text in both), with `HealthInfo`'s units
 * (hardware/interfaces health/aidl/android/hardware/health/HealthInfo.aidl L37-146). A field is
 * null when its line was missing or unreadable.
 */
public data class BatteryServiceDump(
    /**
     * Someone froze the values with `dumpsys battery set` or `unplug`: every typed field is then
     * what they set, not what the hardware reports.
     */
    val updatesStopped: Boolean,
    val acPowered: Boolean?,
    val usbPowered: Boolean?,
    val wirelessPowered: Boolean?,
    val dockPowered: Boolean?,
    val maxChargingCurrentUa: Int?,
    val maxChargingVoltageUv: Int?,
    val chargeCounterUah: Int?,
    /** `BatteryManager.BATTERY_STATUS_*`. */
    val status: Int?,
    /** `BatteryManager.BATTERY_HEALTH_*`. */
    val health: Int?,
    val present: Boolean?,
    val level: Int?,
    val scale: Int?,
    val voltageMv: Int?,
    val temperatureDeciC: Int?,
    val technology: String?,
    /** `BatteryManager.CHARGING_STATE_*` (health HAL `BatteryChargingState`). */
    val chargingState: Int?,
    /** `BatteryManager.CHARGING_POLICY_*` (health HAL `BatteryChargingPolicy`). */
    val chargingPolicy: Int?,
    /** Health HAL `BatteryCapacityLevel`; -1 when the HAL does not report it. */
    val capacityLevel: Int?,
    /**
     * The OEM's other `key: value` lines, in dump order, values as printed (SPEC 5.1 raw map).
     * Timestamped log lines are not here. When a key repeats, the first value is kept.
     */
    val extras: Map<String, String>,
)

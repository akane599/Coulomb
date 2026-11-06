// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.dumpsys

/**
 * `dumpsys thermalservice` (S19), as `ThermalManagerService.dumpInternal` prints it (frameworks/base
 * services/core/java/com/android/server/power/thermal/ThermalManagerService.java,
 * android-16.0.0_r4 L894-933, android-17.0.0_r1 L978-1026).
 */
public data class ThermalDump(
    /**
     * `cmd thermalservice override-status` is in force: [status] is what was set, not what the
     * sensors say. Null when the line was missing.
     */
    val statusOverridden: Boolean?,
    /** `PowerManager.THERMAL_STATUS_*`: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown. */
    val status: Int?,
    /** False when the thermal HAL is not connected; the HAL sections are then absent. */
    val halReady: Boolean?,
    /**
     * The service's cached readings, which change only when the HAL reports a new throttling
     * status; they can be stale by many degrees.
     */
    val cachedTemperatures: List<ThermalReading>,
    /** Read from the HAL while dumping: the current values. Empty when the HAL is not ready. */
    val currentTemperatures: List<ThermalReading>,
    val coolingDevices: List<CoolingReading>,
    val thresholds: List<ThermalThreshold>,
)

/**
 * One `android.os.Temperature` (core/java/android/os/Temperature.java `toString` L182-185, both tags).
 *
 * [type] is the thermal HAL's `TemperatureType` (hardware/interfaces
 * thermal/aidl/android/hardware/thermal/TemperatureType.aidl L26-52, both tags): -1 unknown, 0 CPU,
 * 1 GPU, 2 battery, 3 skin, 4 USB port, 5 power amplifier, 6-8 battery current limiter, 9 NPU,
 * 10 TPU, 11 display, 12 modem, 13 SoC, 14 Wi-Fi, 15 camera, 16 flashlight, 17 speaker, 18 ambient,
 * 19 pogo. [status] is the sensor's own throttling severity, 0 none to 6 shutdown.
 */
public data class ThermalReading(
    val name: String,
    val type: Int,
    /** Rounded to 0.1 °C; null when the HAL reports no value (NaN). */
    val deciC: Int?,
    val status: Int,
)

/**
 * One `android.os.CoolingDevice` (core/java/android/os/CoolingDevice.java `toString` L144-146, both
 * tags): [value] is its throttling step, 0 when idle.
 */
public data class CoolingReading(
    val name: String,
    val type: Int,
    val value: Long,
)

/**
 * A sensor's static thresholds, indexed by throttling severity 0 (none) to 6 (shutdown). An entry
 * is null where the HAL sets none (NaN).
 */
public data class ThermalThreshold(
    val name: String,
    val type: Int,
    val hotDeciC: List<Int?>,
    val coldDeciC: List<Int?>,
)

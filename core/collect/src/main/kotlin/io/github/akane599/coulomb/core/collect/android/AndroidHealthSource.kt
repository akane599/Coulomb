// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.BatteryManager
import io.github.akane599.coulomb.core.collect.engine.HealthReadings
import io.github.akane599.coulomb.core.collect.engine.HealthSource
import io.github.akane599.coulomb.core.model.BatteryDate
import io.github.akane599.coulomb.core.model.BatteryDateSource
import io.github.akane599.coulomb.core.model.BatteryDates
import io.github.akane599.coulomb.core.model.DesignCapacity
import io.github.akane599.coulomb.core.model.DesignCapacitySource
import io.github.akane599.coulomb.core.model.dumpsys.OemBattery
import io.github.akane599.coulomb.core.parsing.dumpsys.BatteryServiceParser
import io.github.akane599.coulomb.core.parsing.dumpsys.OemBatteryReader
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.privileged.PrivilegedResult
import io.github.akane599.coulomb.core.privileged.SysfsRoot
import io.github.akane599.coulomb.core.privileged.SystemService
import org.xmlpull.v1.XmlPullParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * ADR-0042. The design capacity from the framework's power profile, read through the app's own
 * resources, else from the kernel through the worker; the system's state of health and the OEM's
 * values beside it.
 */
internal class AndroidHealthSource(
    private val context: Context,
    private val access: PrivilegedAccess,
) : HealthSource {
    override suspend fun designCapacity(): DesignCapacity? =
        powerProfileMah()?.let { DesignCapacity(it, DesignCapacitySource.POWER_PROFILE) }
            ?: sysfsMah()?.let { DesignCapacity(it, DesignCapacitySource.SYSFS) }

    override suspend fun readings(): HealthReadings = HealthReadings(systemSoh(), oem().health)

    /** The OEM's facts from S21, for the Health screen's first-use date as well (ADR-0042 §3). */
    suspend fun oem(): OemBattery {
        val result =
            access.dumpService(SystemService.BATTERY) { input -> BatteryServiceParser.parse(input).value }
        val dump = (result as? DumpResult.Complete)?.value ?: return OemBattery.NONE
        // Values frozen by `dumpsys battery set` are not the hardware's.
        return if (dump.updatesStopped) OemBattery.NONE else OemBatteryReader.read(dump.extras)
    }

    /**
     * S3's manufacture and first-use dates, the first-use date from S21 when S3 has none
     * (ADR-0044 §4). S21 is read only then, and only when the worker is up.
     */
    suspend fun dates(today: LocalDate): BatteryDates {
        val manufactured = systemDate(PROPERTY_MANUFACTURING_DATE, today)
        val firstUsed =
            systemDate(PROPERTY_FIRST_USAGE_DATE, today)
                ?: oem().firstUseDate?.takeIf { it in BatteryDates.EARLIEST..today }?.let { BatteryDate(it, BatteryDateSource.OEM) }
        return BatteryDates(manufactured, firstUsed)
    }

    /**
     * S3 `BATTERY_PROPERTY_MANUFACTURING_DATE` = 7 and `BATTERY_PROPERTY_FIRST_USAGE_DATE` = 8,
     * `@SystemApi`, BATTERY_STATS: seconds since the epoch in UTC, through `getLongProperty`
     * (core/java/android/os/BatteryManager.java: android-16.0.0_r4 L454 and L469, android-17.0.0_r1
     * L483 and L498, the unit in the KDoc above each). An unsupported property gives `Long.MIN_VALUE` or 0; a date before
     * [BatteryDates.EARLIEST] or after [today] is a placeholder.
     */
    private fun systemDate(
        property: Int,
        today: LocalDate,
    ): BatteryDate? {
        if (!access.state.value.grants.batteryStats) return null
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val seconds = runCatching { manager.getLongProperty(property) }.getOrNull()?.takeIf { it > 0 } ?: return null
        val date = Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC).toLocalDate()
        return date.takeIf { it in BatteryDates.EARLIEST..today }?.let { BatteryDate(it, BatteryDateSource.SYSTEM) }
    }

    /**
     * S20. `PowerProfile` reads `<item name="battery.capacity">` under `<device>` in
     * `com.android.internal.R.xml.power_profile` through the context's resources, so device
     * overlays apply (frameworks/base core/java/com/android/internal/os/PowerProfile.java:
     * identical at android-16.0.0_r4 and android-17.0.0_r1: L246 the key, L345-346 the resource,
     * L384-388 the read, L401-440 the walk). The app's resources resolve the same framework resource, overlays
     * included, with no hidden API. A missing resource or a placeholder value gives null.
     */
    @SuppressLint("DiscouragedApi") // The framework's power profile has no public R constant.
    private fun powerProfileMah(): Double? =
        runCatching {
            val resources = context.resources
            val id = resources.getIdentifier(POWER_PROFILE, "xml", ANDROID)
            if (id == 0) null else readCapacity(resources, id)
        }.getOrNull()?.takeIf { it in DesignCapacity.PLAUSIBLE_MAH }

    private fun readCapacity(
        resources: Resources,
        id: Int,
    ): Double? =
        resources.getXml(id).use { parser ->
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && !parser.isCapacityItem(event)) event = parser.next()
            if (event != XmlPullParser.END_DOCUMENT && parser.next() == XmlPullParser.TEXT) parser.text.trim().toDoubleOrNull() else null
        }

    private fun XmlPullParser.isCapacityItem(event: Int): Boolean =
        event == XmlPullParser.START_TAG && name == ITEM && getAttributeValue(null, NAME) == BATTERY_CAPACITY

    /**
     * S23: `charge_full_design` in µAh (kernel Documentation/ABI/testing/sysfs-class-power), where
     * the worker can read it: shell on some devices, root on others (SPEC 5, S23). healthd reads the
     * same file under each battery supply for the Health HAL's design capacity
     * (system/core healthd/BatteryMonitor.cpp, android-16.0.0_r4 L918-923, android-17.0.0_r1 L964-969).
     */
    private suspend fun sysfsMah(): Double? {
        val result = access.readSysfs(SysfsRoot.POWER_SUPPLY, CHARGE_FULL_DESIGN)
        val uah =
            (result as? PrivilegedResult.Success)
                ?.value
                ?.decodeToString()
                ?.trim()
                ?.toLongOrNull() ?: return null
        return (uah / UAH_PER_MAH).takeIf { it in DesignCapacity.PLAUSIBLE_MAH }
    }

    /**
     * S3 `BATTERY_PROPERTY_STATE_OF_HEALTH` = 10, `@SystemApi`, BATTERY_STATS (BatteryManager.java
     * android-16.0.0_r4 L489, android-17.0.0_r1 L518); the same check as the capability probe.
     */
    private fun systemSoh(): Int? {
        if (!access.state.value.grants.batteryStats) return null
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        return runCatching { manager.getIntProperty(PROPERTY_STATE_OF_HEALTH) }.getOrNull()?.takeIf { it in 1..MAX_SOH }
    }

    private companion object {
        const val POWER_PROFILE = "power_profile"
        const val ANDROID = "android"
        const val ITEM = "item"
        const val NAME = "name"
        const val BATTERY_CAPACITY = "battery.capacity"
        const val CHARGE_FULL_DESIGN = "battery/charge_full_design"
        const val UAH_PER_MAH = 1000.0
        const val PROPERTY_MANUFACTURING_DATE = 7
        const val PROPERTY_FIRST_USAGE_DATE = 8
        const val PROPERTY_STATE_OF_HEALTH = 10
        const val MAX_SOH = 150
    }
}

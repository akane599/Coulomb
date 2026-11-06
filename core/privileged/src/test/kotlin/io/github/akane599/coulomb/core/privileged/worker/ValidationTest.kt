// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.worker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ValidationTest {
    @Test
    fun packageNames() {
        assertThat(Validation.isPackageName("io.github.akane599.coulomb")).isTrue()
        assertThat(Validation.isPackageName("com.android.systemui")).isTrue()
        listOf(null, "", "single", "-flag.inject", "a..b", "a.b;rm", "a.b c", "1a.b", "a.b/../c", "a." + "b".repeat(255)).forEach {
            assertThat(Validation.isPackageName(it)).isFalse()
        }
    }

    @Test
    fun onlyBatteryRelatedSettingsKeys() {
        listOf(
            "protect_battery",
            "adaptive_charging_enabled",
            "low_power",
            "battery_saver_constants",
            "psm_switch",
            "device_idle_constants",
        ).forEach {
            assertThat(Validation.isBatterySettingsKey(it)).isTrue()
        }
        listOf(
            null,
            "",
            "android_id",
            "bluetooth_name",
            "-battery",
            "battery key",
            "default_input_method",
            "x".repeat(200) + "battery",
        ).forEach {
            assertThat(Validation.isBatterySettingsKey(it)).isFalse()
        }
    }

    @Test
    fun sysfsSegmentsRejectTraversal() {
        assertThat(Validation.sysfsSegments("battery/charge_full", allowEmpty = false)).containsExactly("battery", "charge_full").inOrder()
        assertThat(Validation.sysfsSegments("", allowEmpty = true)).isEmpty()
        listOf(null, "", "..", "battery/..", "./battery", "/battery", "battery//x", "a/b/c/d", "bat tery", "battery/\u0000").forEach {
            assertThat(Validation.sysfsSegments(it, allowEmpty = false)).isNull()
        }
    }

    @Test
    fun sysfsReadsStayInsideTheClassEntry() {
        val battery = "/sys/devices/platform/soc/battery/power_supply/battery"
        assertThat(Validation.isContained(battery, battery)).isTrue()
        assertThat(Validation.isContained(battery, "$battery/charge_full")).isTrue()
        // battery/device/serial resolves to the parent device: outside the entry.
        assertThat(Validation.isContained(battery, "/sys/devices/platform/soc/battery/serial")).isFalse()
        // A sibling whose name merely starts with the entry's name.
        assertThat(Validation.isContained(battery, "${battery}2/capacity")).isFalse()
    }
}

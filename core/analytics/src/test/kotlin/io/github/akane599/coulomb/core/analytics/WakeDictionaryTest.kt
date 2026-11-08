// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.analytics.WakeSubsystem.ANDROID
import io.github.akane599.coulomb.core.analytics.WakeSubsystem.BATTERY
import io.github.akane599.coulomb.core.analytics.WakeSubsystem.BLUETOOTH
import io.github.akane599.coulomb.core.analytics.WakeSubsystem.FINGERPRINT
import io.github.akane599.coulomb.core.analytics.WakeSubsystem.GPS
import io.github.akane599.coulomb.core.analytics.WakeSubsystem.INPUT
import io.github.akane599.coulomb.core.analytics.WakeSubsystem.MOBILE_NETWORK
import io.github.akane599.coulomb.core.analytics.WakeSubsystem.NFC
import io.github.akane599.coulomb.core.analytics.WakeSubsystem.OTHER
import io.github.akane599.coulomb.core.analytics.WakeSubsystem.SENSORS
import io.github.akane599.coulomb.core.analytics.WakeSubsystem.TIMERS
import io.github.akane599.coulomb.core.analytics.WakeSubsystem.WIFI
import io.github.akane599.coulomb.core.model.WakeKind
import org.junit.Test

class WakeDictionaryTest {
    /** Kernel wakelock names from the sm-s938b fixture's `kwl` lines and the emulator's. */
    @Test
    fun fixtureNamesFallIntoTheirSubsystems() {
        val expected =
            mapOf(
                "PowerManagerService.WakeLocks" to ANDROID,
                "PowerManagerService.Display" to ANDROID,
                "rmnet_ipa%d" to MOBILE_NETWORK,
                "IPA_CLIENT_APPS_WAN_COAL_CONS" to MOBILE_NETWORK,
                "IPA_WS" to MOBILE_NETWORK,
                "rmnet_ctl" to MOBILE_NETWORK,
                "RMNET_SHS" to MOBILE_NETWORK,
                "radio-interface" to MOBILE_NETWORK,
                "TSP" to INPUT,
                "wacom_wakelock" to INPUT,
                "soc:gpio_keys" to INPUT,
                "event2" to INPUT,
                "sec-battery-monitor" to BATTERY,
                "battery" to BATTERY,
                "wpc_rx_ws" to BATTERY,
                "alarmtimer.0.auto" to TIMERS,
                "[timerfd]" to TIMERS,
                "gnss_hal" to GPS,
                "hal_bluetooth_lock" to BLUETOOTH,
                "bluetooth_timer" to BLUETOOTH,
                "nfc_wake_lock" to NFC,
                "SensorService_wakelock" to SENSORS,
                "SensorsHAL_WAKEUP" to SENSORS,
                "wlan_wow_wl" to WIFI,
                "279 qbt_finger_detect:347 synaptics_ts" to FINGERPRINT,
                "fingerprint_sensor" to FINGERPRINT,
                "425 ipa" to MOBILE_NETWORK,
                // No word names a part: these stay Other rather than take a guess.
                "SuspendControl.TotalSuspendDelay" to OTHER,
                "qcom_rx_wakelock" to OTHER,
                "smp2p-sleepstate" to OTHER,
                "qfs4008_spi_lock" to OTHER,
                "NETLINK" to OTHER,
                "eventpoll" to OTHER,
                "" to OTHER,
            )
        expected.forEach { (name, subsystem) -> assertWithMessage(name).that(WakeDictionary.subsystem(name)).isEqualTo(subsystem) }
    }

    @Test
    fun partialWakelocksOfAndroidsSchedulersAreKnown() {
        assertThat(WakeDictionary.meaning(WakeKind.PARTIAL, "*alarm*")).isEqualTo(WakeMeaning.ALARM_DELIVERY)
        // An alarm's own tag reaches only the history, never a per-app record.
        assertThat(WakeDictionary.meaning(WakeKind.PARTIAL, "*walarm*:com.example.SYNC")).isNull()
        assertThat(WakeDictionary.meaning(WakeKind.PARTIAL, "*job*r/com.example/.SyncJob")).isEqualTo(WakeMeaning.JOB)
        assertThat(WakeDictionary.meaning(WakeKind.PARTIAL, "*sync*/com.example.provider/com.example/42")).isEqualTo(WakeMeaning.SYNC)
        assertThat(WakeDictionary.meaning(WakeKind.PARTIAL, "AudioMix")).isNull()
        // A kernel record with the same text is not an app's wakelock.
        assertThat(WakeDictionary.meaning(WakeKind.KERNEL, "*alarm*")).isNull()
    }

    @Test
    fun kernelNamesFromAndroidAndTheKernelAreKnown() {
        val known =
            mapOf(
                "PowerManagerService.WakeLocks" to WakeMeaning.ANDROID_WAKELOCKS,
                "PowerManagerService.Display" to WakeMeaning.ANDROID_DISPLAY,
                "PowerManagerService.Broadcasts" to WakeMeaning.ANDROID_BROADCASTS,
                "PowerManagerService.Booting" to WakeMeaning.ANDROID_BOOTING,
                "SensorService_wakelock" to WakeMeaning.SENSOR_SERVICE,
                "alarmtimer.0.auto" to WakeMeaning.ALARM_TIMER,
                "[timerfd]" to WakeMeaning.WAKEUP_TIMER,
                "event7" to WakeMeaning.INPUT_DEVICE,
            )
        known.forEach { (name, meaning) ->
            assertWithMessage(name).that(WakeDictionary.meaning(WakeKind.KERNEL, name)).isEqualTo(meaning)
            assertWithMessage(name).that(WakeDictionary.meaning(WakeKind.WAKEUP_SOURCE, name)).isEqualTo(meaning)
        }
        listOf("qcom_rx_wakelock", "PowerManager.SuspendLockout", "events", "eventpoll", "SensorsHAL_WAKEUP").forEach {
            assertWithMessage(it).that(WakeDictionary.meaning(WakeKind.KERNEL, it)).isNull()
        }
    }

    /** Wakeup reasons from the sm-s938b fixture's `wr` lines. */
    @Test
    fun wakeupReasonsReadAsAbandonedSuspendsOrInterrupts() {
        val reasons =
            mapOf(
                "Abort: Pending Wakeup Sources: IPA_CLIENT_APPS_LAN_CONS" to WakeMeaning.SLEEP_BLOCKED,
                "Abort: Last active Wakeup Source: qrtr_ws" to WakeMeaning.SLEEP_INTERRUPTED,
                "Abort: Some devices failed to suspend\t or early wake event detected" to WakeMeaning.SLEEP_ABORTED,
                "Abort: Device 0000:01:00.0 failed to suspend: error -11" to WakeMeaning.SLEEP_ABORTED,
                "425 ipa" to WakeMeaning.INTERRUPT,
                "279 qbt_finger_detect:347 synaptics_ts" to WakeMeaning.INTERRUPT,
                "unknown" to null,
                "-1 watchdog" to null,
            )
        reasons.forEach { (name, meaning) ->
            assertWithMessage(name).that(WakeDictionary.meaning(WakeKind.WAKEUP_REASON, name)).isEqualTo(meaning)
        }
        // KernelWindow stores resume causes under bare interrupt names or other reasons alike.
        assertThat(WakeDictionary.meaning(WakeKind.RESUME_CAUSE, "qcom,smp2p-modem")).isNull()
        assertThat(WakeDictionary.meaning(WakeKind.SUSPEND_ABORT, "anything")).isEqualTo(WakeMeaning.SLEEP_ABORTED)
    }
}

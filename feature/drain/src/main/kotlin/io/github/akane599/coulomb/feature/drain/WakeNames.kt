// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.annotation.StringRes
import io.github.akane599.coulomb.core.analytics.WakeMeaning
import io.github.akane599.coulomb.core.analytics.WakeSubsystem

/** The subsystem's name as a Kernel wakelocks sub-heading (SPEC 10.3). */
@get:StringRes
internal val WakeSubsystem.label: Int
    get() =
        when (this) {
            WakeSubsystem.MOBILE_NETWORK -> R.string.drain_subsystem_mobile_network
            WakeSubsystem.WIFI -> R.string.drain_subsystem_wifi
            WakeSubsystem.BLUETOOTH -> R.string.drain_subsystem_bluetooth
            WakeSubsystem.GPS -> R.string.drain_subsystem_gps
            WakeSubsystem.NFC -> R.string.drain_subsystem_nfc
            WakeSubsystem.INPUT -> R.string.drain_subsystem_input
            WakeSubsystem.FINGERPRINT -> R.string.drain_subsystem_fingerprint
            WakeSubsystem.SENSORS -> R.string.drain_subsystem_sensors
            WakeSubsystem.BATTERY -> R.string.drain_subsystem_battery
            WakeSubsystem.AUDIO -> R.string.drain_subsystem_audio
            WakeSubsystem.CAMERA -> R.string.drain_subsystem_camera
            WakeSubsystem.TIMERS -> R.string.drain_subsystem_timers
            WakeSubsystem.ANDROID -> R.string.drain_subsystem_android
            WakeSubsystem.OTHER -> R.string.drain_subsystem_other
        }

/** The dictionary's plain-language line for a record (SPEC 10.3). */
@get:StringRes
internal val WakeMeaning.text: Int
    get() =
        when (this) {
            WakeMeaning.ANDROID_WAKELOCKS -> R.string.drain_meaning_android_wakelocks
            WakeMeaning.ANDROID_DISPLAY -> R.string.drain_meaning_android_display
            WakeMeaning.ANDROID_BROADCASTS -> R.string.drain_meaning_android_broadcasts
            WakeMeaning.ANDROID_BOOTING -> R.string.drain_meaning_android_booting
            WakeMeaning.SENSOR_SERVICE -> R.string.drain_meaning_sensor_service
            WakeMeaning.ALARM_TIMER -> R.string.drain_meaning_alarm_timer
            WakeMeaning.WAKEUP_TIMER -> R.string.drain_meaning_wakeup_timer
            WakeMeaning.INPUT_DEVICE -> R.string.drain_meaning_input_device
            WakeMeaning.ALARM_DELIVERY -> R.string.drain_meaning_alarm_delivery
            WakeMeaning.JOB -> R.string.drain_meaning_job
            WakeMeaning.SYNC -> R.string.drain_meaning_sync
            WakeMeaning.SLEEP_BLOCKED -> R.string.drain_meaning_sleep_blocked
            WakeMeaning.SLEEP_INTERRUPTED -> R.string.drain_meaning_sleep_interrupted
            WakeMeaning.SLEEP_ABORTED -> R.string.drain_meaning_sleep_aborted
            WakeMeaning.INTERRUPT -> R.string.drain_meaning_interrupt
        }

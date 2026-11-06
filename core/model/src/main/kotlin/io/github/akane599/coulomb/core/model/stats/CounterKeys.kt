// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.stats

/**
 * Keys of the per-UID counters stored as `uid_stat` rows (ADR-0018). Each value is cumulative since
 * the platform's statistics started, in the unit its name ends with. Keys are stored text: add new
 * ones, never rename.
 */
public object UidKeys {
    public const val CPU_USER_MS: String = "cpu_user_ms"
    public const val CPU_SYSTEM_MS: String = "cpu_system_ms"

    /** Partial wakelock time with overlaps between tags removed. */
    public const val PARTIAL_WAKELOCK_MS: String = "partial_wakelock_ms"
    public const val BACKGROUND_PARTIAL_WAKELOCK_MS: String = "bg_partial_wakelock_ms"
    public const val FOREGROUND_ACTIVITY_MS: String = "fg_activity_ms"
    public const val FOREGROUND_SERVICE_MS: String = "fg_service_ms"
    public const val AUDIO_MS: String = "audio_ms"
    public const val VIDEO_MS: String = "video_ms"
    public const val CAMERA_MS: String = "camera_ms"
    public const val FLASHLIGHT_MS: String = "flashlight_ms"
    public const val VIBRATOR_MS: String = "vibrator_ms"
    public const val SYNC_MS: String = "sync_ms"
    public const val GNSS_MS: String = "gnss_ms"

    /** Every sensor but GNSS. */
    public const val SENSOR_MS: String = "sensor_ms"
    public const val BACKGROUND_SENSOR_MS: String = "bg_sensor_ms"
    public const val WIFI_RUNNING_MS: String = "wifi_running_ms"
    public const val WIFI_SCAN_MS: String = "wifi_scan_ms"
    public const val WIFI_SCAN_COUNT: String = "wifi_scan_count"
    public const val BLE_SCAN_MS: String = "ble_scan_ms"
    public const val BACKGROUND_BLE_SCAN_MS: String = "bg_ble_scan_ms"
    public const val UNOPTIMIZED_BLE_SCAN_MS: String = "unoptimized_ble_scan_ms"
    public const val MOBILE_WAKEUPS: String = "mobile_wakeups"
    public const val WIFI_WAKEUPS: String = "wifi_wakeups"
    public const val MOBILE_ACTIVE_COUNT: String = "mobile_active_count"

    /** Time in one `BatteryStats` process state, such as `proc_top_ms`. */
    public fun processState(state: UidProcessState): String = "proc_${state.name.lowercase()}_ms"

    // Window keys for the counters that have their own snapshot tables (`window_stat` only).

    public const val ALARM_WAKEUPS: String = "alarm_wakeups"
    public const val JOB_COUNT: String = "job_count"
    public const val JOB_RUNTIME_MS: String = "job_runtime_ms"
    public const val MOBILE_RX_BYTES: String = "mobile_rx_b"
    public const val MOBILE_TX_BYTES: String = "mobile_tx_b"
    public const val WIFI_RX_BYTES: String = "wifi_rx_b"
    public const val WIFI_TX_BYTES: String = "wifi_tx_b"
    public const val RADIO_ACTIVE_MS: String = "radio_active_ms"
}

/** Keys of the device-wide counters stored as `device_stat` rows (ADR-0018); as [UidKeys]. */
public object DeviceKeys {
    public const val START_COUNT: String = "start_count"
    public const val TOTAL_REALTIME_MS: String = "total_realtime_ms"
    public const val TOTAL_UPTIME_MS: String = "total_uptime_ms"
    public const val BATTERY_REALTIME_MS: String = "battery_realtime_ms"
    public const val BATTERY_UPTIME_MS: String = "battery_uptime_ms"
    public const val SCREEN_OFF_REALTIME_MS: String = "screen_off_realtime_ms"
    public const val SCREEN_OFF_UPTIME_MS: String = "screen_off_uptime_ms"
    public const val SCREEN_DOZE_MS: String = "screen_doze_ms"
    public const val SCREEN_ON_MS: String = "screen_on_ms"
    public const val INTERACTIVE_MS: String = "interactive_ms"
    public const val PHONE_ON_MS: String = "phone_on_ms"
    public const val PARTIAL_WAKELOCK_MS: String = "partial_wakelock_ms"
    public const val MOBILE_RADIO_ACTIVE_MS: String = "mobile_radio_active_ms"
    public const val MOBILE_RADIO_ACTIVE_COUNT: String = "mobile_radio_active_count"
    public const val SIGNAL_SCANNING_MS: String = "signal_scanning_ms"
    public const val BATTERY_SAVER_MS: String = "battery_saver_ms"
    public const val DEEP_DOZE_MS: String = "deep_doze_ms"
    public const val DEEP_DOZE_COUNT: String = "deep_doze_count"
    public const val LIGHT_DOZE_MS: String = "light_doze_ms"
    public const val LIGHT_DOZE_COUNT: String = "light_doze_count"
    public const val DISCHARGE_MAH: String = "discharge_mah"
    public const val DISCHARGE_SCREEN_OFF_MAH: String = "discharge_screen_off_mah"
    public const val DISCHARGE_SCREEN_DOZE_MAH: String = "discharge_screen_doze_mah"
    public const val DISCHARGE_LIGHT_DOZE_MAH: String = "discharge_light_doze_mah"
    public const val DISCHARGE_DEEP_DOZE_MAH: String = "discharge_deep_doze_mah"

    /** The power model's total of everything it computed, in µAh. */
    public const val MODELED_UAH: String = "modeled_uah"

    /** Time in one phone signal bin, 0 (none) to 4 (great). */
    public fun signalBin(bin: Int): String = "signal_bin_${bin}_ms"

    /** Time in one screen brightness bin, 0 (dark) to 4 (bright). */
    public fun brightnessBin(bin: Int): String = "brightness_bin_${bin}_ms"
}

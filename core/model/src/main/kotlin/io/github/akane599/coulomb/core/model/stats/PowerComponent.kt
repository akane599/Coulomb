// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.stats

/**
 * A power component as Drain names it (SPEC 6.5). S5 names the device's items by the old sipper
 * kinds and leaves the rest unnamed ([OTHER]); S4 names every standard component (ADR-0035).
 */
public enum class PowerComponent {
    SCREEN,
    CPU,
    MOBILE_RADIO,
    WIFI,
    BLUETOOTH,
    GNSS,
    SENSORS,
    CAMERA,
    AUDIO,
    VIDEO,
    FLASHLIGHT,
    WAKELOCK,
    MEMORY,
    PHONE,
    AMBIENT_DISPLAY,
    IDLE,
    SYSTEM_SERVICES,
    CUSTOM,
    OTHER,
    ;

    public companion object {
        /** S4's column as Drain names it; the process-state columns are not components. */
        public fun of(column: PowerColumn): PowerComponent {
            require(!column.isProcessState) { "$column is a process state" }
            // Every component column has the component's name.
            return valueOf(column.name)
        }

        /**
         * The component a stored row names: S5's sipper kinds (`SipperKind`, upper case) or the
         * platform's component names (`BatteryConsumer.sPowerComponentNames`, lower case).
         */
        public fun ofStored(name: String): PowerComponent =
            when (name) {
                "SCREEN", "screen" -> SCREEN
                "cpu" -> CPU
                "CELL", "mobile_radio" -> MOBILE_RADIO
                "WIFI", "wifi" -> WIFI
                "BLUETOOTH", "bluetooth" -> BLUETOOTH
                "gnss" -> GNSS
                "sensors" -> SENSORS
                "CAMERA", "camera" -> CAMERA
                "audio" -> AUDIO
                "video" -> VIDEO
                "FLASHLIGHT", "flashlight" -> FLASHLIGHT
                "wakelock" -> WAKELOCK
                "MEMORY", "memory" -> MEMORY
                "PHONE", "phone" -> PHONE
                "AMBIENT_DISPLAY", "ambient_display" -> AMBIENT_DISPLAY
                "IDLE", "idle" -> IDLE
                "system_services" -> SYSTEM_SERVICES
                "custom" -> CUSTOM
                else -> OTHER
            }
    }
}

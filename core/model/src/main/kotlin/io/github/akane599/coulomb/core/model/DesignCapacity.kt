// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model

/**
 * Where the design capacity came from, in SPEC 6.3's order (ADR-0042 §1). [code] is what
 * `device_profile.designCapacitySource` stores.
 */
public enum class DesignCapacitySource(
    public val code: String,
) {
    /** The framework's power profile, `battery.capacity` (S20). */
    POWER_PROFILE("power_profile"),

    /** The kernel's `charge_full_design` under `/sys/class/power_supply` (S23). */
    SYSFS("sysfs"),

    /** Typed in by the owner; never replaced by a read. */
    USER("user"),
    ;

    public companion object {
        public fun fromCode(code: String?): DesignCapacitySource? = entries.firstOrNull { it.code == code }
    }
}

/** A design capacity in mAh with its [source]. */
public data class DesignCapacity(
    val mah: Double,
    val source: DesignCapacitySource,
) {
    public companion object {
        /** No phone battery is outside this; a value outside it is a placeholder (ADR-0042 §1). */
        public val PLAUSIBLE_MAH: ClosedFloatingPointRange<Double> = 500.0..20_000.0
    }
}

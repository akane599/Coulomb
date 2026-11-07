// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model

/** Where a number comes from (SPEC 6.7). Every metric shown carries one. Stored by name: add, never rename. */
public enum class Provenance {
    /** A direct counter from the hardware, the kernel or the framework, such as a coulomb delta, power rail energy or wakelock time. */
    MEASURED,

    /**
     * The output of a model Coulomb does not own: per-app mAh from `BatteryUsageStats`, or the fuel gauge's
     * level, state of health and capacity (ADR-0023).
     */
    MODELED,

    /** Coulomb's statistical estimate, such as capacity, time remaining or wear stress. */
    ESTIMATED,

    /** Arithmetic on the above, including any scaling. */
    DERIVED,
}

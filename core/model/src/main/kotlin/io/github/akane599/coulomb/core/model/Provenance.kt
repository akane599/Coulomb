// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model

/** Where a number comes from (SPEC 6.7). Every metric shown carries one. Stored by name: add, never rename. */
public enum class Provenance {
    /** Direct hardware counter, such as a coulomb delta or power rail energy. */
    MEASURED,

    /** Platform power model output, such as per-app mAh from `BatteryUsageStats`. */
    MODELED,

    /** Coulomb's statistical estimate, such as capacity, time remaining or wear stress. */
    ESTIMATED,

    /** Arithmetic on the above, including any scaling. */
    DERIVED,
}

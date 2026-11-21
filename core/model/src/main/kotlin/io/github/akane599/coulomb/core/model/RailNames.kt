// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model

/**
 * How an S16 power monitor's name is stored (ADR-0068): its type, then its name. A rail
 * (`POWER_MONITOR_TYPE_MEASUREMENT`, 1) is measured directly; an energy consumer
 * (`POWER_MONITOR_TYPE_CONSUMER`, 0) "may be a direct pass-through power rail measurement, or
 * modeled in some fashion" (PowerMonitor.java, android-16.0.0_r4 and android-17.0.0_r1 L43-58).
 */
public object RailNames {
    public const val CONSUMER: Int = 0
    public const val MEASUREMENT: Int = 1

    private const val RAIL = "rail:"
    private const val CONSUMER_PREFIX = "consumer:"

    public fun stored(
        type: Int,
        name: String,
    ): String = (if (type == MEASUREMENT) RAIL else CONSUMER_PREFIX) + name

    /** Whether a stored name is a directly measured rail. */
    public fun isRail(stored: String): Boolean = stored.startsWith(RAIL)

    /** The monitor's own name, as the device gives it. */
    public fun name(stored: String): String = stored.removePrefix(RAIL).removePrefix(CONSUMER_PREFIX)
}

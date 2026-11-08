// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.format

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/** SPEC 6.7 rounding. Pure, so each rule has a unit test. */
public object Rounding {
    private const val CURRENT_STEP_MA = 10.0
    private const val RATE_STEPS_PER_UNIT = 10.0
    private const val MINUTES_PER_HOUR = 60.0
    private const val LONG_STEP_MIN = 10L
    private const val SHORT_STEP_MIN = 1L

    /** Current to 10 mA; above 1 A that is 0.01 A, the same step. */
    public fun currentMa(ma: Double): Double = (ma / CURRENT_STEP_MA).roundToLong() * CURRENT_STEP_MA

    /** A rate to 0.1 %/h. */
    public fun rate(pctPerHour: Double): Double = (pctPerHour * RATE_STEPS_PER_UNIT).roundToLong() / RATE_STEPS_PER_UNIT

    /** Hours to whole minutes. */
    public fun minutes(hours: Double): Long = (hours * MINUTES_PER_HOUR).roundToLong()

    /**
     * The ends of a time-left range in minutes (SPEC 6.8). Each rounds outward to 10 minutes from
     * an hour up and to the minute below it, so rounding only widens the range and the two ends
     * never meet.
     */
    public fun timeLeftMinutes(
        shortestHours: Double,
        longestHours: Double,
    ): Pair<Long, Long> {
        val shortMinutes = (shortestHours * MINUTES_PER_HOUR).coerceAtLeast(0.0)
        val longMinutes = (longestHours * MINUTES_PER_HOUR).coerceAtLeast(shortMinutes)
        val shortStep = step(shortMinutes)
        val longStep = step(longMinutes)
        val shortest = floor(shortMinutes / shortStep).toLong() * shortStep
        val longest = ceil(longMinutes / longStep).toLong() * longStep
        return shortest to if (longest > shortest) longest else shortest + longStep
    }

    private fun step(minutes: Double): Long = if (minutes >= MINUTES_PER_HOUR) LONG_STEP_MIN else SHORT_STEP_MIN
}

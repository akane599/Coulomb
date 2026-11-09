// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChargeTimeTest {
    @Test
    fun pastChargesOnThisChargerGiveTheRange() {
        // Past charges from 20%: a minute a point to 80%, then three minutes a point to 100%.
        val history = listOf(30.0, 31.0, 32.0).map { tail -> PastCharge(true, line(20, minutesPerPoint = 1.0, tail = tail)) }
        // This charge ran 40% to 50% at the same pace and has just reached 50%.
        val current = line(40, 50, minutesPerPoint = 1.0)

        val toEighty = checkNotNull(ChargeTimes.toLevel(current, history, 80))
        assertThat(toEighty.basis).isEqualTo(ChargeTimeBasis.SAME_CHARGER)
        // 30 minutes in every past charge: widened to ±10%.
        assertThat(toEighty.shortestMin).isWithin(1e-6).of(27.0)
        assertThat(toEighty.longestMin).isWithin(1e-6).of(33.0)

        val toFull = checkNotNull(ChargeTimes.toLevel(current, history, 100))
        // 30 + 30 to 32 minutes: the spread of the three is under ±10% of the middle, so widened.
        assertThat(toFull.shortestMin).isWithin(1e-6).of(61.0 * 0.9)
        assertThat(toFull.longestMin).isWithin(1e-6).of(61.0 * 1.1)
    }

    @Test
    fun aSlowerChargeStretchesThePastOnesByItsPace() {
        val history = List(3) { PastCharge(true, line(20, minutesPerPoint = 1.0, tail = 30.0)) }
        // Twice as slow over its last five points.
        val current = line(40, 50, minutesPerPoint = 2.0)

        val toEighty = checkNotNull(ChargeTimes.toLevel(current, history, 80))
        assertThat(toEighty.shortestMin).isWithin(1e-6).of(54.0)
        assertThat(toEighty.longestMin).isWithin(1e-6).of(66.0)
    }

    @Test
    fun theScaleIsHeldWithinItsBounds() {
        val history = List(3) { PastCharge(true, line(20, minutesPerPoint = 1.0, tail = 30.0)) }
        val current = line(40, 50, minutesPerPoint = 10.0)

        val toEighty = checkNotNull(ChargeTimes.toLevel(current, history, 80))
        assertThat(toEighty.longestMin).isWithin(1e-6).of(60.0 * 1.1)
    }

    @Test
    fun timeAlreadySpentAtTheLevelComesOff() {
        val history = List(3) { PastCharge(true, line(20, minutesPerPoint = 1.0, tail = 30.0)) }
        val reached = line(40, 50, minutesPerPoint = 1.0)
        val current = reached + ChargeLevel(reached.last().elapsedNs + 30_000_000_000L, 50)

        val toEighty = checkNotNull(ChargeTimes.toLevel(current, history, 80))
        assertThat(toEighty.shortestMin).isWithin(1e-6).of(26.5)
        assertThat(toEighty.longestMin).isWithin(1e-6).of(32.5)
    }

    @Test
    fun tooFewOnThisChargerFallsBackToAnyChargerAndTooFewInAllGivesNothing() {
        val current = line(40, 50, minutesPerPoint = 1.0)
        val mixed =
            listOf(
                PastCharge(true, line(20, minutesPerPoint = 1.0, tail = 30.0)),
                PastCharge(false, line(20, minutesPerPoint = 1.0, tail = 30.0)),
                PastCharge(false, line(20, minutesPerPoint = 1.0, tail = 30.0)),
            )
        assertThat(checkNotNull(ChargeTimes.toLevel(current, mixed, 80)).basis).isEqualTo(ChargeTimeBasis.ANY_CHARGER)
        assertThat(ChargeTimes.toLevel(current, mixed.take(2), 80)).isNull()
    }

    @Test
    fun aPastChargeThatStartedHigherOrStoppedShortDoesNotCount() {
        val current = line(40, 50, minutesPerPoint = 1.0)
        val history =
            listOf(
                PastCharge(true, line(20, minutesPerPoint = 1.0, tail = 30.0)),
                PastCharge(true, line(20, minutesPerPoint = 1.0, tail = 30.0)),
                // Started at 47%, after the pace window's start.
                PastCharge(true, line(47, minutesPerPoint = 1.0, tail = 30.0)),
                // Unplugged at 78%.
                PastCharge(true, line(20, 78, minutesPerPoint = 1.0)),
            )
        assertThat(ChargeTimes.toLevel(current, history, 80)).isNull()
        assertThat(ChargeTimes.toLevel(current, history, 90)).isNull()
    }

    @Test
    fun fiveChargesOrMoreUseTheMiddleHalf() {
        val tails = listOf(10.0, 30.0, 40.0, 50.0, 200.0)
        val history = tails.map { PastCharge(true, line(20, 79, minutesPerPoint = 1.0) + tailPoints(79, it)) }
        val current = line(40, 79, minutesPerPoint = 1.0)

        val toFull = checkNotNull(ChargeTimes.toLevel(current, history, 100))
        assertThat(toFull.charges).isEqualTo(5)
        assertThat(toFull.shortestMin).isWithin(1e-6).of(30.0)
        assertThat(toFull.longestMin).isWithin(1e-6).of(50.0)
    }

    @Test
    fun atOrAboveTheTargetThereIsNothingToEstimate() {
        val history = List(3) { PastCharge(true, line(20, minutesPerPoint = 1.0, tail = 30.0)) }
        assertThat(ChargeTimes.toLevel(line(40, 80, minutesPerPoint = 1.0), history, 80)).isNull()
        assertThat(ChargeTimes.toLevel(emptyList(), history, 80)).isNull()
    }

    @Test
    fun aLevelBelowTheHighestSoFarHasNoTime() {
        val history = List(3) { PastCharge(true, line(20, minutesPerPoint = 1.0, tail = 30.0)) }
        val rose = line(40, 60, minutesPerPoint = 1.0)
        val fell = rose + ChargeLevel(rose.last().elapsedNs + 600_000_000_000L, 55)
        assertThat(ChargeTimes.toLevel(fell, history, 80)).isNull()
    }

    @Test
    fun aLongStayAtTheLevelTakesOffNoMoreThanItsStep() {
        val history = List(3) { PastCharge(true, line(20, minutesPerPoint = 1.0, tail = 30.0)) }
        val reached = line(40, 50, minutesPerPoint = 1.0)
        // Twenty minutes at 50%, where a step takes one.
        val current = reached + ChargeLevel(reached.last().elapsedNs + 1_200_000_000_000L, 50)

        val toEighty = checkNotNull(ChargeTimes.toLevel(current, history, 80))
        assertThat(toEighty.shortestMin).isWithin(1e-6).of(26.0)
        assertThat(toEighty.longestMin).isWithin(1e-6).of(32.0)
    }

    @Test
    fun aPastChargeThatPausedOnTheWayDoesNotCount() {
        val steady = List(3) { PastCharge(true, line(20, minutesPerPoint = 1.0, tail = 30.0)) }
        // Held at 80% for four hours, then on to full.
        val held =
            line(20, minutesPerPoint = 1.0).let { head ->
                head +
                    tailPoints(80, 30.0, startNs = head.last().elapsedNs + 240 * NS_PER_MIN.toLong())
            }
        val current = line(40, 50, minutesPerPoint = 1.0)

        val toFull = checkNotNull(ChargeTimes.toLevel(current, listOf(PastCharge(true, held)) + steady, 100))
        assertThat(toFull.charges).isEqualTo(3)
        assertThat(ChargeTimes.toLevel(current, listOf(PastCharge(true, held)) + steady.take(2), 100)).isNull()
    }

    /** Points from [from] to [to] at [minutesPerPoint], then, with a [tail], 80% to 100% over it. */
    private fun line(
        from: Int,
        to: Int = 80,
        minutesPerPoint: Double,
        tail: Double? = null,
    ): List<ChargeLevel> {
        val head = (from..to).mapIndexed { i, level -> ChargeLevel((i * minutesPerPoint * NS_PER_MIN).toLong(), level) }
        return if (tail == null) head else head + tailPoints(to, tail, startNs = head.last().elapsedNs)
    }

    /** From [from] to 100% evenly over [minutes], after [startNs] (the last point of a line from 20% at a minute a point by default). */
    private fun tailPoints(
        from: Int,
        minutes: Double,
        startNs: Long = ((from - 20) * NS_PER_MIN).toLong(),
    ): List<ChargeLevel> {
        val steps = 100 - from
        return (1..steps).map { i -> ChargeLevel(startNs + (i * minutes / steps * NS_PER_MIN).toLong(), from + i) }
    }

    private companion object {
        const val NS_PER_MIN = 60_000_000_000.0
    }
}

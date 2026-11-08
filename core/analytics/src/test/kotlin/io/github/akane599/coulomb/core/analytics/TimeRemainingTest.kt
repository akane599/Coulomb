// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.Provenance
import org.junit.Test

class TimeRemainingTest {
    @Test
    fun nothingToEstimateFromGivesNoRange() {
        assertThat(TimeRemaining.estimate(50, session = null, history = emptyList())).isNull()
        assertThat(TimeRemaining.estimate(0, ContextRate(5.0, 2.0, 1.0), emptyList())).isNull()
        // Charging, a zero-length session and a session with no quality are not rates to estimate from.
        val unusable =
            listOf(ContextRate(-3.0, 2.0, 1.0), ContextRate(5.0, 0.0, 1.0), ContextRate(5.0, 2.0, 0.0), ContextRate(Double.NaN, 2.0, 1.0))
        unusable.forEach { assertThat(TimeRemaining.estimate(50, it, unusable)).isNull() }
    }

    @Test
    fun historyAloneGivesItsQuartiles() {
        val history = listOf(2.0, 4.0, 6.0, 8.0).map { ContextRate(it, hours = 1.0, quality = 1.0) }

        val left = TimeRemaining.estimate(60, session = null, history = history)!!

        // Lower quartile 2 %/h, upper 6 %/h.
        assertThat(left.shortestHours).isWithin(1e-9).of(10.0)
        assertThat(left.longestHours).isWithin(1e-9).of(30.0)
        assertThat(left.provenance).isEqualTo(Provenance.ESTIMATED)
    }

    @Test
    fun longerAndBetterWindowsWeighMore() {
        val history =
            listOf(
                ContextRate(10.0, hours = 0.5, quality = 1.0),
                ContextRate(3.0, hours = 8.0, quality = 1.0),
                ContextRate(20.0, hours = 8.0, quality = 0.05),
            )

        val left = TimeRemaining.estimate(30, session = null, history = history)!!

        // The long good night dominates every quartile; the band is then the ±10% floor around 3 %/h.
        assertThat(left.shortestHours).isWithin(1e-9).of(30 / 3.3)
        assertThat(left.longestHours).isWithin(1e-9).of(30 / 2.7)
    }

    @Test
    fun theRangeNeverCollapsesToOneNumber() {
        val history = List(5) { ContextRate(5.0, hours = 2.0, quality = 1.0) }

        val left = TimeRemaining.estimate(50, session = null, history = history)!!

        assertThat(left.shortestHours).isWithin(1e-9).of(50 / 5.5)
        assertThat(left.longestHours).isWithin(1e-9).of(50 / 4.5)
    }

    @Test
    fun aSessionAloneNarrowsAsItGrows() {
        val young = TimeRemaining.estimate(80, ContextRate(8.0, hours = 0.1, quality = 1.0), emptyList())!!
        val hour = TimeRemaining.estimate(80, ContextRate(8.0, hours = 1.0, quality = 1.0), emptyList())!!
        val day = TimeRemaining.estimate(80, ContextRate(8.0, hours = 24.0, quality = 1.0), emptyList())!!

        // One hour of perfect data is half the trust: spread 0.5 − 0.4 × 0.5 = 0.3.
        assertThat(hour.shortestHours).isWithin(1e-9).of(80 / 10.4)
        assertThat(hour.longestHours).isWithin(1e-9).of(80 / 5.6)
        listOf(young, hour, day).forEach {
            assertThat(it.shortestHours).isLessThan(10.0)
            assertThat(it.longestHours).isGreaterThan(10.0)
        }
        assertThat(width(young)).isGreaterThan(width(hour))
        assertThat(width(hour)).isGreaterThan(width(day))
    }

    @Test
    fun aTrustedSessionPullsTheRangeTowardItsRate() {
        val history = listOf(2.0, 3.0, 4.0).map { ContextRate(it, hours = 4.0, quality = 1.0) }
        val historyOnly = TimeRemaining.estimate(50, null, history)!!
        val fresh = TimeRemaining.estimate(50, ContextRate(10.0, hours = 0.05, quality = 1.0), history)!!
        val settled = TimeRemaining.estimate(50, ContextRate(10.0, hours = 6.0, quality = 1.0), history)!!
        val doubtful = TimeRemaining.estimate(50, ContextRate(10.0, hours = 6.0, quality = 0.1), history)!!

        // A heavier session shortens the time left, and less so when its quality is low.
        assertThat(fresh.longestHours).isLessThan(historyOnly.longestHours)
        assertThat(settled.longestHours).isLessThan(fresh.longestHours)
        assertThat(doubtful.longestHours).isGreaterThan(settled.longestHours)
        // Six hours at full quality: trust 6/7.
        val trust = 6.0 / 7
        val spread = 0.5 - 0.4 * trust
        assertThat(settled.shortestHours).isWithin(1e-9).of(50 / (trust * 10 * (1 + spread) + (1 - trust) * 4.0))
        assertThat(settled.longestHours).isWithin(1e-9).of(50 / (trust * 10 * (1 - spread) + (1 - trust) * 2.0))
    }

    @Test
    fun trustGrowsWithHoursAndQuality() {
        assertThat(TimeRemaining.trust(ContextRate(5.0, hours = 1.0, quality = 1.0))).isWithin(1e-9).of(0.5)
        assertThat(TimeRemaining.trust(ContextRate(5.0, hours = 3.0, quality = 0.5))).isWithin(1e-9).of(0.375)
        assertThat(TimeRemaining.trust(ContextRate(5.0, hours = 3.0, quality = 2.0))).isWithin(1e-9).of(0.75)
    }

    @Test
    fun weightlessValuesFallBackToRanks() {
        val values = listOf(4.0 to 0.0, 1.0 to 0.0, 3.0 to 0.0, 2.0 to 0.0, 5.0 to 0.0)

        assertThat(TimeRemaining.weightedQuantile(values, 0.25)).isEqualTo(2.0)
        assertThat(TimeRemaining.weightedQuantile(values, 0.5)).isEqualTo(3.0)
        assertThat(TimeRemaining.weightedQuantile(values, 0.75)).isEqualTo(4.0)
    }

    private fun width(left: TimeLeft): Double = left.longestHours - left.shortestHours
}

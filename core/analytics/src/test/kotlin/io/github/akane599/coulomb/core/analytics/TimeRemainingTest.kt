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

        // Each quartile falls between two values: lower 3 %/h, upper 7 %/h.
        assertThat(left.shortestHours).isWithin(1e-9).of(60 / 7.0)
        assertThat(left.longestHours).isWithin(1e-9).of(60 / 3.0)
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

        // The long good night dominates every quartile, so the band centres on 3 %/h. It is also
        // nearly the only window, so the band is about as wide as one day's.
        val spread = 0.5 / Math.sqrt(TimeRemaining.effectiveCount(listOf(0.5, 8.0, 0.4)))
        assertThat(left.shortestHours).isWithin(1e-9).of(30 / (3 * (1 + spread)))
        assertThat(left.longestHours).isWithin(1e-9).of(30 / (3 * (1 - spread)))
    }

    @Test
    fun fewPastDaysGiveAWideRangeAndManyANarrowOne() {
        val one = TimeRemaining.estimate(60, null, listOf(ContextRate(4.0, hours = 8.0, quality = 1.0)))!!
        val fourteen = TimeRemaining.estimate(60, null, List(14) { ContextRate(4.0, hours = 8.0, quality = 1.0) })!!

        // One day: the untrusted ±50%. Fourteen: ±50% / √14, about ±13%.
        assertThat(one.shortestHours).isWithin(1e-9).of(60 / 6.0)
        assertThat(one.longestHours).isWithin(1e-9).of(60 / 2.0)
        val spread = 0.5 / Math.sqrt(14.0)
        assertThat(fourteen.shortestHours).isWithin(1e-9).of(60 / (4 * (1 + spread)))
        assertThat(fourteen.longestHours).isWithin(1e-9).of(60 / (4 * (1 - spread)))
    }

    @Test
    fun theRangeNeverCollapsesToOneNumber() {
        // A long trusted session that agrees with a long history: every spread is at its floor.
        val history = List(400) { ContextRate(5.0, hours = 2.0, quality = 1.0) }
        val session = ContextRate(5.0, hours = 1_000.0, quality = 1.0)

        val left = TimeRemaining.estimate(50, session, history)!!

        // At least ±10% around 5 %/h, and not much more.
        assertThat(left.shortestHours).isAtMost(50 / 5.5)
        assertThat(left.shortestHours).isAtLeast(50 / 5.6)
        assertThat(left.longestHours).isAtLeast(50 / 4.5)
        assertThat(left.longestHours).isAtMost(50 / 4.4)
    }

    @Test
    fun aSessionThatDisagreesWithTheHistoryWidensTheRange() {
        // Ten past days at 3 %/h; one hour of perfect session data at 10 %/h earns half the trust.
        val history = List(10) { ContextRate(3.0, hours = 2.0, quality = 1.0) }
        val session = ContextRate(10.0, hours = 1.0, quality = 1.0)

        val left = TimeRemaining.estimate(60, session, history)!!

        // Session band 7–13 %/h (spread 0.3), history 3 ± 0.5 / √10. The linear blend, widened by
        // 0.5 × 0.5 × 7 on each side, reaches almost to both rates.
        val historySpread = 0.5 / Math.sqrt(10.0)
        val low = 0.5 * 7 + 0.5 * 3 * (1 - historySpread) - 1.75
        val high = 0.5 * 13 + 0.5 * 3 * (1 + historySpread) + 1.75
        assertThat(left.shortestHours).isWithin(1e-9).of(60 / high)
        assertThat(left.longestHours).isWithin(1e-9).of(60 / low)
        assertThat(60 / left.longestHours).isLessThan(3.5)
        assertThat(60 / left.shortestHours).isGreaterThan(9.5)
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

        // A heavier session shortens the time left, and less so when its quality is low. A fresh
        // one moves only the short end: the long end stays at the history's.
        assertThat(fresh.shortestHours).isLessThan(historyOnly.shortestHours)
        assertThat(fresh.longestHours).isAtMost(historyOnly.longestHours)
        assertThat(settled.longestHours).isLessThan(fresh.longestHours)
        assertThat(doubtful.longestHours).isGreaterThan(settled.longestHours)
        // Six hours at full quality: trust 6/7. The history's quartiles are 2 and 4 %/h, its median 3.
        val trust = 6.0 / 7
        val spread = 0.5 - 0.4 * trust
        val disagreement = trust * (1 - trust) * (10 - 3)
        assertThat(settled.shortestHours).isWithin(1e-9).of(50 / (trust * 10 * (1 + spread) + (1 - trust) * 4.0 + disagreement))
        assertThat(settled.longestHours).isWithin(1e-9).of(50 / (trust * 10 * (1 - spread) + (1 - trust) * 2.0 - disagreement))
    }

    @Test
    fun trustGrowsWithHoursAndQuality() {
        assertThat(TimeRemaining.trust(ContextRate(5.0, hours = 1.0, quality = 1.0))).isWithin(1e-9).of(0.5)
        assertThat(TimeRemaining.trust(ContextRate(5.0, hours = 3.0, quality = 0.5))).isWithin(1e-9).of(0.375)
        assertThat(TimeRemaining.trust(ContextRate(5.0, hours = 3.0, quality = 2.0))).isWithin(1e-9).of(0.75)
    }

    @Test
    fun anEvenSplitTakesTheMidpointAsTheMedianOfAnEvenCount() {
        val values = listOf(2.0 to 1.0, 4.0 to 1.0)

        assertThat(TimeRemaining.weightedQuantile(values, 0.5)).isEqualTo(3.0)
        assertThat(TimeRemaining.weightedQuantile(values, 0.25)).isEqualTo(2.0)
        assertThat(TimeRemaining.effectiveCount(listOf(1.0, 1.0, 1.0, 1.0))).isWithin(1e-9).of(4.0)
        assertThat(TimeRemaining.effectiveCount(listOf(0.0, 0.0))).isWithin(1e-9).of(2.0)
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

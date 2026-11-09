// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChargingTest {
    private val usb = 2
    private val ac = 1

    /** A sample a minute from [from]% to [to]%, at [ua] µA and 4,000 mV until [taperAt], then a third of it. */
    private fun charge(
        from: Int,
        to: Int,
        ua: Long = 3_000_000,
        taperAt: Int? = null,
        tempDeciC: Int = 300,
        plug: Int = ac,
    ): List<ChargeSample> =
        (from..to).mapIndexed { minute, level ->
            val current = if (taperAt != null && level >= taperAt) ua / 3 else ua
            ChargeSample(minute * 60_000_000_000L, level, current, 4_000, tempDeciC, plug)
        }

    @Test
    fun aChargeThatTapersHasItsFullSpeedBeforeTheTaper() {
        val figures = Charging.figures(charge(20, 100, taperAt = 80))
        assertThat(figures.plugType).isEqualTo(ac)
        assertThat(figures.ccToCvLevel).isEqualTo(80)
        // 3 A at 4 V before the taper; the tapered samples are left out.
        assertThat(figures.fullSpeedW).isWithin(1e-9).of(12.0)
        assertThat(figures.peakW).isWithin(1e-9).of(12.0)
    }

    @Test
    fun aChargeUnpluggedBeforeTheTaperHasNone() {
        val figures = Charging.figures(charge(20, 60))
        assertThat(figures.ccToCvLevel).isNull()
        assertThat(figures.fullSpeedW).isWithin(1e-9).of(12.0)
    }

    @Test
    fun aDipThatRecoversIsNotATaper() {
        val points =
            charge(20, 90).map { if (it.levelPct in 60..65) it.copy(currentUa = 900_000) else it }
        assertThat(Charging.figures(points).ccToCvLevel).isNull()
    }

    @Test
    fun aTopUpThatStartsHighHasAPeakButNoFullSpeedOrTaper() {
        // 85% to full, already slowing: a third of full speed throughout, with no reading below 50%.
        val figures = Charging.figures(charge(85, 100, ua = 1_000_000))
        assertThat(figures.peakW).isWithin(1e-9).of(4.0)
        assertThat(figures.fullSpeedW).isNull()
        assertThat(figures.ccToCvLevel).isNull()
        // Nor one still at full speed from 55%: without readings below 50% there is no telling.
        assertThat(Charging.figures(charge(55, 100, taperAt = 85)).fullSpeedW).isNull()
    }

    @Test
    fun aHoldAtOneLevelIsNotATaper() {
        // A protection limit: full speed to 80%, then the current falls and the level stays at 80.
        val toLimit = charge(20, 80)
        val held = (1..20).map { i -> toLimit.last().copy(elapsedNs = toLimit.last().elapsedNs + i * 60_000_000_000L, currentUa = 50_000) }
        val figures = Charging.figures(toLimit + held)
        assertThat(figures.ccToCvLevel).isNull()
        assertThat(figures.fullSpeedW).isWithin(1e-9).of(12.0)
    }

    @Test
    fun tooFewSamplesWithACurrentGiveNoPowerButStillCountTheHeat() {
        val points = charge(20, 40, tempDeciC = 390).mapIndexed { i, p -> if (i < 15) p.copy(currentUa = null) else p }
        val figures = Charging.figures(points)
        assertThat(figures.peakW).isNull()
        assertThat(figures.fullSpeedW).isNull()
        assertThat(figures.ccToCvLevel).isNull()
        assertThat(figures.minutesAbove38C).isEqualTo(20)
    }

    @Test
    fun theMostCommonPlugIsTheSessions() {
        val points = charge(20, 50, plug = usb).mapIndexed { i, p -> if (i < 3) p.copy(plugType = ac) else p }
        assertThat(Charging.figures(points).plugType).isEqualTo(usb)
    }

    @Test
    fun aSessionMatchesTheNearestBaselineOfItsPlugWithinTolerance() {
        val profiles = listOf(ChargerBaseline(1, ac, 20.0), ChargerBaseline(2, ac, 12.0), ChargerBaseline(3, usb, 12.5))
        assertThat(Charging.match(ac, 13.0, profiles)).isEqualTo(2)
        assertThat(Charging.match(ac, 17.0, profiles)).isEqualTo(1)
        // 30% from the nearest AC baseline: a new charger, even though a USB one is near.
        assertThat(Charging.match(ac, 8.4, profiles)).isNull()
        assertThat(Charging.match(2, 12.0, emptyList())).isNull()
    }

    @Test
    fun theBaselineIsTheMedianOfTheLatestSessions() {
        assertThat(Charging.baseline(listOf(10.0, 30.0, 12.0))).isEqualTo(12.0)
        val many = List(10) { 10.0 } + List(5) { 50.0 }
        assertThat(Charging.baseline(many)).isEqualTo(10.0)
    }
}

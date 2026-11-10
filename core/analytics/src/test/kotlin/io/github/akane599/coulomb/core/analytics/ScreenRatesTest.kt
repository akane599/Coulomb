// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class ScreenRatesTest {
    private val zone = ZoneOffset.UTC

    private fun at(
        day: Int,
        hour: Double,
    ): Long =
        LocalDateTime
            .of(2026, 11, day, 0, 0)
            .toInstant(zone)
            .toEpochMilli() + (hour * 3_600_000).toLong()

    private fun span(
        day: Int,
        from: Double,
        to: Double,
        levels: Pair<Int, Int>,
        screenOn: Boolean = true,
        quality: Double = 1.0,
    ) = LevelSpan(screenOn, at(day, from), at(day, to), levels.first, levels.second, quality)

    @Test
    fun spansCombineIntoOneRateWithItsResolution() {
        val rate = ScreenRates.combine(listOf(span(8, 9.0, 10.0, 80 to 74), span(8, 12.0, 13.0, 70 to 64, quality = 0.5)))!!

        assertThat(rate.pctPerHour).isWithin(1e-9).of(6.0)
        assertThat(rate.hours).isWithin(1e-9).of(2.0)
        // Mean quality 0.75, times 1 − 1/12 for a 12% drop.
        assertThat(rate.quality).isWithin(1e-9).of(0.75 * (11.0 / 12))
    }

    @Test
    fun dropsAreTheFirstAndLastFallAfterTheStartLevel() {
        val changes = listOf(LevelChange(10, 80), LevelChange(20, 79), LevelChange(30, 80), LevelChange(40, 78), LevelChange(50, 78))

        // 80 at 10 is no drop from a start at 80; the rise to 80 at 30 is no drop either.
        assertThat(ScreenRates.drops(80, changes)).isEqualTo(LevelDrops(20, 79, 40, 78))
        // From a start at 81, the first change is already a drop.
        assertThat(ScreenRates.drops(81, changes)).isEqualTo(LevelDrops(10, 80, 40, 78))
    }

    @Test
    fun oneDropOrNoneTimesNothing() {
        assertThat(ScreenRates.drops(80, listOf(LevelChange(10, 79)))).isNull()
        assertThat(ScreenRates.drops(80, listOf(LevelChange(10, 80), LevelChange(20, 81)))).isNull()
        assertThat(ScreenRates.drops(80, emptyList())).isNull()
    }

    @Test
    fun aTimedSpanCountsFromItsFirstDropToItsLast() {
        // 100% held for an hour, then 6 points in 3 h: 2 %/h, not 7 points in 4 h.
        val timed = span(8, 0.0, 4.0, 100 to 93, screenOn = false).copy(drops = LevelDrops(at(8, 1.0), 99, at(8, 4.0), 93))
        val rate = ScreenRates.combine(listOf(timed, span(8, 5.0, 6.0, 90 to 88, screenOn = false)))!!

        // 6 + 2 points over 3 + 1 hours counted; watched 5 hours; resolution over the 8 points counted.
        assertThat(rate.pctPerHour).isWithin(1e-9).of(2.0)
        assertThat(rate.hours).isWithin(1e-9).of(5.0)
        assertThat(rate.quality).isWithin(1e-9).of(7.0 / 8)
    }

    @Test
    fun aPartOnBatteryKeepsTheDropsOnlyWhenBothFallInsideIt() {
        val drops = LevelDrops(at(8, 2.0), 79, at(8, 5.0), 76)
        val span = span(8, 1.0, 6.0, 80 to 75, screenOn = false).copy(drops = drops)

        val inside = ScreenRates.discharging(span, listOf(Discharge(at(8, 1.5), at(8, 7.0), 80, 70, 1.0)))
        val cut = ScreenRates.discharging(span, listOf(Discharge(at(8, 3.0), at(8, 7.0), 78, 70, 1.0)))

        assertThat(inside.single().drops).isEqualTo(drops)
        assertThat(cut.single().drops).isNull()
    }

    @Test
    fun aOnePercentDropEarnsNoTrustAndNoTimeIsNoRate() {
        assertThat(ScreenRates.combine(listOf(span(8, 1.0, 4.0, 60 to 59, screenOn = false)))!!.quality).isEqualTo(0.0)
        assertThat(ScreenRates.combine(listOf(span(8, 1.0, 1.0, 60 to 60)))).isNull()
        assertThat(ScreenRates.combine(emptyList())).isNull()
    }

    @Test
    fun historyIsPerPastDayAtTheSameHourAndScreenState() {
        val spans =
            listOf(
                // Two spans on the 5th near 14:00 combine into one day.
                span(5, 13.0, 14.0, 90 to 80),
                span(5, 15.5, 16.0, 70 to 66),
                // Too far from 14:00, screen off, today and too old.
                span(6, 18.0, 19.0, 90 to 80),
                span(6, 14.0, 15.0, 90 to 89, screenOn = false),
                span(8, 12.0, 13.0, 90 to 80),
                span(8, 14.0, 15.0, 90 to 80).shiftedBack(FIFTEEN_DAYS),
                // Across midnight: 23:00 is within two hours of 01:00, checked below.
                span(7, 23.0, 23.5, 50 to 46),
            )

        val atTwo = ScreenRates.history(spans, screenOn = true, nowWallMs = at(8, 14.0), zone = zone)
        assertThat(atTwo).hasSize(1)
        assertThat(atTwo.single().pctPerHour).isWithin(1e-9).of(14.0 / 1.5)

        val atOne = ScreenRates.history(spans, screenOn = true, nowWallMs = at(8, 1.0), zone = zone)
        assertThat(atOne.map { it.pctPerHour }).containsExactly(8.0)
    }

    @Test
    fun onlyTheDischargingPartOfASpanCounts() {
        // Screen on from 9:00 to 13:00; plugged in from 10:00 to 11:00.
        val span = span(8, 9.0, 13.0, 50 to 70, quality = 0.9)
        val discharges =
            listOf(
                Discharge(at(8, 6.0), at(8, 10.0), 60, 45, 1.0),
                Discharge(at(8, 11.0), at(8, 14.0), 80, 60, 0.6),
            )

        val parts = ScreenRates.discharging(span, discharges)

        assertThat(parts)
            .containsExactly(
                span.copy(endWallMs = at(8, 10.0), endLevel = 45),
                span.copy(startWallMs = at(8, 11.0), startLevel = 80, quality = 0.6),
            ).inOrder()
        assertThat(ScreenRates.discharging(span, listOf(Discharge(at(8, 14.0), at(8, 15.0), 70, 60, 1.0)))).isEmpty()
    }

    private fun LevelSpan.shiftedBack(ms: Long) = copy(startWallMs = startWallMs - ms, endWallMs = endWallMs - ms)

    private companion object {
        const val FIFTEEN_DAYS = 15L * 86_400_000
    }
}

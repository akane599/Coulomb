// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Protocol B (SPEC 16.6, ADR-0049) on the phone's data: the two screen-off sessions of 2 h or more
 * in the 4 days it collected up to 2026-11-10. The level changes are the ones its stored history
 * recorded inside each session, as offsets from the session's start, so no clock time is kept.
 * The reference, computed from the same history by `tools/validation/report.py` through a different
 * path, is 1.842 and 1.852 %/h, together 1.848; from their ends the sessions read 1.969 and 1.413.
 */
class ScreenRatesPhoneSessionsTest {
    private fun session(
        lengthMs: Long,
        levels: Pair<Int, Int>,
        changes: List<Pair<Long, Int>>,
        start: Long,
    ): LevelSpan {
        val drops = ScreenRates.drops(levels.first, changes.map { (offset, level) -> LevelChange(start + offset, level) })
        return LevelSpan(screenOn = false, start, start + lengthMs, levels.first, levels.second, quality = 1.0, drops = drops)
    }

    /** 67% → 61% over 3.05 h. */
    private val steady =
        session(
            10_972_580,
            67 to 61,
            listOf(38_375L to 66, 2_390_780L to 65, 4_139_873L to 64, 5_406_579L to 63, 7_397_118L to 62, 9_809_521L to 61),
            start = 1_000_000_000_000,
        )

    /** 100% → 93% over 4.95 h, after a full charge: the gauge held 100% for the first 1.15 h. */
    private val afterFull =
        session(
            17_832_406,
            100 to 93,
            listOf(
                4_135_400L to 99,
                6_182_615L to 98,
                8_298_633L to 97,
                10_274_198L to 96,
                12_074_679L to 95,
                14_001_049L to 94,
                15_798_588L to 93,
            ),
            start = 1_000_200_000_000,
        )

    @Test
    fun eachSessionMatchesTheReference() {
        assertThat(ScreenRates.combine(listOf(steady))!!.pctPerHour).isWithin(0.001).of(1.842)
        assertThat(ScreenRates.combine(listOf(afterFull))!!.pctPerHour).isWithin(0.001).of(1.852)
    }

    @Test
    fun togetherTheyPassProtocolB() {
        val rate = ScreenRates.combine(listOf(steady, afterFull))!!

        assertThat(rate.pctPerHour).isWithin(0.001).of(1.848)
        // B: within ±0.1 %/h of the reference, over at least 4 points.
        val points = listOf(steady, afterFull).sumOf { span -> span.drops!!.let { it.firstLevel - it.lastLevel } }
        assertThat(points).isEqualTo(11)
    }

    @Test
    fun fromTheirEndsTheyWouldFail() {
        val ends = listOf(steady, afterFull).map { it.copy(drops = null) }

        assertThat(ScreenRates.combine(ends)!!.pctPerHour).isWithin(0.001).of(1.625)
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RoundingTest {
    @Test
    fun currentRoundsToTenMilliamps() {
        assertThat(Rounding.currentMa(414.0)).isEqualTo(410.0)
        assertThat(Rounding.currentMa(-416.0)).isEqualTo(-420.0)
        assertThat(Rounding.currentMa(1_236.0)).isEqualTo(1_240.0)
    }

    @Test
    fun rateRoundsToATenthOfAPercentAnHour() {
        assertThat(Rounding.rate(12.64)).isEqualTo(12.6)
        assertThat(Rounding.rate(0.75)).isEqualTo(0.8)
    }

    @Test
    fun timeLeftRoundsOutwardToTenMinutesFromAnHour() {
        // 5 h 54 min and 7 h 13 min.
        assertThat(Rounding.timeLeftMinutes(354 / 60.0, 433 / 60.0)).isEqualTo(350L to 440L)
    }

    @Test
    fun underAnHourTimeLeftKeepsTheMinute() {
        assertThat(Rounding.timeLeftMinutes(12.4 / 60, 38.2 / 60)).isEqualTo(12L to 39L)
    }

    @Test
    fun theEndsNeverMeet() {
        assertThat(Rounding.timeLeftMinutes(2.0, 2.0)).isEqualTo(120L to 130L)
        assertThat(Rounding.timeLeftMinutes(0.0, 0.0)).isEqualTo(0L to 1L)
    }

    @Test
    fun aLongestBelowTheShortestIsRaisedToIt() {
        assertThat(Rounding.timeLeftMinutes(3.0, 1.0)).isEqualTo(180L to 190L)
    }

    @Test
    fun splitFindsTheDigitsWhereverTheLocalePutsTheSign() {
        assertThat(Formats.split("74%")).isEqualTo(Affixed("", "74", "%"))
        assertThat(Formats.split("%74")).isEqualTo(Affixed("%", "74", ""))
        assertThat(Formats.split("74 %")).isEqualTo(Affixed("", "74", " %"))
    }
}

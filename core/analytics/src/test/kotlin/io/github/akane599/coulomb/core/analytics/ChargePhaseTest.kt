// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChargePhaseTest {
    @Test
    fun aFullCurrentIsFullSpeed() {
        val samples = run(from = 20, to = 60, ua = 4_000_000)
        assertThat(ChargePhases.of(samples, usualTaperLevel = null)).isEqualTo(ChargePhase.FULL_SPEED)
    }

    @Test
    fun aCurrentBelowSeventyPercentOfFullSpeedAboveHalfIsSlowing() {
        val samples = run(from = 20, to = 80, ua = 4_000_000) + run(from = 81, to = 86, ua = 2_000_000, startMin = 61)
        assertThat(ChargePhases.of(samples, usualTaperLevel = null)).isEqualTo(ChargePhase.SLOWING)
    }

    @Test
    fun aBriefDipIsNotSlowing() {
        val samples = run(from = 20, to = 80, ua = 4_000_000) + run(from = 81, to = 82, ua = 2_000_000, startMin = 61)
        assertThat(ChargePhases.of(samples, usualTaperLevel = null)).isEqualTo(ChargePhase.FULL_SPEED)
    }

    @Test
    fun aChargeStartedHighUsesTheChargersUsualTaper() {
        val samples = run(from = 82, to = 86, ua = 1_500_000)
        assertThat(ChargePhases.of(samples, usualTaperLevel = 80)).isEqualTo(ChargePhase.SLOWING)
        assertThat(ChargePhases.of(samples, usualTaperLevel = 90)).isEqualTo(ChargePhase.FULL_SPEED)
        // Nothing to tell full speed from slowing: no phase rather than a guess.
        assertThat(ChargePhases.of(samples, usualTaperLevel = null)).isNull()
    }

    @Test
    fun aCurrentFlowingOutWhilePluggedIsDrawing() {
        val samples = run(from = 20, to = 60, ua = 4_000_000) + (1..5).map { sample(60 + it, 60 - it, -300_000) }
        assertThat(ChargePhases.of(samples, usualTaperLevel = null)).isEqualTo(ChargePhase.DRAWING)
    }

    @Test
    fun aLevelStillForTenMinutesWithAlmostNoCurrentIsHolding() {
        val climb = run(from = 20, to = 80, ua = 4_000_000)
        val held = (1..11).map { sample(61 + it, 80, 50_000) }
        assertThat(ChargePhases.of(climb + held, usualTaperLevel = null)).isEqualTo(ChargePhase.HOLDING)
        assertThat(ChargePhases.of(climb + held.take(5), usualTaperLevel = null)).isEqualTo(ChargePhase.SLOWING)
    }

    @Test
    fun aLevelHeldWhileARealCurrentLeavesIsDrawingNotHolding() {
        val climb = run(from = 20, to = 80, ua = 4_000_000)
        // Past 10% of the 4 A full speed: a real current out.
        val leaving = (1..11).map { sample(61 + it, 80, -600_000) }
        assertThat(ChargePhases.of(climb + leaving, usualTaperLevel = null)).isEqualTo(ChargePhase.DRAWING)
        // A trickle either way at a charge limit is still holding.
        val trickle = (1..11).map { sample(61 + it, 80, -50_000) }
        assertThat(ChargePhases.of(climb + trickle, usualTaperLevel = null)).isEqualTo(ChargePhase.HOLDING)
    }

    @Test
    fun aHundredIsFullAndNoCurrentIsNoPhase() {
        assertThat(ChargePhases.of(run(from = 95, to = 100, ua = 300_000), usualTaperLevel = null)).isEqualTo(ChargePhase.FULL)
        assertThat(ChargePhases.of(listOf(sample(0, 50, null)), usualTaperLevel = null)).isNull()
        assertThat(ChargePhases.of(emptyList(), usualTaperLevel = null)).isNull()
    }

    /** A sample a minute from [from] to [to] at [ua]. */
    private fun run(
        from: Int,
        to: Int,
        ua: Long,
        startMin: Int = 0,
    ): List<ChargeSample> = (from..to).mapIndexed { i, level -> sample(startMin + i, level, ua) }

    private fun sample(
        minute: Int,
        level: Int,
        ua: Long?,
    ) = ChargeSample(minute * 60_000_000_000L, level, ua, 4_000, 300, 1)
}

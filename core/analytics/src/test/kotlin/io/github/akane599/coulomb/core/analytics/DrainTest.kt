// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import org.junit.Test

class DrainTest {
    private fun point(
        minutes: Long,
        level: Int,
        chargeUah: Long?,
        bootSeq: Int = 1,
    ) = ChargePoint(bootSeq, minutes * 60 * NS_PER_S, level, chargeUah)

    @Test
    fun aScreenOffNightIsTheCoulombDeltaOverTheHours() {
        val rate =
            Drain.rate(
                point(0, 80, 4_000_000),
                point(480, 76, 3_800_000),
                counterReliable = true,
                calibrated = true,
                capacityMah = 5_000.0,
            )
        check(rate is DrainRate.Rate)
        assertThat(rate.avgCurrentMa).isWithin(1e-9).of(-25.0)
        assertThat(rate.pctPerHourOfCapacity).isWithin(1e-9).of(0.5)
        assertThat(rate.pctPerHourOfLevel).isWithin(1e-9).of(0.5)
        assertThat(rate.shown).isEqualTo(DrainRate.Shown.CAPACITY)
        assertThat(rate.currentProvenance).isEqualTo(Provenance.MEASURED)
        // %/h of capacity divides by an estimate, so the shown rate is Estimated (SPEC 6.7).
        assertThat(rate.rateProvenance).isEqualTo(Provenance.ESTIMATED)
        assertThat(rate.chargeSource).isEqualTo(ChargeSource.CHARGE_COUNTER)
        // 200 mAh from a 1 mAh counter.
        assertThat(rate.quality).isWithin(1e-9).of(0.995)
    }

    @Test
    fun unsupportedCounterValuesAndNoTimeGiveNoCounterRate() {
        val rate = Drain.rate(point(0, 80, Long.MIN_VALUE), point(60, 78, 3_900_000), true, true, 5_000.0)
        check(rate is DrainRate.Rate)
        assertThat(rate.chargeSource).isEqualTo(ChargeSource.LEVEL_DELTA)
        assertThat(Drain.rate(point(10, 80, 4_000_000), point(10, 79, 3_000_000), true, true, 5_000.0)).isEqualTo(DrainRate.Collecting)
    }

    @Test
    fun shortAndSmallWindowsAreStillCollecting() {
        assertThat(Drain.rate(point(0, 80, 4_000_000), point(10, 80, 3_990_000), true, true, 5_000.0)).isEqualTo(DrainRate.Collecting)
        // 20 mAh in ten minutes is enough; so is a quarter of an hour.
        assertThat(
            Drain.rate(point(0, 80, 4_000_000), point(10, 79, 3_980_000), true, true, 5_000.0),
        ).isInstanceOf(DrainRate.Rate::class.java)
        assertThat(
            Drain.rate(point(0, 80, 4_000_000), point(15, 80, 3_999_000), true, true, 5_000.0),
        ).isInstanceOf(DrainRate.Rate::class.java)
    }

    @Test
    fun aRebootBetweenTheEndsGivesNoRate() {
        assertThat(Drain.rate(point(0, 80, 4_000_000), point(60, 70, 3_500_000, bootSeq = 2), true, true, 5_000.0))
            .isEqualTo(DrainRate.AcrossReboot)
    }

    @Test
    fun withoutATrustedCounterTheRateIsEstimated() {
        val integrated =
            Drain.rate(
                point(0, 80, null),
                point(60, 78, null),
                counterReliable = false,
                calibrated = true,
                capacityMah = 5_000.0,
                integratedUah = -100_000.0,
            )
        check(integrated is DrainRate.Rate)
        assertThat(integrated.chargeSource).isEqualTo(ChargeSource.INTEGRATED_CURRENT)
        assertThat(integrated.currentProvenance).isEqualTo(Provenance.ESTIMATED)
        assertThat(integrated.avgCurrentMa).isWithin(1e-9).of(-100.0)

        val levels =
            Drain.rate(
                point(0, 80, 4_000_000),
                point(60, 78, 3_900_000),
                counterReliable = false,
                calibrated = false,
                capacityMah = null,
            )
        check(levels is DrainRate.Rate)
        assertThat(levels.chargeSource).isEqualTo(ChargeSource.LEVEL_DELTA)
        assertThat(levels.avgCurrentMa).isNull()
        assertThat(levels.pctPerHourOfLevel).isWithin(1e-9).of(2.0)
        assertThat(levels.shown).isEqualTo(DrainRate.Shown.LEVEL)
        // Two level steps from the level alone: low confidence.
        assertThat(levels.quality).isWithin(1e-9).of(0.25)
    }

    @Test
    fun untilCalibrationCompletesTheLevelRateIsShown() {
        val rate =
            Drain.rate(
                point(0, 80, 4_000_000),
                point(60, 78, 3_900_000),
                counterReliable = true,
                calibrated = false,
                capacityMah = 5_000.0,
            )
        check(rate is DrainRate.Rate)
        assertThat(rate.shown).isEqualTo(DrainRate.Shown.LEVEL)
        assertThat(rate.currentProvenance).isEqualTo(Provenance.MEASURED)
    }
}

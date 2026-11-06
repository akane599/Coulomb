// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class CapacityTest {
    private fun window(
        socPct: Double = 50.0,
        capacityMah: Double = 4_800.0,
        charging: Boolean = true,
        minTemp: Int = 250,
        maxTemp: Int = 320,
        meanTemp: Int = 280,
    ) = CapacityWindow(
        charging = charging,
        deltaSocPct = if (charging) socPct else -socPct,
        deltaChargeUah = ((if (charging) 1 else -1) * capacityMah * socPct / 100 * 1_000).toLong(),
        minTempDeciC = minTemp,
        maxTempDeciC = maxTemp,
        meanTempDeciC = meanTemp,
        sameBoot = true,
        counterReliable = true,
    )

    @Test
    fun onlyQualifyingWindowsGiveAnEstimate() {
        assertThat(Capacity.rejection(window(socPct = 40.0))).isNull()
        assertThat(Capacity.rejection(window(socPct = 39.0))).isEqualTo(CapacityRejection.SOC_TOO_SMALL)
        assertThat(Capacity.rejection(window(socPct = 30.0, charging = false))).isNull()
        assertThat(Capacity.rejection(window(socPct = 29.0, charging = false))).isEqualTo(CapacityRejection.SOC_TOO_SMALL)
        assertThat(Capacity.rejection(window(minTemp = 149))).isEqualTo(CapacityRejection.TEMPERATURE)
        assertThat(Capacity.rejection(window(maxTemp = 401))).isEqualTo(CapacityRejection.TEMPERATURE)
        assertThat(Capacity.rejection(window().copy(sameBoot = false))).isEqualTo(CapacityRejection.REBOOT)
        assertThat(Capacity.rejection(window().copy(counterReliable = false))).isEqualTo(CapacityRejection.COUNTER)
        assertThat(Capacity.estimateMah(window(socPct = 50.0, capacityMah = 4_800.0))).isWithin(1e-6).of(4_800.0)
        assertThat(Capacity.estimateMah(window(socPct = 35.0, capacityMah = 4_800.0, charging = false))).isWithin(1e-6).of(4_800.0)
    }

    @Test
    fun temperatureAndRecencyWeights() {
        assertThat(Capacity.temperatureFactor(250)).isEqualTo(1.0)
        assertThat(Capacity.temperatureFactor(200)).isEqualTo(1.0)
        assertThat(Capacity.temperatureFactor(300)).isEqualTo(1.0)
        assertThat(Capacity.temperatureFactor(175)).isWithin(1e-9).of(0.75)
        assertThat(Capacity.temperatureFactor(350)).isWithin(1e-9).of(0.75)
        assertThat(Capacity.temperatureFactor(150)).isWithin(1e-9).of(0.5)
        assertThat(Capacity.temperatureFactor(400)).isWithin(1e-9).of(0.5)
        assertThat(Capacity.recency(60.0)).isWithin(1e-9).of(0.5)
        assertThat(Capacity.recency(0.0)).isEqualTo(1.0)
        assertThat(Capacity.staticWeight(window(socPct = 50.0, meanTemp = 175))).isWithin(1e-9).of(37.5)
    }

    @Test
    fun theWeightedMedianFollowsTheWeights() {
        assertThat(Capacity.weightedMedian(listOf(1.0, 2.0, 3.0), listOf(1.0, 1.0, 1.0))).isEqualTo(2.0)
        assertThat(Capacity.weightedMedian(listOf(1.0, 2.0, 3.0), listOf(1.0, 1.0, 5.0))).isEqualTo(3.0)
        assertThat(Capacity.weightedMedian(listOf(3.0, 1.0), listOf(1.0, 1.0))).isEqualTo(1.0)
    }

    @Test
    fun theEstimateFindsAKnownCapacity() {
        // Ten windows around a true 4,780 mAh, with 1% noise in the measured charge.
        val noise = Random(42)
        val readings =
            List(12) { i ->
                val w = window(socPct = 40.0 + i, capacityMah = 4_780.0 * (1 + (noise.nextDouble() - 0.5) * 0.02))
                CapacityReading(Capacity.estimateMah(w), Capacity.staticWeight(w), ageDays = i * 3.0)
            }
        val estimate = checkNotNull(Capacity.estimate(readings, designMah = 5_000.0))
        assertThat(estimate.windows).isEqualTo(10)
        assertThat(estimate.estimateMah).isWithin(50.0).of(4_780.0)
        assertThat(estimate.estimateMah % 10).isEqualTo(0.0)
        assertThat(estimate.ciLowMah).isAtMost(estimate.estimateMah)
        assertThat(estimate.ciHighMah).isAtLeast(estimate.estimateMah)
        assertThat(estimate.healthPct).isWithin(1e-9).of(100 * estimate.estimateMah / 5_000.0)
        // The resampling is seeded from the inputs: the same readings give the same interval.
        assertThat(Capacity.estimate(readings, designMah = 5_000.0)).isEqualTo(estimate)
    }

    @Test
    fun theIntervalIsNeverNarrowerThanOnePercentOfDesign() {
        val same = List(5) { CapacityReading(4_800.0, staticWeight = 50.0, ageDays = 0.0) }
        val estimate = checkNotNull(Capacity.estimate(same, designMah = 5_000.0))
        assertThat(estimate.estimateMah).isEqualTo(4_800.0)
        assertThat(estimate.ciLowMah).isEqualTo(4_750.0)
        assertThat(estimate.ciHighMah).isEqualTo(4_850.0)
        assertThat(Capacity.estimate(emptyList(), 5_000.0)).isNull()
        assertThat(Capacity.estimate(same, designMah = null)?.healthPct).isNull()
    }

    @Test
    fun olderWindowsCountLess() {
        val readings =
            listOf(
                CapacityReading(4_600.0, staticWeight = 50.0, ageDays = 0.0),
                CapacityReading(4_900.0, staticWeight = 50.0, ageDays = 300.0),
                CapacityReading(4_900.0, staticWeight = 50.0, ageDays = 300.0),
            )
        assertThat(checkNotNull(Capacity.estimate(readings, null)).estimateMah).isEqualTo(4_600.0)
    }
}

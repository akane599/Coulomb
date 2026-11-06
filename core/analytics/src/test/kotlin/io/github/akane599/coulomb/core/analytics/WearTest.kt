// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WearTest {
    private val thresholds = StressThresholds.bundled()

    @Test
    fun theBundledThresholdsLoad() {
        assertThat(thresholds.minutesAtOrAbove80).isEqualTo(Threshold(60.0, 240.0))
        assertThat(thresholds.peakTempDeciC).isEqualTo(Threshold(380.0, 430.0))
        assertThat(thresholds.cRate).isEqualTo(Threshold(0.7, 1.2))
    }

    @Test
    fun theWorstFactorSetsTheStress() {
        val calm =
            WearModel.wear(
                WearInput(-2_400_000, minutesAtOrAbove80 = 10, peakTempDeciC = 330, peakCurrentMa = 1_000.0),
                4_800.0,
                thresholds,
            )
        assertThat(calm.cycleEquivalents).isWithin(1e-9).of(0.5)
        assertThat(calm.stress).isEqualTo(StressLevel.LOW)
        assertThat(calm.cause).isNull()

        val hot = WearModel.wear(WearInput(2_400_000, 10, peakTempDeciC = 385, peakCurrentMa = 1_000.0), 4_800.0, thresholds)
        assertThat(hot.stress).isEqualTo(StressLevel.MODERATE)
        assertThat(hot.cause).isEqualTo(StressFactor.TEMPERATURE)

        // 6 A into 4,800 mAh is 1.25 C.
        val fast = WearModel.wear(WearInput(2_400_000, 300, peakTempDeciC = 385, peakCurrentMa = 6_000.0), 4_800.0, thresholds)
        assertThat(fast.stress).isEqualTo(StressLevel.HIGH)
        assertThat(fast.cause).isEqualTo(StressFactor.TIME_AT_HIGH_CHARGE)
    }
}

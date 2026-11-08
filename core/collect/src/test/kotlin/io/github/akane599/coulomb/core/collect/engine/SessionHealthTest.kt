// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.analytics.Capacity
import io.github.akane599.coulomb.core.analytics.CapacityRejection
import io.github.akane599.coulomb.core.analytics.StressThresholds
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.SessionKind
import org.junit.Test

class SessionHealthTest {
    private val thresholds = StressThresholds.bundled()

    @Test
    fun theWindowRunsFromTheFirstLevelChangeToTheLast() {
        // 30% for a while, then a step a minute up to 80%, then a plateau at 80%.
        val samples =
            listOf(sample(0, 30, 1_500_000), sample(1, 30, 1_520_000)) +
                (31..80).mapIndexed { i, level -> sample(2 + i, level, 1_550_000L + 50_000L * (i + 1)) } +
                listOf(sample(60, 80, 4_200_000))
        val window = checkNotNull(SessionHealth.capacityWindow(SessionKind.CHARGE, 30, samples, counterReliable = true))
        // From the step to 31% to the step to 80%: 49 points and 49 × 50 mAh.
        assertThat(window.deltaSocPct).isEqualTo(49.0)
        assertThat(window.deltaChargeUah).isEqualTo(49 * 50_000L)
        assertThat(Capacity.rejection(window)).isNull()
        assertThat(Capacity.estimateMah(window)).isWithin(1e-9).of(5_000.0)
    }

    @Test
    fun theFirstSampleIsAChangeWhenItLeftTheSessionsStartLevel() {
        val samples = (31..80).mapIndexed { i, level -> sample(1 + i, level, 1_550_000L + 50_000L * (i + 1)) }
        val window = checkNotNull(SessionHealth.capacityWindow(SessionKind.CHARGE, 30, samples, counterReliable = true))
        assertThat(window.deltaSocPct).isEqualTo(49.0)
    }

    @Test
    fun aSessionWithFewerThanTwoLevelChangesHasNoWindow() {
        val samples = listOf(sample(0, 50, 2_500_000), sample(1, 50, 2_490_000), sample(2, 49, 2_450_000))
        assertThat(SessionHealth.capacityWindow(SessionKind.DISCHARGE, 50, samples, counterReliable = true)).isNull()
    }

    @Test
    fun aLevelChangeWithoutAChargeReadingIsNotAnEnd() {
        val samples =
            listOf(sample(0, 80, 4_000_000), sample(1, 79, null)) +
                (78 downTo 40).mapIndexed { i, level -> sample(2 + i, level, 3_950_000L - 50_000L * (i + 1)) }
        val window = checkNotNull(SessionHealth.capacityWindow(SessionKind.DISCHARGE, 80, samples, counterReliable = true))
        assertThat(window.deltaSocPct).isEqualTo(-38.0)
        assertThat(window.charging).isFalse()
    }

    @Test
    fun aHotStretchInsideTheWindowRejectsIt() {
        val samples =
            (30..80).mapIndexed { i, level -> sample(i, level, 1_500_000L + 50_000L * i, if (level == 60) 412 else 300) }
        val window = checkNotNull(SessionHealth.capacityWindow(SessionKind.CHARGE, 30, samples, counterReliable = true))
        assertThat(Capacity.rejection(window)).isEqualTo(CapacityRejection.TEMPERATURE)
    }

    @Test
    fun screenSessionsHaveNoWindowAndNoWear() {
        val samples = (30..80).mapIndexed { i, level -> sample(i, level, 1_500_000L + 50_000L * i) }
        assertThat(SessionHealth.capacityWindow(SessionKind.SCREEN_ON, 30, samples, counterReliable = true)).isNull()
        val screen = session(SessionKind.SCREEN_ON)
        assertThat(SessionHealth.withWear(screen, 5_000.0, thresholds)).isEqualTo(screen)
    }

    @Test
    fun cycleEquivalentsUseTheCounterAgainstTheEstimate() {
        val worn = SessionHealth.withWear(session(SessionKind.CHARGE), capacityMah = 5_000.0, thresholds)
        // 2,000 mAh into a 5,000 mAh battery.
        assertThat(worn.cycleEquivalents).isWithin(1e-9).of(0.4)
        assertThat(worn.stressIndex).isEqualTo(0.0)
    }

    @Test
    fun withoutAnEstimateCycleEquivalentsComeFromTheLevelAndStressIsUnknown() {
        val worn = SessionHealth.withWear(session(SessionKind.CHARGE), capacityMah = null, thresholds)
        assertThat(worn.cycleEquivalents).isWithin(1e-9).of(0.5)
        assertThat(worn.stressIndex).isNull()
    }

    @Test
    fun aLevelOnlySessionCountsItsLevelChange() {
        val levelOnly = session(SessionKind.DISCHARGE).copy(chargeSource = ChargeSource.LEVEL_DELTA, startLevel = 90, endLevel = 60)
        assertThat(SessionHealth.withWear(levelOnly, 5_000.0, thresholds).cycleEquivalents).isWithin(1e-9).of(0.3)
    }

    @Test
    fun anUncalibratedPeakCurrentLeavesStressUnknown() {
        val worn = SessionHealth.withWear(session(SessionKind.CHARGE).copy(peakCurrentMa = null), 5_000.0, thresholds)
        assertThat(worn.stressIndex).isNull()
        assertThat(worn.cycleEquivalents).isWithin(1e-9).of(0.4)
    }

    @Test
    fun aFastHotChargeIsHighStress() {
        val hot = session(SessionKind.CHARGE).copy(maxTempDeciC = 470, peakCurrentMa = 9_000.0)
        assertThat(SessionHealth.withWear(hot, 5_000.0, thresholds).stressIndex).isEqualTo(2.0)
    }

    private fun sample(
        minute: Int,
        level: Int,
        chargeUah: Long?,
        tempDeciC: Int = 300,
    ) = BatterySampleEntity(
        bootSeq = 1,
        elapsedNs = minute * 60_000_000_000L,
        wallMs = minute * 60_000L,
        levelPct = level,
        chargeUah = chargeUah,
        currentUa = null,
        currentRaw = null,
        voltageMv = 4_000,
        tempDeciC = tempDeciC,
        plugType = 0,
        status = 0,
        screenOn = false,
        dozeState = 0,
        thermalStatus = 0,
    )

    /** A closed session from 30% to 80% with 2,000 mAh counted, 15 min above 80% and a 2 A peak. */
    private fun session(kind: SessionKind) =
        SessionEntity(
            id = 7,
            kind = kind,
            bootSeq = 1,
            startElapsedNs = 0,
            endElapsedNs = 3_600_000_000_000,
            startWall = 0,
            endWall = 3_600_000,
            startSnapshotId = null,
            endSnapshotId = null,
            startLevel = 30,
            endLevel = 80,
            startChargeUah = 1_500_000,
            endChargeUah = 3_500_000,
            chargeSource = ChargeSource.CHARGE_COUNTER,
            avgCurrentMa = 1_500.0,
            peakCurrentMa = 2_000.0,
            minTempDeciC = 280,
            meanTempDeciC = 300,
            maxTempDeciC = 320,
            awakeRatio = null,
            deepIdleReached = null,
            deepIdleReachedMs = null,
            minutesAtOrAbove80 = 15,
            minutesAt100 = 0,
            stressIndex = null,
            cycleEquivalents = null,
            closeReason = null,
            quality = 1.0,
        )
}

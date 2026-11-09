// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.analytics.CapacityEstimate
import io.github.akane599.coulomb.core.analytics.StressLevel
import io.github.akane599.coulomb.core.database.dao.LevelTime
import io.github.akane599.coulomb.core.database.entity.CapacityEstimateEntity
import io.github.akane599.coulomb.core.database.entity.HealthSummaryEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.DesignCapacity
import io.github.akane599.coulomb.core.model.DesignCapacitySource
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import org.junit.Test
import java.time.LocalDate

class HealthReaderTest {
    private val day = 86_400_000L
    private val hourNs = 3_600_000_000_000L

    private fun summary(
        epochDay: Int,
        mah: Double? = 4_600.0,
        soh: Int? = null,
        oem: String? = null,
        cycles: Int? = null,
        sum: Double = 0.0,
    ) = HealthSummaryEntity(epochDay, mah, mah?.minus(70), mah?.plus(70), null, soh, oem, cycles, sum)

    private fun charge(
        start: Int,
        hours: Long = 2,
        meanTemp: Int? = 300,
        stress: Double? = 0.0,
    ) = SessionEntity(
        kind = SessionKind.CHARGE,
        bootSeq = 1,
        startElapsedNs = 0,
        endElapsedNs = hours * hourNs,
        startWall = 0,
        endWall = hours * 3_600_000,
        startSnapshotId = null,
        endSnapshotId = null,
        startLevel = start,
        endLevel = 100,
        startChargeUah = null,
        endChargeUah = null,
        chargeSource = null,
        avgCurrentMa = null,
        peakCurrentMa = null,
        minTempDeciC = null,
        meanTempDeciC = meanTemp,
        maxTempDeciC = 350,
        awakeRatio = null,
        deepIdleReached = null,
        deepIdleReachedMs = null,
        minutesAtOrAbove80 = null,
        minutesAt100 = null,
        stressIndex = stress,
        cycleEquivalents = null,
        closeReason = SessionCloseReason.STATE_CHANGED,
        quality = 1.0,
    )

    @Test
    fun theEstimateIsMadeAsOfTheReadWithTodaysDesignCapacity() {
        val estimates =
            listOf(4_700.0, 4_600.0, 4_620.0).mapIndexed { i, mah ->
                CapacityEstimateEntity(
                    i + 1L,
                    i + 1L,
                    mah,
                    50.0,
                    50.0,
                    2_300.0,
                    250,
                    i * day,
                )
            }
        val design = DesignCapacity(5_000.0, DesignCapacitySource.USER)
        val estimate = checkNotNull(HealthReader.capacity(estimates, design, nowWallMs = 3 * day))
        assertThat(estimate.estimateMah).isEqualTo(4_620.0)
        assertThat(estimate.healthPct).isWithin(1e-9).of(92.4)
        assertThat(estimate.windows).isEqualTo(3)
        assertThat(HealthReader.capacity(emptyList(), design, 0)).isNull()
    }

    @Test
    fun theChangeIsAgainstTheFirstDayOnceThatDayHasPassed() {
        val now = CapacityEstimate(4_500.0, 4_430.0, 4_570.0, null, 10, 1.0)
        val summaries = listOf(summary(100, mah = null), summary(101, mah = 4_610.0), summary(140, mah = 4_500.0))
        val change = checkNotNull(HealthReader.change(summaries, now, LocalDate.ofEpochDay(140)))
        assertThat(change.firstDay).isEqualTo(LocalDate.ofEpochDay(101))
        assertThat(change.deltaMah).isWithin(1e-9).of(-110.0)
        assertThat(change.firstHalfWidthMah).isWithin(1e-9).of(70.0)
        // On the first estimate's own day there is nothing to compare.
        assertThat(HealthReader.change(summaries.take(2), now, LocalDate.ofEpochDay(101))).isNull()
    }

    @Test
    fun eachCrossCheckValueComesFromTheLatestDayThatHasIt() {
        val summaries =
            listOf(
                summary(1, soh = 95, oem = "mSavedBatteryAsoc=99"),
                summary(2, soh = 94),
                summary(3),
            )
        val check = HealthReader.crossCheck(summaries)
        assertThat(check.systemSohPct).isEqualTo(94)
        assertThat(check.oemHealth.single().percent).isEqualTo(99)
    }

    @Test
    fun cyclesCoverTheSameDaysOnceTwoDaysHaveACount() {
        val first = LocalDate.ofEpochDay(9)
        val one = HealthReader.cycles(listOf(summary(10, cycles = 300, sum = 0.6)), first)
        assertThat(one).isEqualTo(Cycles(300, 0.6, first, null))

        val two = HealthReader.cycles(listOf(summary(10, cycles = 300, sum = 0.6), summary(40, cycles = 330, sum = 29.1)), first)
        assertThat(two.platformCount).isEqualTo(330)
        assertThat(two.coulombCount).isWithin(1e-9).of(28.5)
        assertThat(two.since).isEqualTo(LocalDate.ofEpochDay(10))
        assertThat(two.platformCounted).isEqualTo(30)

        assertThat(HealthReader.cycles(emptyList(), null)).isEqualTo(Cycles(null, 0.0, null, null))
    }

    @Test
    fun habitsAverageTheChargesAndScaleTheHighTimeToADay() {
        val charges =
            listOf(
                charge(start = 20, hours = 3, meanTemp = 300, stress = 2.0),
                charge(start = 60, hours = 1, meanTemp = 340, stress = 0.0),
                charge(start = 50, hours = 1, meanTemp = null, stress = null),
            )
        // Six hours at 80% or more over two watched days.
        val habits = checkNotNull(HealthReader.habits(charges, LevelTime(6 * 3_600_000, 2 * day)))
        assertThat(habits.sessions).isEqualTo(3)
        assertThat(habits.depthPct).isWithin(1e-9).of((80 + 40 + 50) / 3.0)
        assertThat(habits.highMinutesPerDay).isWithin(1e-9).of(180.0)
        // Weighted by duration: (300 × 3 + 340 × 1) / 4.
        assertThat(habits.chargeTempDeciC).isWithin(1e-9).of(310.0)
        assertThat(habits.stress).containsExactly(StressLevel.HIGH, 1, StressLevel.LOW, 1)

        // Less than a day watched gives no daily figure; no charge gives no habits.
        assertThat(HealthReader.habits(charges, LevelTime(3_600_000, day - 1))?.highMinutesPerDay).isNull()
        assertThat(HealthReader.habits(emptyList(), LevelTime(0, 0))).isNull()
    }
}

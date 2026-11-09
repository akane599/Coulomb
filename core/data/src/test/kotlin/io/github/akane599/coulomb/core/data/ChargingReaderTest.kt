// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.analytics.ChargeLevel
import io.github.akane599.coulomb.core.analytics.ChargePhase
import io.github.akane599.coulomb.core.analytics.ChargeTimeBasis
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.ChargerProfileEntity
import org.junit.Test

class ChargingReaderTest {
    private val desk =
        ChargerProfileEntity(id = 7, name = "Desk", plugType = AC, baselineW = 16.0, firstSeenWall = 0, lastSeenWall = 0, notes = null)

    @Test
    fun aChargeOnAKnownChargerHasItsReadingsPhaseAndTimes() {
        val now = ChargingReader.now(climb(from = 20, to = 60), 0, history(profileId = 7), listOf(desk), targetPct = 80)

        assertThat(now.watts).isWithin(1e-9).of(16.0)
        assertThat(now.currentMa).isWithin(1e-9).of(4_000.0)
        assertThat(now.tempRiseDeciC).isEqualTo(40)
        assertThat(now.phase).isEqualTo(ChargePhase.FULL_SPEED)
        val charger = now.charger as LiveCharger.Known
        assertThat(charger.charger.name).isEqualTo("Desk")
        assertThat(charger.shareOfBaseline).isWithin(1e-9).of(1.0)
        // Past charges took 20 min from 60 to 80 at this pace, and 60 more to full: ±10% each.
        val toTarget = checkNotNull(now.toTarget)
        assertThat(toTarget.basis).isEqualTo(ChargeTimeBasis.SAME_CHARGER)
        assertThat(toTarget.shortestMin).isWithin(1e-9).of(18.0)
        assertThat(toTarget.longestMin).isWithin(1e-9).of(22.0)
        val toFull = checkNotNull(now.toFull)
        assertThat(toFull.shortestMin).isWithin(1e-9).of(72.0)
        assertThat(toFull.longestMin).isWithin(1e-9).of(88.0)
    }

    @Test
    fun pastChargesOnAnotherChargerCountAsAnyCharger() {
        val now = ChargingReader.now(climb(from = 20, to = 60), 0, history(profileId = 3), listOf(desk), targetPct = 80)
        assertThat(checkNotNull(now.toTarget).basis).isEqualTo(ChargeTimeBasis.ANY_CHARGER)
    }

    @Test
    fun aFullSpeedFarFromEveryBaselineIsANewCharger() {
        val fast = desk.copy(baselineW = 30.0)
        val now = ChargingReader.now(climb(from = 20, to = 60), 0, emptyList(), listOf(fast), targetPct = 80)
        assertThat(now.charger).isEqualTo(LiveCharger.New(AC, 16.0))
        assertThat(now.toTarget).isNull()
    }

    @Test
    fun tooFewReadingsAreLearningAndAHighStartCannotTell() {
        assertThat(ChargingReader.now(climb(from = 20, to = 24), 0, emptyList(), listOf(desk), 80).charger)
            .isEqualTo(LiveCharger.Learning(AC))
        assertThat(ChargingReader.now(climb(from = 70, to = 78), 0, emptyList(), listOf(desk), 80).charger)
            .isEqualTo(LiveCharger.StartedHigh(AC))
    }

    @Test
    fun atTheTargetOnlyTimeToFullIsAsked() {
        val now = ChargingReader.now(climb(from = 20, to = 85), 0, history(profileId = 7), listOf(desk), targetPct = 80)
        assertThat(now.toTarget).isNull()
        assertThat(now.targetPct).isEqualTo(80)
    }

    @Test
    fun beforeCalibrationThereIsNoPowerOrPhase() {
        val samples = climb(from = 20, to = 30).map { it.copy(currentUa = null) }
        val now = ChargingReader.now(samples, 0, emptyList(), listOf(desk), 80)
        assertThat(now.watts).isNull()
        assertThat(now.phase).isNull()
        assertThat(now.charger).isEqualTo(LiveCharger.Learning(AC))
    }

    @Test
    fun aHighStartReadsItsPhaseFromPastChargesOnTheSamePlug() {
        val tapering = climb(from = 82, to = 90).map { it.copy(currentUa = 1_200_000) }
        val withHistory = ChargingReader.now(tapering, 0, history(profileId = 3), listOf(desk), 95)
        assertThat(withHistory.charger).isEqualTo(LiveCharger.StartedHigh(AC))
        assertThat(withHistory.phase).isEqualTo(ChargePhase.SLOWING)
        // With nothing to compare against, no phase rather than "full speed".
        assertThat(ChargingReader.now(tapering, 0, emptyList(), listOf(desk), 95).phase).isNull()
    }

    @Test
    fun drawingFromTheBatteryWhilePluggedHasNoTimes() {
        val base = climb(from = 60, to = 60).single()
        val falling =
            (1..5).map { i ->
                base.copy(elapsedNs = (40 + i) * NS_PER_MIN, wallMs = (40 + i) * MS_PER_MIN, levelPct = 60 - i, currentUa = -300_000)
            }
        val drawing = climb(from = 20, to = 60) + falling
        val now = ChargingReader.now(drawing, 0, history(profileId = 7), listOf(desk), 80)
        assertThat(now.phase).isEqualTo(ChargePhase.DRAWING)
        assertThat(now.toTarget).isNull()
        assertThat(now.toFull).isNull()
        // Net power out of the battery is shown as it is, never clamped to zero (gate critique).
        assertThat(now.watts).isWithin(1e-9).of(-1.2)
        assertThat(now.belowHighest).isTrue()
    }

    @Test
    fun aChargerIsNotNamedInItsFirstTwoMinutes() {
        val ramp = (0..20).map { i -> climb(from = 20, to = 20).single().copy(elapsedNs = i * 5_000_000_000L, wallMs = i * 5_000L) }
        assertThat(ChargingReader.now(ramp, 0, emptyList(), listOf(desk), 80).charger).isEqualTo(LiveCharger.Learning(AC))
    }

    /** One sample a minute, a level a minute, at 4 A and 4.0 V, warming 0.1 °C a minute. */
    private fun climb(
        from: Int,
        to: Int,
    ): List<BatterySampleEntity> =
        (from..to).mapIndexed { i, level ->
            BatterySampleEntity(
                bootSeq = 1,
                elapsedNs = i * NS_PER_MIN,
                wallMs = i * MS_PER_MIN,
                levelPct = level,
                chargeUah = null,
                currentUa = 4_000_000,
                currentRaw = null,
                voltageMv = 4_000,
                tempDeciC = 300 + i,
                plugType = AC,
                status = 2,
                screenOn = true,
                dozeState = 0,
                thermalStatus = 0,
            )
        }

    /** Three charges from 10% to full: a minute a level to 80%, then three. */
    private fun history(profileId: Long): List<PastChargeRow> =
        (1..3).map { id ->
            var minute = 0L
            val points =
                (10..100).map { level ->
                    ChargeLevel(minute * NS_PER_MIN, level).also { minute += if (level < 80) 1 else 3 }
                }
            PastChargeRow(id.toLong(), profileId, AC, 80, points)
        }

    private companion object {
        const val AC = 1
        const val NS_PER_MIN = 60_000_000_000L
        const val MS_PER_MIN = 60_000L
    }
}

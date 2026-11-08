// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import org.junit.Test
import java.time.ZoneOffset

class DischargeSummariesTest {
    private val zone = ZoneOffset.UTC
    private val day = 20_400L * 86_400_000 // a midnight

    private fun hour(h: Double) = day + (h * 3_600_000).toLong()

    private fun session(
        kind: SessionKind,
        from: Double,
        to: Double?,
        levels: Pair<Int, Int?>,
        awake: Double? = null,
    ) = SessionEntity(
        kind = kind,
        bootSeq = 1,
        startElapsedNs = 0,
        endElapsedNs = null,
        startWall = hour(from),
        endWall = to?.let(::hour),
        startSnapshotId = null,
        endSnapshotId = null,
        startLevel = levels.first,
        endLevel = levels.second,
        startChargeUah = null,
        endChargeUah = null,
        chargeSource = null,
        avgCurrentMa = null,
        peakCurrentMa = null,
        minTempDeciC = null,
        meanTempDeciC = null,
        maxTempDeciC = 300,
        awakeRatio = awake,
        deepIdleReached = null,
        deepIdleReachedMs = null,
        minutesAtOrAbove80 = null,
        minutesAt100 = null,
        stressIndex = null,
        cycleEquivalents = null,
        closeReason = if (to == null) null else SessionCloseReason.STATE_CHANGED,
        quality = 1.0,
    )

    private fun sample(
        at: Double,
        level: Int,
        plugged: Boolean = false,
        screenOn: Boolean = true,
    ) = BatterySampleEntity(
        bootSeq = 1,
        elapsedNs = 0,
        wallMs = hour(at),
        levelPct = level,
        chargeUah = null,
        currentUa = -410_000,
        currentRaw = null,
        voltageMv = 3_900,
        tempDeciC = 300,
        plugType = if (plugged) 1 else 0,
        status = 3,
        screenOn = screenOn,
        dozeState = 0,
        thermalStatus = 0,
    )

    @Test
    fun theStripSplitsTheOpenDischargeByScreenStateAndLeavesTheChargerOut() {
        val sessions =
            listOf(
                // The screen came on before the unplug at 8:00, while charging.
                session(SessionKind.SCREEN_ON, 7.0, 9.0, 70 to 88),
                session(SessionKind.DISCHARGE, 8.0, null, 90 to null),
                session(SessionKind.SCREEN_OFF, 9.0, 12.0, 88 to 85, awake = 0.2),
                session(SessionKind.SCREEN_ON, 12.0, null, 85 to null),
            )

        val summary = DischargeSummaries.build(sample(13.0, 80), sessions, zone)!!

        assertThat(summary.startWallMs).isEqualTo(hour(8.0))
        assertThat(summary.startLevel).isEqualTo(90)
        // On: 8–9 (90 → 88) and 12–13 (85 → 80): 7% over 2 h. Off: 3% over 3 h.
        assertThat(summary.screenOn!!.pctPerHour).isWithin(1e-9).of(3.5)
        assertThat(summary.screenOn.hours).isWithin(1e-9).of(2.0)
        assertThat(summary.screenOff!!.pctPerHour).isWithin(1e-9).of(1.0)
        assertThat(summary.deepSleepShare).isWithin(1e-9).of(0.8)
        // No history: the screen-on session alone, 80% at 3.5 %/h, as a range around 22.9 h.
        val left = summary.timeLeft!!
        assertThat(left.shortestHours).isLessThan(80 / 3.5)
        assertThat(left.longestHours).isGreaterThan(80 / 3.5)
    }

    @Test
    fun pastDaysAtTheSameHourFeedTheEstimate() {
        val past =
            (1..5).flatMap { d ->
                val back = -24.0 * d
                listOf(
                    session(SessionKind.DISCHARGE, back + 8.0, back + 20.0, 100 to 40),
                    session(SessionKind.SCREEN_ON, back + 12.0, back + 14.0, 80 to 60),
                )
            }
        val now =
            listOf(
                session(SessionKind.DISCHARGE, 12.0, null, 90 to null),
                session(SessionKind.SCREEN_ON, 12.0, null, 90 to null),
            )

        // Ten minutes in: the session barely counts, so the past days' 10 %/h sets the range.
        val left = DischargeSummaries.build(sample(12.0 + 1.0 / 6, 89), past + now, zone)!!.timeLeft!!

        assertThat(left.shortestHours).isWithin(0.5).of(89 / 11.0)
        assertThat(left.longestHours).isWithin(0.5).of(89 / 9.0)
    }

    @Test
    fun pluggedInOrWithoutAnOpenDischargeThereIsNoSummary() {
        val open = listOf(session(SessionKind.DISCHARGE, 8.0, null, 90 to null))
        assertThat(DischargeSummaries.build(sample(9.0, 85, plugged = true), open, zone)).isNull()
        assertThat(DischargeSummaries.build(sample(9.0, 85), emptyList(), zone)).isNull()
        val summary = DischargeSummaries.build(sample(9.0, 85), open, zone)!!
        assertThat(summary.screenOn).isNull()
        assertThat(summary.deepSleepShare).isNull()
        assertThat(summary.timeLeft).isNull()
    }
}

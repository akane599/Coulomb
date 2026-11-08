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
        cut: SessionCloseReason = SessionCloseReason.STATE_CHANGED,
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
        closeReason = if (to == null) null else cut,
        // As stored: an open session's quality is 0 until it closes (SessionSummary.open).
        quality = if (to == null) 0.0 else 1.0,
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
        val sleep = summary.deepSleep!!
        assertThat(sleep.share).isWithin(1e-9).of(0.8)
        assertThat(sleep.hours).isWithin(1e-9).of(3.0)
        assertThat(sleep.quality).isWithin(1e-9).of(1.0)
        assertThat(sleep.ready).isTrue()
        // No history: the screen-on session alone, 80% at 3.5 %/h, as a range around 22.9 h.
        val left = summary.timeLeft!!
        assertThat(left.shortestHours).isLessThan(80 / 3.5)
        assertThat(left.longestHours).isGreaterThan(80 / 3.5)
    }

    private fun sleepAfter(hours: Double): DeepSleepShare {
        val sessions =
            listOf(
                session(SessionKind.DISCHARGE, 8.0, null, 90 to null),
                session(SessionKind.SCREEN_OFF, 8.0, 8.0 + hours, 90 to 90, awake = 0.1),
            )
        return DischargeSummaries.build(sample(9.5, 90), sessions, zone)!!.deepSleep!!
    }

    @Test
    fun deepSleepWaitsForAQuarterHourThenGainsConfidenceUpToAnHour() {
        // Not ready a minute short of 15 min, ready at 15 min.
        assertThat(sleepAfter(0.25 - 1.0 / 60).ready).isFalse()
        val quarter = sleepAfter(0.25)
        assertThat(quarter.ready).isTrue()
        // A clean quarter hour earns a quarter: low confidence (SPEC 6.7) until half an hour.
        assertThat(quarter.quality).isWithin(1e-9).of(0.25)
        assertThat(sleepAfter(0.5).quality).isWithin(1e-9).of(0.5)
        assertThat(sleepAfter(1.25).quality).isWithin(1e-9).of(1.0)
    }

    @Test
    fun deepSleepMarksSessionsCutShort() {
        // One clean hour and three hours cut short by a reboot: quality (1 + 3 × 0.5) / 4.
        val cut =
            listOf(
                session(SessionKind.DISCHARGE, 8.0, null, 90 to null),
                session(SessionKind.SCREEN_OFF, 8.0, 9.0, 90 to 89, awake = 0.1),
                session(SessionKind.SCREEN_OFF, 9.0, 12.0, 89 to 86, awake = 0.3, cut = SessionCloseReason.REBOOT),
            )
        val sleep = DischargeSummaries.build(sample(12.5, 85), cut, zone)!!.deepSleep!!
        assertThat(sleep.share).isWithin(1e-9).of(1 - (0.1 + 3 * 0.3) / 4)
        assertThat(sleep.quality).isWithin(1e-9).of(0.625)
        assertThat(sleep.ready).isTrue()

        // Forty minutes, all cut short: 0.5 × 40/60 is under 0.5, so the share is low confidence.
        val short =
            listOf(
                session(SessionKind.DISCHARGE, 8.0, null, 90 to null),
                session(SessionKind.SCREEN_OFF, 8.0, 8.0 + 40.0 / 60, 90 to 90, awake = 0.1, cut = SessionCloseReason.DATA_GAP),
            )
        val gap = DischargeSummaries.build(sample(9.0, 90), short, zone)!!.deepSleep!!
        assertThat(gap.quality).isWithin(1e-6).of(0.5 * 40 / 60)
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

        // Ten minutes in, a 1% drop: the session earns nothing, so five past days at 10 %/h set the
        // range, widened for five days of history (TimeRemaining's 0.5 / √5).
        val summary = DischargeSummaries.build(sample(12.0 + 1.0 / 6, 89), past + now, zone)!!
        val left = summary.timeLeft!!

        val spread = 0.5 / Math.sqrt(5.0)
        assertThat(left.shortestHours).isWithin(1e-6).of(89 / (10 * (1 + spread)))
        assertThat(left.longestHours).isWithin(1e-6).of(89 / (10 * (1 - spread)))
        // SPEC 6.2: ten minutes is too soon to show a rate.
        assertThat(summary.screenOn!!.ready).isFalse()
    }

    @Test
    fun theOpenSessionCountsThoughItStoresNoQualityYet() {
        val now =
            listOf(
                session(SessionKind.DISCHARGE, 10.0, null, 90 to null),
                session(SessionKind.SCREEN_ON, 10.0, null, 90 to null),
            )

        // Two hours of screen-on use, 20% gone: 10 %/h, with no history at all.
        val summary = DischargeSummaries.build(sample(12.0, 70), now, zone)!!

        assertThat(summary.screenOn!!.ready).isTrue()
        assertThat(summary.screenOn.quality).isWithin(1e-9).of(1 - 1.0 / 20)
        val left = summary.timeLeft!!
        assertThat(left.shortestHours).isLessThan(7.0)
        assertThat(left.longestHours).isGreaterThan(7.0)
    }

    @Test
    fun aSessionCutShortCountsHalfAndOneWithoutAnEndIsSkipped() {
        val sessions =
            listOf(
                session(SessionKind.DISCHARGE, 8.0, null, 90 to null),
                session(SessionKind.SCREEN_OFF, 8.0, 10.0, 90 to 80).copy(closeReason = SessionCloseReason.REBOOT),
                // Closed without an end level: no rate can come from it.
                session(SessionKind.SCREEN_ON, 10.0, 11.0, 80 to null),
            )

        val summary = DischargeSummaries.build(sample(11.5, 75), sessions, zone)!!

        assertThat(summary.screenOff!!.quality).isWithin(1e-9).of(0.5 * (1 - 1.0 / 10))
        assertThat(summary.screenOn).isNull()
    }

    @Test
    fun aSampleFromBeforeTheUnplugIsNotRead() {
        val open = listOf(session(SessionKind.DISCHARGE, 8.0, null, 90 to null))
        assertThat(DischargeSummaries.build(sample(7.5, 92), open, zone)).isNull()
    }

    @Test
    fun pluggedInOrWithoutAnOpenDischargeThereIsNoSummary() {
        val open = listOf(session(SessionKind.DISCHARGE, 8.0, null, 90 to null))
        assertThat(DischargeSummaries.build(sample(9.0, 85, plugged = true), open, zone)).isNull()
        assertThat(DischargeSummaries.build(sample(9.0, 85), emptyList(), zone)).isNull()
        val summary = DischargeSummaries.build(sample(9.0, 85), open, zone)!!
        assertThat(summary.screenOn).isNull()
        assertThat(summary.deepSleep).isNull()
        assertThat(summary.timeLeft).isNull()
    }
}

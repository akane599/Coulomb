// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.surface

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.data.ChargeCurvePoint
import io.github.akane599.coulomb.core.data.ChargingNow
import io.github.akane599.coulomb.core.data.LiveCharger
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import org.junit.Test

class SurfaceRulesTest {
    private fun charge(
        levelPct: Int,
        atMs: Long,
        startLevelPct: Int = 20,
        startMs: Long = 0,
        targetPct: Int = 80,
        watts: List<Double?> = emptyList(),
    ) = ChargingNow(
        levelPct = levelPct,
        plugType = 1,
        watts = watts.lastOrNull(),
        currentMa = null,
        voltageMv = 4_200,
        tempDeciC = 300,
        tempRiseDeciC = null,
        phase = null,
        charger = LiveCharger.Learning(1),
        targetPct = targetPct,
        belowHighest = false,
        toTarget = null,
        toFull = null,
        startWallMs = startMs,
        startLevelPct = startLevelPct,
        curve = watts.mapIndexed { i, w -> ChargeCurvePoint(startMs + i, startLevelPct, w, 300) },
        wallMs = atMs,
    )

    @Test
    fun aChargeShowsAtOnceThenOnALevelChangeNoSoonerThanThirtySeconds() {
        val updates = ChargingUpdates()
        assertThat(updates.next(charge(20, 0), 0, 20)).isInstanceOf(ChargingStep.Show::class.java)
        // The same level again: nothing to say.
        assertThat(updates.next(charge(20, 40_000), 40_000, 20)).isEqualTo(ChargingStep.None)
        // A point more after 10 s of the last update: too soon.
        assertThat(updates.next(charge(21, 10_000), 10_000, 21)).isEqualTo(ChargingStep.None)
        val shown = updates.next(charge(22, 31_000), 31_000, 22)
        assertThat((shown as ChargingStep.Show).now.levelPct).isEqualTo(22)
    }

    @Test
    fun unpluggingEndsTheChargeWithItsSummaryOnce() {
        val updates = ChargingUpdates()
        updates.next(charge(20, 0, watts = listOf(20.0, null, 22.0, -1.0)), 0, 20)
        val end = updates.next(null, 38 * 60_000L, 66)
        // The level and the time are the unplugging's; the power is the mean of the readings into the battery.
        assertThat(end).isEqualTo(ChargingStep.End(ChargeSummary(gainedPct = 46, durationMs = 38 * 60_000L, averageWatts = 21.0)))
        assertThat(updates.next(null, 39 * 60_000L, 66)).isEqualTo(ChargingStep.None)
    }

    @Test
    fun aProcessThatStartsUnpluggedClearsALeftoverOnce() {
        val updates = ChargingUpdates()
        assertThat(updates.next(null, 0, 50)).isEqualTo(ChargingStep.Clear)
        assertThat(updates.next(null, 1, 50)).isEqualTo(ChargingStep.None)
    }

    @Test
    fun aNewChargeShowsAtOnceEvenAtTheLevelOfTheLast() {
        val updates = ChargingUpdates()
        updates.next(charge(50, 0), 0, 50)
        updates.next(null, 1_000, 50)
        assertThat(
            updates.next(charge(50, 2_000, startLevelPct = 50, startMs = 2_000), 2_000, 50),
        ).isInstanceOf(ChargingStep.Show::class.java)
    }

    @Test
    fun theBarRunsFromTheStartLevelToFullWithTheTargetOnIt() {
        val bar = ChargeBar.of(charge(62, 0, startLevelPct = 20, targetPct = 80))
        assertThat(bar).isEqualTo(ChargeBar(max = 80, progress = 42, toTarget = 60, targetAt = 60))
        assertThat(bar.pastTarget).isEqualTo(20)
    }

    @Test
    fun aChargeBegunAboveTheTargetHasOneSegmentAndNoPoint() {
        val bar = ChargeBar.of(charge(92, 0, startLevelPct = 90, targetPct = 80))
        assertThat(bar).isEqualTo(ChargeBar(max = 10, progress = 2, toTarget = 10, targetAt = null))
        // A target of 100% is the bar's end, not a point on it.
        assertThat(ChargeBar.of(charge(50, 0, targetPct = 100)).targetAt).isNull()
        // A charge that began full still has a bar.
        assertThat(ChargeBar.of(charge(100, 0, startLevelPct = 100)).max).isEqualTo(1)
    }

    @Test
    fun theTargetAlarmFiresWhenTheLevelCrossesItOnTheCharger() {
        val rules = AlertRules()
        assertThat(rules.next(79, true, 300, 0, 80, null)).isEmpty()
        assertThat(rules.next(80, true, 300, 1, 80, null)).containsExactly(Alert.TargetReached(80))
        // Once per charge: staying there or going on says nothing more.
        assertThat(rules.next(80, true, 300, 2, 80, null)).isEmpty()
        assertThat(rules.next(81, true, 300, 3, 80, null)).isEmpty()
        // Audit BUG-012: a dip under load and a second crossing on the same charger neither.
        assertThat(rules.next(79, true, 300, 4, 80, null)).isEmpty()
        assertThat(rules.next(80, true, 300, 5, 80, null)).isEmpty()
        // A new charge after an unplug rings again.
        rules.next(70, false, 300, 6, 80, null)
        rules.next(79, true, 300, 7, 80, null)
        assertThat(rules.next(80, true, 300, 8, 80, null)).containsExactly(Alert.TargetReached(80))
    }

    @Test
    fun theTargetAlarmNeedsACrossingSeenOnTheCharger() {
        // Already at the target when Coulomb first looks: no crossing was seen.
        assertThat(AlertRules().next(85, true, 300, 0, 80, null)).isEmpty()
        // Plugged in at the target after a discharge: the level before was not a charging one.
        val rules = AlertRules()
        rules.next(79, false, 300, 0, 80, null)
        assertThat(rules.next(80, true, 300, 1, 80, null)).isEmpty()
        // Off (no target): never.
        val off = AlertRules()
        off.next(79, true, 300, 0, null, null)
        assertThat(off.next(80, true, 300, 1, null, null)).isEmpty()
    }

    @Test
    fun theTemperatureAlarmFiresAtItsThresholdAndRestsHalfAnHour() {
        val rules = AlertRules()
        assertThat(rules.next(50, false, 419, 0, null, 420)).isEmpty()
        assertThat(rules.next(50, false, 420, 1, null, 420)).containsExactly(Alert.Hot(420))
        assertThat(rules.next(50, false, 450, AlertRules.TEMPERATURE_REST_MS, null, 420)).isEmpty()
        assertThat(rules.next(50, false, 450, AlertRules.TEMPERATURE_REST_MS + 1, null, 420)).containsExactly(Alert.Hot(450))
        // Off.
        assertThat(AlertRules().next(50, false, 500, 0, null, null)).isEmpty()
    }

    @Test
    fun aHeatToldByAnInsightRestsTheAlarm() {
        val rules = AlertRules()
        rules.rest(1_000)
        assertThat(rules.next(50, true, 450, 2_000, null, 420)).isEmpty()
    }

    @Test
    fun theDigestOpensOnceADayFromItsMinute() {
        fun open(
            minute: Int,
            last: Long?,
            enabled: Boolean = true,
        ) = DigestRules.open(enabled, minute, 9 * 60, 100, last)
        assertThat(open(8 * 60 + 59, null)).isFalse()
        assertThat(open(9 * 60, null)).isTrue()
        assertThat(open(23 * 60, 99)).isTrue()
        assertThat(open(23 * 60, 100)).isFalse()
        assertThat(open(12 * 60, null, enabled = false)).isFalse()
    }

    @Test
    fun theDigestNamesTheNewInsightsOnlyWhenOneCameSinceTheLast() {
        assertThat(DigestRules.fresh(listOf(3L, 5L), null) { it }).containsExactly(3L, 5L)
        assertThat(DigestRules.fresh(listOf(3L, 5L), 4L) { it }).containsExactly(3L, 5L)
        // Both were in the last digest and are still unseen: nothing new to say.
        assertThat(DigestRules.fresh(listOf(3L, 5L), 5L) { it }).isEmpty()
        assertThat(DigestRules.fresh(emptyList<Long>(), null) { it }).isEmpty()
    }

    @Test
    fun aLostAccessIsToldOnce() {
        assertThat(PrivilegeNotices.toPost(PrivilegeIssue.MANAGER_STOPPED, null)).isEqualTo(PrivilegeIssue.MANAGER_STOPPED)
        assertThat(PrivilegeNotices.toPost(PrivilegeIssue.MANAGER_STOPPED, "MANAGER_STOPPED")).isNull()
        // Another loss while the first stands is news.
        assertThat(PrivilegeNotices.toPost(PrivilegeIssue.GRANTS_REVOKED, "MANAGER_STOPPED")).isEqualTo(PrivilegeIssue.GRANTS_REVOKED)
        // Basic mode by choice is not a loss.
        assertThat(PrivilegeNotices.toPost(PrivilegeIssue.MANAGER_NOT_INSTALLED, null)).isNull()
        assertThat(PrivilegeNotices.toPost(null, "MANAGER_STOPPED")).isNull()
    }
}

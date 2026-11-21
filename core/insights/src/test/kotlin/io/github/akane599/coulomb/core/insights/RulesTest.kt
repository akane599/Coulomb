// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SPEC 16.1: each rule with a firing case, a non-firing case and a boundary case, against the
 * thresholds the app ships with. A boundary case sits exactly on the threshold, which SPEC 9.2
 * words as "more than", so it must not fire.
 */
class RulesTest {
    private val thresholds = Thresholds.bundled()
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val lastDay = Span(now - day, now)
    private val app = 10_123

    private fun run(
        rule: RuleId,
        ctx: RuleContext,
    ): List<InsightCandidate> = Rules.all.single { it.id == rule }.evaluate(ctx, thresholds.of(rule))

    private fun dayOf(
        vararg apps: AppDayUse,
        screenOffMs: Long = 8 * hour,
    ) = RuleContext(now, day = DayUse(lastDay, screenOffMs, apps.toList()))

    private fun fires(
        rule: RuleId,
        ctx: RuleContext,
    ): InsightCandidate = run(rule, ctx).single()

    private fun quiet(
        rule: RuleId,
        ctx: RuleContext,
    ) = assertThat(run(rule, ctx)).isEmpty()

    @Test
    fun r01ExcessiveWakelock() {
        val found =
            fires(
                RuleId.EXCESSIVE_WAKELOCK,
                dayOf(AppDayUse(app, partialWakelockMs = 3 * hour), AppDayUse(10_200, partialWakelockMs = hour)),
            )
        assertThat(found.subject).isEqualTo(Subject.app(app))
        assertThat(found.evidence.measured).isEqualTo(3.0)
        assertThat(found.evidence.threshold).isEqualTo(2.0)
        assertThat(found.severity).isEqualTo(RuleId.EXCESSIVE_WAKELOCK.severity)
        quiet(RuleId.EXCESSIVE_WAKELOCK, dayOf(AppDayUse(app, partialWakelockMs = hour)))
        quiet(RuleId.EXCESSIVE_WAKELOCK, dayOf(AppDayUse(app, partialWakelockMs = 2 * hour)))
        quiet(RuleId.EXCESSIVE_WAKELOCK, RuleContext(now))
    }

    @Test
    fun r02LongWakelockTag() {
        fun holds(ms: Long) = RuleContext(now, tagHolds = listOf(TagHold("*sync*/mail", app, ms, lastDay, windowId = 9)))
        val found = fires(RuleId.LONG_WAKELOCK_TAG, holds(61 * minute))
        assertThat(found.subject).isEqualTo(Subject("wakelock:*sync*/mail", app))
        assertThat(found.windowId).isEqualTo(9)
        assertThat(found.evidence.labels).containsExactly("tag", "*sync*/mail")
        quiet(RuleId.LONG_WAKELOCK_TAG, holds(20 * minute))
        quiet(RuleId.LONG_WAKELOCK_TAG, holds(60 * minute))
    }

    private fun night(
        id: Long,
        endedAgo: Long,
        hours: Double = 7.0,
        awake: Double? = null,
        rate: Double? = null,
        deep: Boolean? = null,
    ): ScreenOffSession {
        val ms = (hours * hour).toLong()
        val drop = rate?.let { (it * hours).toInt() }
        return ScreenOffSession(id, Span(now - endedAgo - ms, now - endedAgo), ms, awake, rate, drop, deep, windowId = 100 + id)
    }

    private fun nights(vararg sessions: ScreenOffSession) = RuleContext(now, screenOffSessions = sessions.toList())

    @Test
    fun r03PoorDeepSleep() {
        val found = fires(RuleId.POOR_DEEP_SLEEP, nights(night(1, day, awake = 0.05), night(2, hour, awake = 0.25)))
        assertThat(found.subject).isNull()
        assertThat(found.evidence.sessionId).isEqualTo(2)
        assertThat(found.evidence.measured).isEqualTo(25.0)
        assertThat(found.windowId).isEqualTo(102)
        // The latest night that qualifies is the one judged, and it was fine.
        quiet(RuleId.POOR_DEEP_SLEEP, nights(night(1, day, awake = 0.25), night(2, hour, awake = 0.05)))
        quiet(RuleId.POOR_DEEP_SLEEP, nights(night(1, hour, awake = 0.15)))
        // Under an hour, or with no awake figure, a session is not judged.
        quiet(RuleId.POOR_DEEP_SLEEP, nights(night(1, hour, hours = 0.9, awake = 0.9), night(2, 0, awake = null)))
        assertThat(run(RuleId.POOR_DEEP_SLEEP, nights(night(1, hour, hours = 1.0, awake = 0.16)))).hasSize(1)
    }

    @Test
    fun r04HighScreenOffDrain() {
        fun usual(
            rate: Double,
            count: Int = 5,
        ) = (1..count).map { night(it.toLong(), it * day, rate = rate) }.reversed().toTypedArray()

        val found = fires(RuleId.HIGH_SCREEN_OFF_DRAIN, nights(*usual(0.5), night(9, hour, rate = 1.2)))
        assertThat(found.evidence.threshold).isEqualTo(1.0)
        assertThat(found.evidence.values).containsEntry("median", 0.5)
        assertThat(found.evidence.sessionId).isEqualTo(9)
        quiet(RuleId.HIGH_SCREEN_OFF_DRAIN, nights(*usual(0.5), night(9, hour, rate = 0.9)))
        quiet(RuleId.HIGH_SCREEN_OFF_DRAIN, nights(*usual(0.5), night(9, hour, rate = 1.0)))
        // Twice a very low median is still not a high rate: the 0.8 %/h floor holds.
        quiet(RuleId.HIGH_SCREEN_OFF_DRAIN, nights(*usual(0.2), night(9, hour, rate = 0.8)))
        assertThat(run(RuleId.HIGH_SCREEN_OFF_DRAIN, nights(*usual(0.2), night(9, hour, rate = 0.81)))).hasSize(1)
        // A night that lost two level points is within the gauge's rounding, whatever its rate.
        quiet(RuleId.HIGH_SCREEN_OFF_DRAIN, nights(*usual(0.5), night(9, hour, hours = 1.5, rate = 1.9)))
        // Too few earlier nights, or nights older than 14 days, give no baseline.
        quiet(RuleId.HIGH_SCREEN_OFF_DRAIN, nights(*usual(0.5, count = 4), night(9, hour, rate = 5.0)))
        val old = (1..5).map { night(it.toLong(), (20 + it) * day, rate = 0.5) }.reversed().toTypedArray()
        quiet(RuleId.HIGH_SCREEN_OFF_DRAIN, nights(*old, night(9, hour, rate = 5.0)))
    }

    @Test
    fun r05AlarmStorm() {
        val found = fires(RuleId.ALARM_STORM, dayOf(AppDayUse(app, alarmWakeups = 25), screenOffMs = 2 * hour))
        assertThat(found.evidence.measured).isEqualTo(12.5)
        assertThat(found.evidence.unit).isEqualTo(EvidenceUnit.PER_HOUR)
        quiet(RuleId.ALARM_STORM, dayOf(AppDayUse(app, alarmWakeups = 10), screenOffMs = 2 * hour))
        quiet(RuleId.ALARM_STORM, dayOf(AppDayUse(app, alarmWakeups = 24), screenOffMs = 2 * hour))
        // Half an hour of screen-off time is too little to call a rate.
        quiet(RuleId.ALARM_STORM, dayOf(AppDayUse(app, alarmWakeups = 100), screenOffMs = 30 * minute))
        quiet(RuleId.ALARM_STORM, dayOf(AppDayUse(app, alarmWakeups = 100), screenOffMs = 0))
    }

    @Test
    fun r06BackgroundLocation() {
        assertThat(
            fires(RuleId.BACKGROUND_LOCATION, dayOf(AppDayUse(app, gnssScreenOffMs = 11 * minute))).evidence.measured,
        ).isEqualTo(11.0)
        quiet(RuleId.BACKGROUND_LOCATION, dayOf(AppDayUse(app, gnssScreenOffMs = 2 * minute)))
        quiet(RuleId.BACKGROUND_LOCATION, dayOf(AppDayUse(app, gnssScreenOffMs = 10 * minute)))
    }

    @Test
    fun r07WeakSignalTax() {
        fun signal(
            modem: Double?,
            vararg bins: Long,
        ) = RuleContext(now, day = DayUse(lastDay, 8 * hour, signal = SignalUse(bins.toList(), modem)))

        val found = fires(RuleId.WEAK_SIGNAL_TAX, signal(0.2, 2 * hour, 2 * hour, 2 * hour, 2 * hour, 2 * hour))
        assertThat(found.evidence.measured).isEqualTo(40.0)
        assertThat(found.evidence.values).containsEntry("modemShare", 20.0)
        // Weak signal without a costly modem, and a costly modem with good signal.
        quiet(RuleId.WEAK_SIGNAL_TAX, signal(0.05, 5 * hour, 0, 0, 0, 5 * hour))
        quiet(RuleId.WEAK_SIGNAL_TAX, signal(0.4, 0, hour, 0, 0, 9 * hour))
        quiet(RuleId.WEAK_SIGNAL_TAX, signal(0.4, 3 * hour, 0, 7 * hour, 0, 0))
        quiet(RuleId.WEAK_SIGNAL_TAX, signal(0.15, 5 * hour, 0, 0, 0, 5 * hour))
        quiet(RuleId.WEAK_SIGNAL_TAX, signal(null, 5 * hour, 0, 0, 0, 5 * hour))
        quiet(RuleId.WEAK_SIGNAL_TAX, signal(0.4, hour, 0, 0, 0, 30 * minute))
    }

    @Test
    fun r08ScanHeavy() {
        fun scans(
            wifi: Long,
            ble: Long,
        ) = RuleContext(now, day = DayUse(lastDay, 8 * hour, wifiScanMs = wifi, bleScanMs = ble))

        assertThat(fires(RuleId.SCAN_HEAVY, scans(25 * minute, 0)).evidence.labels).containsExactly("radio", "wifi")
        val bluetooth = fires(RuleId.SCAN_HEAVY, scans(25 * minute, 30 * minute))
        assertThat(bluetooth.evidence.labels).containsExactly("radio", "bluetooth")
        assertThat(bluetooth.evidence.measured).isEqualTo(30.0)
        quiet(RuleId.SCAN_HEAVY, scans(15 * minute, 15 * minute))
        quiet(RuleId.SCAN_HEAVY, scans(20 * minute, 20 * minute))
    }

    @Test
    fun r09SensorHolder() {
        assertThat(fires(RuleId.SENSOR_HOLDER, dayOf(AppDayUse(app, sensorScreenOffMs = 45 * minute))).evidence.measured).isEqualTo(45.0)
        quiet(RuleId.SENSOR_HOLDER, dayOf(AppDayUse(app, sensorScreenOffMs = 5 * minute)))
        quiet(RuleId.SENSOR_HOLDER, dayOf(AppDayUse(app, sensorScreenOffMs = 30 * minute)))
    }

    @Test
    fun r10JobChurn() {
        assertThat(fires(RuleId.JOB_CHURN, dayOf(AppDayUse(app, jobCount = 51))).evidence.measured).isEqualTo(51.0)
        val abandoned = fires(RuleId.JOB_CHURN, dayOf(AppDayUse(app, jobCount = 3, abandonedJobs = 1)))
        assertThat(abandoned.evidence.labels).containsExactly("reason", "abandoned")
        assertThat(abandoned.evidence.measured).isEqualTo(1.0)
        quiet(RuleId.JOB_CHURN, dayOf(AppDayUse(app, jobCount = 12, abandonedJobs = 0)))
        quiet(RuleId.JOB_CHURN, dayOf(AppDayUse(app, jobCount = 50)))
    }

    private fun charge(
        samples: List<TempSample> = emptyList(),
        avgW: Double? = null,
        baselineW: Double? = null,
        minutesAtFull: Int = 0,
        protectionOn: Boolean? = null,
    ) = RuleContext(
        now,
        chargeSessions = listOf(ChargeSession(4, lastDay, samples, avgW, baselineW, 3, "Desk charger", minutesAtFull, protectionOn)),
    )

    /** Readings one minute apart, the first at the session's start. */
    private fun readings(
        vararg tempC: Double,
        charging: Boolean = true,
    ) = tempC.mapIndexed { index, temp -> TempSample(lastDay.fromWallMs + index * minute, temp, charging) }

    @Test
    fun r11HotCharging() {
        val hot = DoubleArray(12) { 41.0 }
        val found = fires(RuleId.HOT_CHARGING, charge(readings(*hot)))
        assertThat(found.subject).isEqualTo(Subject.session(4))
        assertThat(found.evidence.measured).isEqualTo(11.0)
        assertThat(found.evidence.values).containsEntry("peakC", 41.0)
        quiet(RuleId.HOT_CHARGING, charge(readings(*DoubleArray(12) { 36.0 })))
        // Eleven readings span ten minutes: exactly the threshold.
        quiet(RuleId.HOT_CHARGING, charge(readings(*DoubleArray(11) { 41.0 })))
        quiet(RuleId.HOT_CHARGING, charge(readings(*DoubleArray(12) { 40.0 })))
        quiet(RuleId.HOT_CHARGING, charge(readings(*hot, charging = false)))
        // Two hot readings an hour apart say nothing about the hour between them.
        quiet(RuleId.HOT_CHARGING, charge(listOf(TempSample(now - hour, 44.0, true), TempSample(now, 44.0, true))))
        quiet(RuleId.HOT_CHARGING, charge())
    }

    @Test
    fun r12HotBattery() {
        fun temps(vararg tempC: Double) =
            RuleContext(
                now,
                temperatures =
                    tempC.mapIndexed { i, t ->
                        TempSample(
                            now - i * minute,
                            t,
                            topUid =
                                app + i,
                        )
                    },
            )

        val found = fires(RuleId.HOT_BATTERY, temps(39.0, 45.5, 41.0))
        assertThat(found.evidence.measured).isEqualTo(45.5)
        assertThat(found.relatedUid).isEqualTo(app + 1)
        assertThat(found.subject).isNull()
        assertThat(found.evidence.fromWallMs).isEqualTo(now - minute)
        quiet(RuleId.HOT_BATTERY, temps(30.0, 38.0))
        quiet(RuleId.HOT_BATTERY, temps(45.0))
        quiet(RuleId.HOT_BATTERY, temps())
    }

    @Test
    fun r13SlowCharging() {
        val found = fires(RuleId.SLOW_CHARGING, charge(avgW = 12.0, baselineW = 20.0))
        assertThat(found.subject).isEqualTo(Subject.charger(3))
        assertThat(found.evidence.values).containsEntry("baselineW", 20.0)
        assertThat(found.evidence.labels).containsExactly("charger", "Desk charger")
        quiet(RuleId.SLOW_CHARGING, charge(avgW = 19.0, baselineW = 20.0))
        quiet(RuleId.SLOW_CHARGING, charge(avgW = 100 * 0.70, baselineW = 100.0))
        quiet(RuleId.SLOW_CHARGING, charge(avgW = 5.0, baselineW = null))
        quiet(RuleId.SLOW_CHARGING, charge(avgW = null, baselineW = 20.0))
    }

    @Test
    fun r14CapacityDrop() {
        fun health(vararg points: Pair<Int, Double>) =
            RuleContext(
                now,
                health =
                    points.map { (daysAgo, pct) ->
                        HealthPoint(now - daysAgo * day, pct)
                    },
            )

        val found = fires(RuleId.CAPACITY_DROP, health(40 to 99.0, 30 to 95.0, 8 to 94.0, 0 to 91.5))
        assertThat(found.evidence.measured).isEqualTo(3.5)
        assertThat(found.evidence.values).containsExactly("fromPct", 95.0, "toPct", 91.5)
        quiet(RuleId.CAPACITY_DROP, health(30 to 95.0, 0 to 94.0))
        quiet(RuleId.CAPACITY_DROP, health(30 to 95.0, 0 to 92.0))
        // A fall seen only against a day older than 30 days, or closer than a week, is not judged.
        quiet(RuleId.CAPACITY_DROP, health(31 to 99.0, 0 to 91.0))
        quiet(RuleId.CAPACITY_DROP, health(6 to 99.0, 0 to 91.0))
        quiet(RuleId.CAPACITY_DROP, health())
    }

    @Test
    fun r15LongAtFull() {
        val found = fires(RuleId.LONG_AT_FULL, charge(minutesAtFull = 150, protectionOn = false))
        assertThat(found.evidence.measured).isEqualTo(2.5)
        assertThat(found.evidence.sessionId).isEqualTo(4)
        quiet(RuleId.LONG_AT_FULL, charge(minutesAtFull = 45, protectionOn = false))
        quiet(RuleId.LONG_AT_FULL, charge(minutesAtFull = 120, protectionOn = false))
        quiet(RuleId.LONG_AT_FULL, charge(minutesAtFull = 600, protectionOn = true))
        // An unread setting is not a setting that is off.
        quiet(RuleId.LONG_AT_FULL, charge(minutesAtFull = 600, protectionOn = null))
    }

    @Test
    fun r16UnusedActiveApp() {
        fun standby(
            active: Boolean,
            openedAgo: Long?,
        ) = RuleContext(now, standby = listOf(AppStandby(app, active, openedAgo?.let { now - it })))

        val found = fires(RuleId.UNUSED_ACTIVE_APP, standby(true, 10 * day))
        assertThat(found.evidence.measured).isEqualTo(10.0)
        assertThat(found.evidence.unit).isEqualTo(EvidenceUnit.DAYS)
        quiet(RuleId.UNUSED_ACTIVE_APP, standby(true, day))
        quiet(RuleId.UNUSED_ACTIVE_APP, standby(true, 7 * day))
        quiet(RuleId.UNUSED_ACTIVE_APP, standby(false, 30 * day))
        quiet(RuleId.UNUSED_ACTIVE_APP, standby(true, null))
    }

    @Test
    fun r17DozeNeverDeep() {
        val shallow = arrayOf(night(1, 3 * day, deep = false), night(2, 2 * day, deep = false), night(3, day, deep = false))
        val found = fires(RuleId.DOZE_NEVER_DEEP, nights(*shallow))
        assertThat(found.evidence.measured).isEqualTo(3.0)
        assertThat(found.evidence.sessionId).isEqualTo(3)
        assertThat(found.evidence.fromWallMs).isEqualTo(shallow.first().span.fromWallMs)
        // A short session in between is not one of the three, whatever it reached.
        assertThat(run(RuleId.DOZE_NEVER_DEEP, nights(*shallow, night(4, hour, hours = 1.5, deep = true)))).hasSize(1)
        quiet(RuleId.DOZE_NEVER_DEEP, nights(shallow[0], shallow[1], night(3, day, deep = true)))
        quiet(RuleId.DOZE_NEVER_DEEP, nights(shallow[0], shallow[1]))
        quiet(RuleId.DOZE_NEVER_DEEP, nights(shallow[0], night(2, 2 * day, deep = null), shallow[2]))
        // Exactly two hours is long enough.
        val twoHours = (1L..3L).map { night(it, (4 - it) * day, hours = 2.0, deep = false) }.toTypedArray()
        assertThat(run(RuleId.DOZE_NEVER_DEEP, nights(*twoHours))).hasSize(1)
    }

    @Test
    fun r18UpdateRegression() {
        val updated = now - 2 * day

        fun trend(
            after: Double,
            usual: Double = 20.0,
            baselineDays: Int = 5,
        ): RuleContext {
            val before = (baselineDays downTo 1).map { DailyMah(updated - it * day, usual) }
            return RuleContext(
                now,
                trends =
                    listOf(
                        AppTrend(
                            app,
                            updated,
                            before + DailyMah(updated, usual) + DailyMah(updated + day, after),
                        ),
                    ),
            )
        }

        val found = fires(RuleId.UPDATE_REGRESSION, trend(after = 55.0))
        assertThat(found.subject).isEqualTo(Subject.app(app))
        assertThat(found.evidence.measured).isEqualTo(55.0)
        assertThat(found.evidence.threshold).isEqualTo(40.0)
        assertThat(found.evidence.values).containsEntry("median", 20.0)
        quiet(RuleId.UPDATE_REGRESSION, trend(after = 25.0))
        quiet(RuleId.UPDATE_REGRESSION, trend(after = 40.0))
        quiet(RuleId.UPDATE_REGRESSION, trend(after = 500.0, baselineDays = 4))
        // An app that uses next to nothing: five times its median is still under the floor.
        quiet(RuleId.UPDATE_REGRESSION, trend(after = 10.0, usual = 2.0))
        assertThat(run(RuleId.UPDATE_REGRESSION, trend(after = 10.5, usual = 2.0))).hasSize(1)
        // No known update, and a jump more than three days after one.
        quiet(RuleId.UPDATE_REGRESSION, RuleContext(now, trends = listOf(AppTrend(app, null, listOf(DailyMah(now - day, 900.0))))))
        val late = (5 downTo 1).map { DailyMah(updated - it * day, 20.0) } + DailyMah(updated + 3 * day, 900.0)
        quiet(RuleId.UPDATE_REGRESSION, RuleContext(now, trends = listOf(AppTrend(app, updated, late))))
    }

    @Test
    fun r19UnattributedGap() {
        fun window(
            unattributed: Double,
            measured: Double = 100.0,
            ms: Long = 2 * hour,
        ) = RuleContext(now, dischargeWindows = listOf(DischargeWindow(31, lastDay, ms, measured, unattributed)))

        val found = fires(RuleId.UNATTRIBUTED_GAP, window(35.0))
        assertThat(found.evidence.measured).isEqualTo(35.0)
        assertThat(found.windowId).isEqualTo(31)
        quiet(RuleId.UNATTRIBUTED_GAP, window(5.0))
        quiet(RuleId.UNATTRIBUTED_GAP, window(20.0))
        quiet(RuleId.UNATTRIBUTED_GAP, window(90.0, ms = 2 * hour - 1))
        quiet(RuleId.UNATTRIBUTED_GAP, window(40.0, measured = 49.0))
    }

    @Test
    fun r20SelfCost() {
        fun self(
            ownMah: Double,
            span: Span = lastDay,
        ) = RuleContext(now, day = DayUse(span, 8 * hour, self = SelfCost(ownMah, 5000.0)))

        val found = fires(RuleId.SELF_COST, self(25.0))
        assertThat(found.evidence.measured).isEqualTo(0.5)
        assertThat(found.evidence.threshold).isEqualTo(0.3)
        quiet(RuleId.SELF_COST, self(6.0))
        quiet(RuleId.SELF_COST, self(15.0))
        // Twenty hours of charge are scaled to a day; a few hours are not judged at all.
        assertThat(run(RuleId.SELF_COST, self(13.0, Span(now - 20 * hour, now)))).hasSize(1)
        quiet(RuleId.SELF_COST, self(400.0, Span(now - 3 * hour, now)))
        quiet(RuleId.SELF_COST, RuleContext(now, day = DayUse(lastDay, 8 * hour, self = SelfCost(25.0, 0.0))))
    }

    @Test
    fun theRulesAreR01ToR20AndTheBundleHasEveryThresholdTheyRead() {
        assertThat(Rules.all.map { it.id }).isEqualTo(RuleId.entries)
        for (rule in Rules.all) {
            assertThat(rule.inputs).isNotEmpty()
            assertThat(thresholds.of(rule.id).names).isNotEmpty()
            assertThat(rule.evaluate(RuleContext(now), thresholds.of(rule.id))).isEmpty()
        }
    }
}

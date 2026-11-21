// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import io.github.akane599.coulomb.core.insights.ActionId.DENY_WAKELOCKS
import io.github.akane599.coulomb.core.insights.ActionId.OPEN_APP_DETAIL
import io.github.akane599.coulomb.core.insights.ActionId.OPEN_LOCATION_PERMISSION
import io.github.akane599.coulomb.core.insights.ActionId.RESTRICT_BACKGROUND
import io.github.akane599.coulomb.core.insights.ActionId.STANDBY_BUCKET

/** The rules about one app or one wakelock tag (SPEC 9.2). */
internal object AppRules {
    private val wakelockActions = listOf(RESTRICT_BACKGROUND, STANDBY_BUCKET, DENY_WAKELOCKS)

    /** R01: an app holds more than 2 h of partial wakelocks in 24 h. */
    val excessiveWakelock =
        rule(RuleId.EXCESSIVE_WAKELOCK, setOf(RuleInput.DAY_USE), wakelockActions) {
            val day = ctx.day ?: return@rule emptyList()
            val hours = limit("wakelockHours")
            day.apps
                .filter { it.partialWakelockMs / MS_PER_HOUR > hours }
                .map { found(Subject.app(it.uid), day.span.evidence(it.partialWakelockMs / MS_PER_HOUR, hours, EvidenceUnit.HOURS)) }
        }

    /** R02: one tag is held for more than 60 min within a session. */
    val longWakelockTag =
        rule(RuleId.LONG_WAKELOCK_TAG, setOf(RuleInput.SESSION_WAKELOCKS), wakelockActions) {
            val minutes = limit("tagMinutes")
            ctx.tagHolds
                .filter { it.heldMs / MS_PER_MINUTE > minutes }
                .map { hold ->
                    val evidence =
                        hold.span.evidence(hold.heldMs / MS_PER_MINUTE, minutes, EvidenceUnit.MINUTES, labels = mapOf("tag" to hold.tag))
                    found(Subject.wakelock(hold.tag, hold.uid), evidence, hold.windowId)
                }
        }

    /** R05: more than 12 wakeup alarms per screen-off hour from one app. */
    val alarmStorm =
        rule(RuleId.ALARM_STORM, setOf(RuleInput.DAY_USE), listOf(RESTRICT_BACKGROUND, STANDBY_BUCKET)) {
            val day = ctx.day ?: return@rule emptyList()
            val screenOffHours = day.screenOffMs / MS_PER_HOUR
            // A rate over a few minutes of screen-off time is noise.
            if (screenOffHours < limit("minScreenOffHours")) return@rule emptyList()
            val perHour = limit("alarmsPerHour")
            day.apps
                .filter { it.alarmWakeups / screenOffHours > perHour }
                .map { app ->
                    val values = mapOf("alarms" to app.alarmWakeups.toDouble(), "screenOffHours" to screenOffHours)
                    found(
                        Subject.app(app.uid),
                        day.span.evidence(app.alarmWakeups / screenOffHours, perHour, EvidenceUnit.PER_HOUR, values),
                    )
                }
        }

    /** R06: one app keeps GNSS active for more than 10 min while the screen is off. */
    val backgroundLocation =
        rule(RuleId.BACKGROUND_LOCATION, setOf(RuleInput.DAY_USE), listOf(OPEN_LOCATION_PERMISSION)) {
            screenOffMinutes("gnssMinutes") { it.gnssScreenOffMs }
        }

    /**
     * R09: one app holds a sensor for more than 30 min while the screen is off. The sources do not
     * say which sensors are wakeup sensors, so this counts every sensor but GNSS (ADR-0054).
     */
    val sensorHolder =
        rule(RuleId.SENSOR_HOLDER, setOf(RuleInput.DAY_USE), listOf(OPEN_APP_DETAIL)) {
            screenOffMinutes("sensorMinutes") { it.sensorScreenOffMs }
        }

    private fun RuleScope.screenOffMinutes(
        threshold: String,
        ms: (AppDayUse) -> Long,
    ): List<InsightCandidate> {
        val day = ctx.day ?: return emptyList()
        val minutes = limit(threshold)
        return day.apps
            .filter { ms(it) / MS_PER_MINUTE > minutes }
            .map { found(Subject.app(it.uid), day.span.evidence(ms(it) / MS_PER_MINUTE, minutes, EvidenceUnit.MINUTES)) }
    }

    /** R10: one app runs more than 50 jobs in a day, or has any abandoned job. */
    val jobChurn =
        rule(RuleId.JOB_CHURN, setOf(RuleInput.DAY_USE), listOf(STANDBY_BUCKET)) {
            val day = ctx.day ?: return@rule emptyList()
            val jobs = limit("jobsPerDay")
            val abandoned = limit("abandonedJobs")
            day.apps.mapNotNull { app ->
                val left = app.abandonedJobs?.toDouble() ?: 0.0
                val values = mapOf("jobs" to app.jobCount.toDouble(), "abandoned" to left)
                when {
                    app.jobCount > jobs -> day.span.evidence(app.jobCount.toDouble(), jobs, EvidenceUnit.COUNT, values)
                    left > abandoned -> day.span.evidence(left, abandoned, EvidenceUnit.COUNT, values, mapOf("reason" to "abandoned"))
                    else -> null
                }?.let { found(Subject.app(app.uid), it) }
            }
        }

    /** R16: an app sits in the Active standby bucket and was not opened for 7 days. */
    val unusedActiveApp =
        rule(RuleId.UNUSED_ACTIVE_APP, setOf(RuleInput.APP_STANDBY), listOf(STANDBY_BUCKET)) {
            val days = limit("unusedDays")
            ctx.standby.mapNotNull { app ->
                // No record of a launch is not a record of none.
                val opened = app.lastOpenedWallMs ?: return@mapNotNull null
                val unused = (ctx.nowWallMs - opened) / MS_PER_DAY
                if (!app.active || unused <= days) return@mapNotNull null
                found(Subject.app(app.uid), Span(opened, ctx.nowWallMs).evidence(unused, days, EvidenceUnit.DAYS))
            }
        }

    /**
     * R18: within 3 days of an update, a day's charge is more than twice the app's median over the
     * 14 days before it. Only whole days on either side count; the day of the update mixes both
     * versions. A floor keeps apps that use next to nothing out (ADR-0054).
     */
    val updateRegression =
        rule(RuleId.UPDATE_REGRESSION, setOf(RuleInput.APP_TRENDS), listOf(OPEN_APP_DETAIL)) {
            ctx.trends.mapNotNull { trend -> regression(trend)?.let { found(Subject.app(trend.uid), it) } }
        }

    private fun RuleScope.regression(trend: AppTrend): Evidence? {
        val updated = trend.updatedWallMs ?: return null
        val before =
            trend.daily
                .filter { it.dayStartWallMs + MS_PER_DAY <= updated && it.dayStartWallMs >= updated - limit("baselineDays") * MS_PER_DAY }
                .map { it.mah }
        val worst =
            trend.daily
                .filter { it.dayStartWallMs >= updated && it.dayStartWallMs < updated + limit("daysAfterUpdate") * MS_PER_DAY }
                .maxByOrNull { it.mah }
        if (worst == null || before.size < limit("minBaselineDays")) return null
        val usual = median(before)
        val threshold = maxOf(limit("medianMultiple") * usual, limit("minDailyMah"))
        if (worst.mah <= threshold) return null
        val span = Span(updated, worst.dayStartWallMs + MS_PER_DAY.toLong())
        return span.evidence(worst.mah, threshold, EvidenceUnit.MAH, mapOf("median" to usual))
    }
}

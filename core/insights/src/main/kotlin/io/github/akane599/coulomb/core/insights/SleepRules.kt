// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import io.github.akane599.coulomb.core.model.insight.EvidenceUnit
import io.github.akane599.coulomb.core.model.insight.RuleId

/**
 * The rules about screen-off sessions (SPEC 9.2). Each is about the device, not about one session:
 * it judges the latest session that qualifies, so one card follows the latest night rather than a
 * card piling up per night (ADR-0054). The session is in the evidence, for the card to open.
 */
internal object SleepRules {
    private fun RuleScope.sessionsOfAtLeast(hoursThreshold: String): List<ScreenOffSession> {
        val hours = limit(hoursThreshold)
        return ctx.screenOffSessions.filter { it.durationMs / MS_PER_HOUR >= hours }
    }

    /** R03: a screen-off session of at least 1 h with the phone awake for more than 15% of it. */
    val poorDeepSleep =
        rule(RuleId.POOR_DEEP_SLEEP, setOf(RuleInput.SCREEN_OFF_SESSIONS), listOf(ActionId.OPEN_SESSION)) {
            val share = limit("awakeShare")
            val session = sessionsOfAtLeast("minSessionHours").lastOrNull { it.awakeRatio != null }
            val awake = session?.awakeRatio
            if (awake == null || awake <= share) return@rule emptyList()
            val values = mapOf("hours" to session.durationMs / MS_PER_HOUR)
            val evidence = session.span.evidence(awake * PERCENT, share * PERCENT, EvidenceUnit.PERCENT, values, sessionId = session.id)
            listOf(found(null, evidence, session.windowId))
        }

    /**
     * R04: a screen-off rate above twice the personal median and above 0.8 %/h. The median is over
     * the sessions of the 14 days before; with too few of them there is no baseline and no finding.
     */
    val highScreenOffDrain =
        rule(RuleId.HIGH_SCREEN_OFF_DRAIN, setOf(RuleInput.SCREEN_OFF_SESSIONS), listOf(ActionId.OPEN_NIGHT_REPORT)) {
            val rated = sessionsOfAtLeast("minSessionHours").filter { it.ratePctPerHour != null }
            val session = rated.lastOrNull() ?: return@rule emptyList()
            val rate = session.ratePctPerHour ?: return@rule emptyList()
            // One or two level points over a night are within the gauge's rounding.
            if ((session.levelDropPct ?: 0) < limit("minDropPct")) return@rule emptyList()
            val since = session.span.fromWallMs - limit("baselineDays") * MS_PER_DAY
            val earlier = rated.dropLast(1).filter { it.span.toWallMs >= since }.mapNotNull { it.ratePctPerHour }
            if (earlier.size < limit("minBaselineSessions")) return@rule emptyList()
            val usual = median(earlier)
            val threshold = maxOf(limit("medianMultiple") * usual, limit("minRatePctPerHour"))
            if (rate <= threshold) return@rule emptyList()
            val values = mapOf("median" to usual, "sessions" to earlier.size.toDouble())
            val evidence = session.span.evidence(rate, threshold, EvidenceUnit.PERCENT_PER_HOUR, values, sessionId = session.id)
            listOf(found(null, evidence, session.windowId))
        }

    /**
     * R17: none of the last 3 screen-off sessions of at least 2 h reached deep idle. A session whose
     * Doze state is unknown proves nothing, so it stops the rule rather than counting either way.
     */
    val dozeNeverDeep =
        rule(RuleId.DOZE_NEVER_DEEP, setOf(RuleInput.SCREEN_OFF_SESSIONS), listOf(ActionId.OPEN_DOZE)) {
            val needed = limit("sessions").toInt()
            val last = sessionsOfAtLeast("minSessionHours").takeLast(needed)
            if (last.size < needed || last.any { it.deepIdleReached != false }) return@rule emptyList()
            val span = Span(last.first().span.fromWallMs, last.last().span.toWallMs)
            val evidence = span.evidence(last.size.toDouble(), needed.toDouble(), EvidenceUnit.COUNT, sessionId = last.last().id)
            listOf(found(null, evidence, last.last().windowId))
        }
}

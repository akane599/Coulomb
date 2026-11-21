// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.insight

import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.model.InsightState

/**
 * The SPEC 9.2 rules. [code] is what `Insight.ruleId` stores; [severity] is the table's rating,
 * which a rule uses unless its thresholds give a finding a higher one.
 */
public enum class RuleId(
    public val code: String,
    public val severity: InsightSeverity,
) {
    EXCESSIVE_WAKELOCK("R01", InsightSeverity.ACT),
    LONG_WAKELOCK_TAG("R02", InsightSeverity.WATCH),
    POOR_DEEP_SLEEP("R03", InsightSeverity.ACT),
    HIGH_SCREEN_OFF_DRAIN("R04", InsightSeverity.WATCH),
    ALARM_STORM("R05", InsightSeverity.ACT),
    BACKGROUND_LOCATION("R06", InsightSeverity.ACT),
    WEAK_SIGNAL_TAX("R07", InsightSeverity.INFO),
    SCAN_HEAVY("R08", InsightSeverity.INFO),
    SENSOR_HOLDER("R09", InsightSeverity.WATCH),
    JOB_CHURN("R10", InsightSeverity.WATCH),
    HOT_CHARGING("R11", InsightSeverity.ACT),
    HOT_BATTERY("R12", InsightSeverity.ACT),
    SLOW_CHARGING("R13", InsightSeverity.WATCH),
    CAPACITY_DROP("R14", InsightSeverity.INFO),
    LONG_AT_FULL("R15", InsightSeverity.WATCH),
    UNUSED_ACTIVE_APP("R16", InsightSeverity.INFO),
    DOZE_NEVER_DEEP("R17", InsightSeverity.WATCH),
    UPDATE_REGRESSION("R18", InsightSeverity.ACT),
    UNATTRIBUTED_GAP("R19", InsightSeverity.INFO),
    SELF_COST("R20", InsightSeverity.ACT),
    ;

    public companion object {
        private val byCode = entries.associateBy { it.code }

        /** The rule stored as [code], or null for one this version does not know. */
        public fun of(code: String): RuleId? = byCode[code]
    }
}

/** The unit of an [Evidence]'s measured value and threshold; the UI formats by it (SPEC 9.3). */
public enum class EvidenceUnit {
    HOURS,
    MINUTES,
    PERCENT,
    PERCENT_PER_HOUR,
    PER_HOUR,
    COUNT,
    CELSIUS,
    WATTS,
    POINTS,
    DAYS,
    MAH,
}

/**
 * Why an insight exists: what "Why am I seeing this?" shows (SPEC 9.4). [measured] and
 * [threshold] are the rule's main comparison, in [unit]. [values] holds its other numbers by name
 * (a second threshold, a baseline, a count) and [labels] its names (a tag, a charger). The span is
 * the time the measurement covers, and [sessionId] the session it was made in, which the insight's
 * card opens.
 *
 * Stored as `Insight.evidenceJson` (`EvidenceJson` in `:core:insights`).
 */
public data class Evidence(
    val measured: Double,
    val threshold: Double,
    val unit: EvidenceUnit,
    val fromWallMs: Long,
    val toWallMs: Long,
    val values: Map<String, Double> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
    val sessionId: Long? = null,
)

/** A stored insight as the screens read it (SPEC 9.4). */
public data class Insight(
    val id: Long,
    val rule: RuleId,
    val severity: InsightSeverity,
    val state: InsightState,
    val subjectKey: String?,
    /** The app the insight is about or points at. */
    val uid: Int?,
    val windowId: Long?,
    val evidence: Evidence,
    val createdWallMs: Long,
)

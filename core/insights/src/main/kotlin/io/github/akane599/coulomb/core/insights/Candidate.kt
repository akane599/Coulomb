// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import io.github.akane599.coulomb.core.model.InsightSeverity

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

/**
 * What an insight is about, so a rule keeps one open insight per subject (SPEC 9.1). [key] is
 * stored as `Insight.subjectKey`: `uid:10123`, `wakelock:<tag>`, `charger:3`, `session:42`. A rule
 * about the whole device has no subject.
 */
public data class Subject(
    val key: String,
    val uid: Int? = null,
) {
    public companion object {
        public fun app(uid: Int): Subject = Subject("uid:$uid", uid)

        public fun wakelock(
            tag: String,
            uid: Int?,
        ): Subject = Subject("wakelock:$tag", uid)

        public fun charger(id: Long): Subject = Subject("charger:$id")

        public fun session(id: Long): Subject = Subject("session:$id")
    }
}

/** A finding a rule made (SPEC 9.1), before the lifecycle decides whether it becomes an insight. */
public data class InsightCandidate(
    val rule: RuleId,
    val severity: InsightSeverity,
    val subject: Subject?,
    /** The stored window the finding came from, when it came from one. */
    val windowId: Long?,
    val evidence: Evidence,
)

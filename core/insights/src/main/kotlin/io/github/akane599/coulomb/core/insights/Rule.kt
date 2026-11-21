// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import io.github.akane599.coulomb.core.model.insight.Evidence
import io.github.akane599.coulomb.core.model.insight.EvidenceUnit
import io.github.akane599.coulomb.core.model.insight.RuleId

/** What an insight's card can offer (SPEC 9.2 "Action offered"): an Action Center action or a place to open. */
public enum class ActionId {
    RESTRICT_BACKGROUND,
    STANDBY_BUCKET,
    DENY_WAKELOCKS,
    OPEN_SESSION,
    OPEN_NIGHT_REPORT,
    OPEN_LOCATION_PERMISSION,
    OPEN_SCANNING_SETTINGS,
    OPEN_APP_DETAIL,
    OPEN_HEALTH,
    OPEN_BATTERY_PROTECTION,
    OPEN_DOZE,
    OPEN_SELF_DIAGNOSTIC,
    SWITCH_TO_LITE,
}

/**
 * SPEC 9.1's rule contract. A rule is a pure function of stored data and its thresholds. It returns
 * a list because a rule about apps can find several at once; a rule about the device finds at most
 * one. An empty [actions] is a rule that only explains or gives a tip (SPEC 1.1, principle 5).
 * Copy is keyed by [RuleId.code].
 */
public interface Rule {
    public val id: RuleId
    public val inputs: Set<RuleInput>
    public val actions: List<ActionId>

    public fun evaluate(
        ctx: RuleContext,
        thresholds: RuleThresholds,
    ): List<InsightCandidate>
}

/** What a rule's body sees: the data, its thresholds, and [found] to report a finding. */
internal class RuleScope(
    val ctx: RuleContext,
    private val rule: RuleId,
    private val thresholds: RuleThresholds,
) {
    fun limit(name: String): Double = thresholds[name]

    fun found(
        subject: Subject?,
        evidence: Evidence,
        windowId: Long? = null,
        relatedUid: Int? = null,
    ): InsightCandidate = InsightCandidate(rule, rule.severity, subject, windowId, evidence, relatedUid)
}

internal fun rule(
    id: RuleId,
    inputs: Set<RuleInput>,
    actions: List<ActionId> = emptyList(),
    body: RuleScope.() -> List<InsightCandidate>,
): Rule =
    object : Rule {
        override val id = id
        override val inputs = inputs
        override val actions = actions

        override fun evaluate(
            ctx: RuleContext,
            thresholds: RuleThresholds,
        ): List<InsightCandidate> = RuleScope(ctx, id, thresholds).body()
    }

internal const val MS_PER_MINUTE: Double = 60_000.0
internal const val MS_PER_HOUR: Double = 3_600_000.0
internal const val MS_PER_DAY: Double = 86_400_000.0
internal const val PERCENT: Double = 100.0

internal fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

internal fun Span.evidence(
    measured: Double,
    threshold: Double,
    unit: EvidenceUnit,
    values: Map<String, Double> = emptyMap(),
    labels: Map<String, String> = emptyMap(),
    sessionId: Long? = null,
): Evidence = Evidence(measured, threshold, unit, fromWallMs, toWallMs, values, labels, sessionId)

/** The engine's rules, R01 to R20 in order (SPEC 9.2). */
public object Rules {
    public val all: List<Rule> =
        listOf(
            AppRules.excessiveWakelock,
            AppRules.longWakelockTag,
            SleepRules.poorDeepSleep,
            SleepRules.highScreenOffDrain,
            AppRules.alarmStorm,
            AppRules.backgroundLocation,
            DeviceRules.weakSignalTax,
            DeviceRules.scanHeavy,
            AppRules.sensorHolder,
            AppRules.jobChurn,
            BatteryRules.hotCharging,
            BatteryRules.hotBattery,
            BatteryRules.slowCharging,
            BatteryRules.capacityDrop,
            BatteryRules.longAtFull,
            AppRules.unusedActiveApp,
            SleepRules.dozeNeverDeep,
            AppRules.updateRegression,
            DeviceRules.unattributedGap,
            DeviceRules.selfCost,
        )
}

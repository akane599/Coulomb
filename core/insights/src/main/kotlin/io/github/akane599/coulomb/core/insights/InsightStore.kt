// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import io.github.akane599.coulomb.core.database.dao.InsightDao
import io.github.akane599.coulomb.core.database.entity.InsightEntity
import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.model.InsightState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

/**
 * The stored insights: what the rules found, through the lifecycle (SPEC 9.1, 9.4), and what the
 * user did with each. Findings are applied one at a time on the caller's coroutine; the collector
 * calls [record] from its single consumer (SPEC 7.5).
 */
public class InsightStore(
    private val dao: InsightDao,
) {
    /**
     * Applies [candidates] as of [nowWallMs]: opens an insight, raises or refreshes the open one, or
     * leaves the user's dismissal or snooze alone. Returns the insights that are now `NEW` because of
     * it, which are the ones a notification may mention.
     */
    public suspend fun record(
        candidates: List<InsightCandidate>,
        nowWallMs: Long,
    ): List<Insight> =
        candidates.mapNotNull { candidate ->
            val latest = dao.latest(candidate.rule.code, candidate.subject?.key)
            val stored = latest?.let { StoredInsight(it.id, it.severity, it.state, it.stateChangedWall, it.snoozeUntilWall) }
            when (InsightLifecycle.decide(candidate.severity, stored, nowWallMs)) {
                Decision.Keep -> {
                    null
                }

                Decision.Open -> {
                    entity(candidate, nowWallMs).let { it.copy(id = dao.insert(it)) }
                }

                is Decision.Raise -> {
                    checkNotNull(
                        latest,
                    ).takes(candidate).copy(state = InsightState.NEW, stateChangedWall = nowWallMs).also {
                        dao.update(it)
                    }
                }

                is Decision.Refresh -> {
                    checkNotNull(latest).takes(candidate).also { dao.update(it) }.let { null }
                }
            }?.let(::read)
        }

    /** Insights not yet dismissed, snoozed or acted on: highest severity first, then newest. */
    public fun observeOpen(): Flow<List<Insight>> =
        dao.observeInState(listOf(InsightState.NEW, InsightState.SEEN)).map { rows ->
            rows.mapNotNull(::read).sortedWith(compareByDescending<Insight> { it.severity }.thenByDescending { it.createdWallMs })
        }

    /** The insight was shown on screen. */
    public suspend fun seen(
        insight: Insight,
        nowWallMs: Long,
    ) {
        if (insight.state == InsightState.NEW) dao.setState(insight.id, InsightState.SEEN, nowWallMs)
    }

    public suspend fun dismiss(
        id: Long,
        nowWallMs: Long,
    ): Unit = dao.setState(id, InsightState.DISMISSED, nowWallMs)

    /** The user ran the insight's action. */
    public suspend fun acted(
        id: Long,
        nowWallMs: Long,
    ): Unit = dao.setState(id, InsightState.ACTED, nowWallMs)

    public suspend fun snooze(
        id: Long,
        nowWallMs: Long,
        forMs: Long,
    ): Unit = dao.setState(id, InsightState.SNOOZED, nowWallMs, nowWallMs + forMs)

    private fun entity(
        candidate: InsightCandidate,
        nowWallMs: Long,
    ) = InsightEntity(
        ruleId = candidate.rule.code,
        windowId = candidate.windowId,
        severity = candidate.severity,
        subjectKey = candidate.subject?.key,
        subjectUid = candidate.subject?.uid ?: candidate.relatedUid,
        evidenceJson = candidate.evidence.toJson(),
        state = InsightState.NEW,
        createdWall = nowWallMs,
        stateChangedWall = nowWallMs,
        snoozeUntilWall = null,
    )

    private fun InsightEntity.takes(candidate: InsightCandidate): InsightEntity =
        copy(
            windowId = candidate.windowId,
            severity = maxOf(severity, candidate.severity),
            subjectUid = candidate.subject?.uid ?: candidate.relatedUid,
            evidenceJson = candidate.evidence.toJson(),
        )

    /** Null for a rule this version does not know or evidence it cannot read: such a row is not shown. */
    private fun read(row: InsightEntity): Insight? {
        val rule = RuleId.of(row.ruleId) ?: return null
        val evidence = Evidence.fromJson(row.evidenceJson) ?: return null
        return Insight(row.id, rule, row.severity, row.state, row.subjectKey, row.subjectUid, row.windowId, evidence, row.createdWall)
    }

    public companion object {
        public const val SNOOZE_DAY_MS: Long = 24 * 60 * 60 * 1000L
        public const val SNOOZE_WEEK_MS: Long = 7 * SNOOZE_DAY_MS
    }
}

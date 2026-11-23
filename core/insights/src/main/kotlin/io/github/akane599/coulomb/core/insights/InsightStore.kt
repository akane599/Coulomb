// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import io.github.akane599.coulomb.core.database.dao.DeviceDao
import io.github.akane599.coulomb.core.database.dao.InsightDao
import io.github.akane599.coulomb.core.database.dao.InsightEvidence
import io.github.akane599.coulomb.core.database.entity.InsightEntity
import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.model.InsightState
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.model.insight.RuleId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** An open insight with the name of the app it is about or points at; null when it has none or the name is not known. */
public data class ShownInsight(
    val insight: Insight,
    val appLabel: String?,
)

/**
 * The stored insights: what the rules found, through the lifecycle (SPEC 9.1, 9.4), and what the
 * user did with each. Findings are applied one at a time on the caller's coroutine; the collector
 * calls [record] from its single consumer (SPEC 7.5).
 */
public class InsightStore(
    private val dao: InsightDao,
    private val devices: DeviceDao,
) {
    /**
     * Applies [candidates] as of [nowWallMs]: opens an insight, raises or refreshes the open one, or
     * leaves the user's dismissal or snooze alone. Returns the insights that are now `NEW` because of
     * it, which are the ones a notification may mention.
     */
    public suspend fun record(
        candidates: List<InsightCandidate>,
        nowWallMs: Long,
    ): List<Insight> = candidates.mapNotNull { candidate -> apply(candidate, nowWallMs, again = true)?.let(::read) }

    /**
     * One candidate: read, decide, write. The user's taps come on another thread, so a write changes
     * only what its decision is about, and sets `NEW` only while the insight is still open: a
     * dismissal or snooze made since the read stays. A raise that found the insight closed is decided
     * once more on the row as it is now, where a rise opens a new insight (SPEC 9.1, 9.4; audit
     * CONC-004). The row that is now `NEW` because of this, or null.
     */
    private suspend fun apply(
        candidate: InsightCandidate,
        nowWallMs: Long,
        again: Boolean,
    ): InsightEntity? {
        val latest = latest(candidate)
        val stored = latest?.let { StoredInsight(it.id, it.severity, it.state, it.stateChangedWall, it.snoozeUntilWall) }
        return when (InsightLifecycle.decide(candidate.severity, stored, nowWallMs)) {
            Decision.Keep -> {
                null
            }

            Decision.Open -> {
                entity(candidate, nowWallMs).let { it.copy(id = dao.insert(it)) }
            }

            is Decision.Raise -> {
                val raised = checkNotNull(latest).takes(candidate).copy(state = InsightState.NEW, stateChangedWall = nowWallMs)
                when {
                    raise(raised, nowWallMs) -> raised
                    again -> apply(candidate, nowWallMs, again = false)
                    else -> null
                }
            }

            is Decision.Refresh -> {
                dao.refresh(checkNotNull(latest).takes(candidate).evidence())
                null
            }
        }
    }

    /**
     * Whether [raised] was stored: false when the user closed the insight since it was read. The
     * state goes first, in one guarded statement; the evidence follows only where that took.
     */
    private suspend fun raise(
        raised: InsightEntity,
        nowWallMs: Long,
    ): Boolean {
        if (dao.raise(raised.id, InsightState.NEW, nowWallMs, OPEN) == 0) return false
        dao.refresh(raised.evidence())
        return true
    }

    private fun InsightEntity.evidence() = InsightEvidence(id, subjectKey, windowId, severity, subjectUid, evidenceJson)

    /** Insights not yet dismissed, snoozed or acted on: highest severity first, then newest. */
    public fun observeOpen(): Flow<List<Insight>> =
        dao.observeInState(OPEN).map { rows ->
            rows.mapNotNull(::read).sortedWith(compareByDescending<Insight> { it.severity }.thenByDescending { it.createdWallMs })
        }

    /** The first [limit] of [observeOpen], each with its app's name: what a screen shows. */
    public fun observeShown(limit: Int): Flow<List<ShownInsight>> =
        combine(observeOpen(), devices.observeUidMeta()) { open, meta ->
            val labels = meta.associate { it.uid to it.label }
            open.take(limit).map { insight -> ShownInsight(insight, insight.uid?.let(labels::get)?.takeIf { it.isNotBlank() }) }
        }

    /** The insight was shown on screen. */
    public suspend fun seen(
        insight: Insight,
        nowWallMs: Long,
    ) {
        // The check saves a write per shown card; the guard in the query keeps a snooze or a dismissal
        // made since the list was read (audit CONC-004).
        if (insight.state == InsightState.NEW) dao.advance(insight.id, InsightState.NEW, InsightState.SEEN, nowWallMs)
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
        evidenceJson = EvidenceJson.encode(candidate.evidence),
        state = InsightState.NEW,
        createdWall = nowWallMs,
        stateChangedWall = nowWallMs,
        snoozeUntilWall = null,
    )

    /**
     * The subject's latest row. A row an earlier build keyed by the tag alone is this subject's
     * when it names the same app; it takes the new key at its next update (audit BUG-057).
     */
    private suspend fun latest(candidate: InsightCandidate): InsightEntity? {
        val subject = candidate.subject
        val latest = dao.latest(candidate.rule.code, subject?.key)
        if (latest != null || subject?.legacyKey == null) return latest
        return dao.latest(candidate.rule.code, subject.legacyKey)?.takeIf { it.subjectUid == subject.uid }
    }

    private fun InsightEntity.takes(candidate: InsightCandidate): InsightEntity =
        copy(
            subjectKey = candidate.subject?.key ?: subjectKey,
            windowId = candidate.windowId,
            severity = maxOf(severity, candidate.severity),
            subjectUid = candidate.subject?.uid ?: candidate.relatedUid,
            evidenceJson = EvidenceJson.encode(candidate.evidence),
        )

    /** Null for a rule this version does not know or evidence it cannot read: such a row is not shown. */
    private fun read(row: InsightEntity): Insight? {
        val rule = RuleId.of(row.ruleId) ?: return null
        val evidence = EvidenceJson.decode(row.evidenceJson) ?: return null
        return Insight(row.id, rule, row.severity, row.state, row.subjectKey, row.subjectUid, row.windowId, evidence, row.createdWall)
    }

    public companion object {
        public const val SNOOZE_DAY_MS: Long = 24 * 60 * 60 * 1000L
        public const val SNOOZE_WEEK_MS: Long = 7 * SNOOZE_DAY_MS

        /** The states in which an insight is shown, and may be raised in place. */
        private val OPEN = listOf(InsightState.NEW, InsightState.SEEN)
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.entity.InsightEntity
import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.model.InsightState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SPEC 9.1 and 9.4 against the real table: one open insight per rule and subject, and the user's word kept. */
@RunWith(AndroidJUnit4::class)
class InsightStoreTest {
    private lateinit var db: CoulombDatabase
    private lateinit var store: InsightStore
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val app = Subject.app(10_123)

    @Before
    fun open() {
        db = CoulombDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        store = InsightStore(db.insightDao())
    }

    @After
    fun close() = db.close()

    private fun found(
        rule: RuleId,
        subject: Subject?,
        measured: Double,
        severity: InsightSeverity = rule.severity,
        relatedUid: Int? = null,
    ) = InsightCandidate(rule, severity, subject, null, Evidence(measured, 2.0, EvidenceUnit.HOURS, now - hour, now), relatedUid)

    private suspend fun shown() = store.observeOpen().first()

    @Test
    fun aFindingOpensOneInsightAndALaterOneOnlyRefreshesIt() =
        runTest {
            val opened = store.record(listOf(found(RuleId.EXCESSIVE_WAKELOCK, app, 3.0)), now)
            assertThat(opened.single().state).isEqualTo(InsightState.NEW)
            assertThat(opened.single().uid).isEqualTo(10_123)
            store.seen(shown().single(), now + 1)
            // Found again: nothing new to announce, but the card shows the later figure and stays seen.
            assertThat(store.record(listOf(found(RuleId.EXCESSIVE_WAKELOCK, app, 5.0)), now + hour)).isEmpty()
            val card = shown().single()
            assertThat(card.id).isEqualTo(opened.single().id)
            assertThat(card.evidence.measured).isEqualTo(5.0)
            assertThat(card.state).isEqualTo(InsightState.SEEN)
            assertThat(card.createdWallMs).isEqualTo(now)
            // Another app and another rule are other insights.
            store.record(listOf(found(RuleId.EXCESSIVE_WAKELOCK, Subject.app(10_200), 4.0), found(RuleId.JOB_CHURN, app, 80.0)), now + hour)
            assertThat(shown()).hasSize(3)
        }

    @Test
    fun aDismissedFindingStaysAwayForADayUnlessItRises() =
        runTest {
            val first = store.record(listOf(found(RuleId.LONG_WAKELOCK_TAG, Subject.wakelock("*sync*", 10_123), 70.0)), now).single()
            store.dismiss(first.id, now + hour)
            assertThat(shown()).isEmpty()
            assertThat(
                store.record(listOf(found(RuleId.LONG_WAKELOCK_TAG, Subject.wakelock("*sync*", 10_123), 90.0)), now + 2 * hour),
            ).isEmpty()
            // A higher severity comes back at once, as a new insight.
            val risen =
                store.record(
                    listOf(found(RuleId.LONG_WAKELOCK_TAG, Subject.wakelock("*sync*", 10_123), 200.0, InsightSeverity.ACT)),
                    now + 3 * hour,
                )
            assertThat(risen.single().id).isNotEqualTo(first.id)
            store.acted(risen.single().id, now + 4 * hour)
            assertThat(
                store.record(
                    listOf(found(RuleId.LONG_WAKELOCK_TAG, Subject.wakelock("*sync*", 10_123), 200.0, InsightSeverity.ACT)),
                    now + 27 * hour,
                ),
            ).isEmpty()
            assertThat(
                store.record(
                    listOf(found(RuleId.LONG_WAKELOCK_TAG, Subject.wakelock("*sync*", 10_123), 200.0, InsightSeverity.ACT)),
                    now + 28 * hour,
                ),
            ).hasSize(1)
        }

    @Test
    fun aSnoozeHoldsAndARiseOnAnOpenInsightAnnouncesItAgain() =
        runTest {
            val first =
                store
                    .record(
                        listOf(found(RuleId.HOT_BATTERY, null, 46.0, InsightSeverity.WATCH, relatedUid = 10_300)),
                        now,
                    ).single()
            assertThat(first.subjectKey).isNull()
            assertThat(first.uid).isEqualTo(10_300)
            store.seen(first, now)
            val raised = store.record(listOf(found(RuleId.HOT_BATTERY, null, 48.0, InsightSeverity.ACT)), now + hour).single()
            assertThat(raised.id).isEqualTo(first.id)
            assertThat(raised.severity).isEqualTo(InsightSeverity.ACT)
            assertThat(raised.state).isEqualTo(InsightState.NEW)
            store.snooze(first.id, now + hour, InsightStore.SNOOZE_DAY_MS)
            assertThat(shown()).isEmpty()
            assertThat(store.record(listOf(found(RuleId.HOT_BATTERY, null, 48.0, InsightSeverity.ACT)), now + 24 * hour)).isEmpty()
            assertThat(store.record(listOf(found(RuleId.HOT_BATTERY, null, 48.0, InsightSeverity.ACT)), now + 25 * hour)).hasSize(1)
        }

    @Test
    fun openInsightsComeMostSevereFirstAndUnreadableRowsAreLeftOut() =
        runTest {
            store.record(listOf(found(RuleId.SCAN_HEAVY, null, 30.0)), now)
            store.record(listOf(found(RuleId.JOB_CHURN, app, 80.0)), now + 1)
            store.record(listOf(found(RuleId.EXCESSIVE_WAKELOCK, app, 3.0)), now + 2)
            store.record(listOf(found(RuleId.ALARM_STORM, app, 20.0)), now + 3)
            val row = InsightEntity(0, "R99", null, InsightSeverity.ACT, null, null, "{}", InsightState.NEW, now, now, null)
            db.insightDao().insert(row)
            db.insightDao().insert(row.copy(ruleId = "R01", subjectKey = "uid:1", evidenceJson = "not json"))
            assertThat(
                shown().map {
                    it.rule
                },
            ).containsExactly(RuleId.ALARM_STORM, RuleId.EXCESSIVE_WAKELOCK, RuleId.JOB_CHURN, RuleId.SCAN_HEAVY).inOrder()
        }
}

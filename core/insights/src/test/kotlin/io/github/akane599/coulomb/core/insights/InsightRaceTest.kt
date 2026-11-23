// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.dao.InsightDao
import io.github.akane599.coulomb.core.database.entity.InsightEntity
import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.model.InsightState
import io.github.akane599.coulomb.core.model.insight.Evidence
import io.github.akane599.coulomb.core.model.insight.EvidenceUnit
import io.github.akane599.coulomb.core.model.insight.RuleId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Audit CONC-004: the rules run on the collector's consumer while the user dismisses or snoozes on
 * the main thread. A write decided on a row read before the user's tap must not put the old state
 * back. The DAO here stops a rule run between its read and its write.
 */
@RunWith(AndroidJUnit4::class)
class InsightRaceTest {
    private lateinit var db: CoulombDatabase
    private lateinit var store: InsightStore
    private val now = 1_800_000_000_000L
    private val app = Subject.app(10_123)
    private val rule = RuleId.EXCESSIVE_WAKELOCK

    private var held = false
    private val read = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()

    /** Stops after the row was read, while [held], until [release]. */
    private inner class Gated(
        private val dao: InsightDao,
    ) : InsightDao by dao {
        override suspend fun latest(
            ruleId: String,
            subjectKey: String?,
        ): InsightEntity? {
            val row = dao.latest(ruleId, subjectKey)
            if (held) {
                read.complete(Unit)
                release.await()
            }
            return row
        }
    }

    @Before
    fun open() {
        db = CoulombDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        store = InsightStore(Gated(db.insightDao()), db.deviceDao())
    }

    @After
    fun close() = db.close()

    private fun found(
        measured: Double,
        severity: InsightSeverity = rule.severity,
    ) = InsightCandidate(rule, severity, app, null, Evidence(measured, 2.0, EvidenceUnit.HOURS, now - 1, now), null)

    private suspend fun rows(): List<InsightEntity> =
        db
            .insightDao()
            .observeInState(InsightState.entries)
            .first()
            .sortedBy { it.id }

    /** Runs [record] up to its read, then [meanwhile], then lets it write. */
    private suspend fun racing(
        candidate: InsightCandidate,
        meanwhile: suspend () -> Unit,
    ) = runBlocking {
        held = true
        val run = async { store.record(listOf(candidate), now + 10) }
        read.await()
        meanwhile()
        release.complete(Unit)
        run.await()
    }

    @Test
    fun aRefreshRacingADismissalKeepsTheDismissal() =
        runBlocking {
            val id = store.record(listOf(found(3.0)), now).single().id

            val announced = racing(found(4.0)) { store.dismiss(id, now + 5) }

            assertThat(announced).isEmpty()
            assertThat(rows().single().state).isEqualTo(InsightState.DISMISSED)
        }

    /** SPEC 9.1: a rise after a snooze opens a new insight; the snoozed one keeps its snooze. */
    @Test
    fun aRaiseRacingASnoozeOpensANewInsightAndKeepsTheSnooze() =
        runBlocking {
            val id = store.record(listOf(found(3.0, InsightSeverity.WATCH)), now).single().id

            val announced = racing(found(9.0, InsightSeverity.ACT)) { store.snooze(id, now + 5, InsightStore.SNOOZE_DAY_MS) }

            val (snoozed, raised) = rows()
            assertThat(snoozed.id).isEqualTo(id)
            assertThat(snoozed.state).isEqualTo(InsightState.SNOOZED)
            assertThat(snoozed.snoozeUntilWall).isEqualTo(now + 5 + InsightStore.SNOOZE_DAY_MS)
            assertThat(raised.state).isEqualTo(InsightState.NEW)
            assertThat(raised.severity).isEqualTo(InsightSeverity.ACT)
            assertThat(announced.single().id).isEqualTo(raised.id)
        }

    /** A card read while new and shown after its snooze must not undo the snooze. */
    @Test
    fun aStaleSeenLeavesASnooze() =
        runBlocking {
            store.record(listOf(found(3.0)), now)
            val stale = store.observeOpen().first().single()
            store.snooze(stale.id, now + 5, InsightStore.SNOOZE_DAY_MS)

            store.seen(stale, now + 6)

            val row = rows().single()
            assertThat(row.state).isEqualTo(InsightState.SNOOZED)
            assertThat(row.snoozeUntilWall).isEqualTo(now + 5 + InsightStore.SNOOZE_DAY_MS)
        }

    /** Without a race the rules still raise an open insight in place and announce it. */
    @Test
    fun aRaiseOnAnOpenInsightStillAnnouncesItInPlace() =
        runBlocking {
            val id = store.record(listOf(found(3.0, InsightSeverity.WATCH)), now).single().id
            store.seen(store.observeOpen().first().single(), now + 1)

            val announced = store.record(listOf(found(9.0, InsightSeverity.ACT)), now + 10)

            assertThat(announced.single().id).isEqualTo(id)
            assertThat(rows().single().state).isEqualTo(InsightState.NEW)
            assertThat(rows().single().severity).isEqualTo(InsightSeverity.ACT)
        }
}

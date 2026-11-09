// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.dao.WindowStat
import io.github.akane599.coulomb.core.database.dao.WindowWake
import io.github.akane599.coulomb.core.database.entity.CapacityEstimateEntity
import io.github.akane599.coulomb.core.database.entity.ChargeDetailEntity
import io.github.akane599.coulomb.core.database.entity.ChargerProfileEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.WindowUid
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WindowSessionTest : DatabaseTest() {
    private val windows get() = db.windowDao()
    private val sessions get() = db.sessionDao()

    private fun detail() =
        WindowRows(
            uids = listOf(WindowUid(0, 10_123, "cpu", UidPowerEntity.ANY_STATE, WindowUidEntity.WHOLE_UID, 4.0, Provenance.DERIVED)),
            wakes = listOf(WindowWake(null, "qcom_rx_wakelock", WakeKind.KERNEL, 1_200, 3, null)),
            stats = listOf(WindowStat(UidPowerEntity.DEVICE_UID, "wakeups", 14, Provenance.MEASURED)),
        )

    @Test
    fun aWindowStoresItsRecords() =
        runTest {
            val from = db.snapshotDao().insert(snapshot(elapsedS = 10))
            val to = db.snapshotDao().insert(snapshot(elapsedS = 20))
            val id = windows.insert(window(from, to), detail())
            val rows = windows.rows(id)
            assertThat(rows.uids.single().windowId).isEqualTo(id)
            assertThat(rows.uids.single().mAh).isEqualTo(4.0)
            assertThat(rows.uids.single().provenance).isEqualTo(Provenance.DERIVED)
            assertThat(rows.wakes).isEqualTo(detail().wakes)
            assertThat(rows.stats).isEqualTo(detail().stats)
        }

    @Test
    fun keysAndTagsAreStoredOnceAndUnusedTagsGo() =
        runTest {
            val from = db.snapshotDao().insert(snapshot(elapsedS = 10))
            val to = db.snapshotDao().insert(snapshot(elapsedS = 20))
            val first = windows.insert(window(from, to), detail())
            val other = WindowWake(10_123, "*job*/sync", WakeKind.PARTIAL, 900, 1, 0)
            val second = windows.insert(window(from, to), detail().copy(wakes = detail().wakes + other))
            // One counter key, and the uid row's component and process state (ADR-0035).
            assertThat(long("SELECT COUNT(*) FROM stat_key")).isEqualTo(3)
            assertThat(long("SELECT COUNT(*) FROM wake_tag")).isEqualTo(2)
            assertThat(windows.rows(second).wakes.map { it.tag }).containsExactly("qcom_rx_wakelock", "*job*/sync").inOrder()

            windows.deleteWindows(listOf(second))
            assertThat(windows.window(second)).isNull()
            assertThat(long("SELECT COUNT(*) FROM window_wake")).isEqualTo(1)
            assertThat(long("SELECT COUNT(*) FROM wake_tag")).isEqualTo(1)
            windows.pruneDetail(listOf(first))
            assertThat(long("SELECT COUNT(*) FROM wake_tag")).isEqualTo(0)
            // Keys are never pruned: the set is small and only grows.
            assertThat(long("SELECT COUNT(*) FROM stat_key")).isEqualTo(3)
        }

    @Test
    fun windowsOrderAndSelectByTheirEndAcrossBoots() =
        runTest {
            val s = db.snapshotDao()
            val a = s.insert(snapshot(elapsedS = 500, bootSeq = 1))
            val b = s.insert(snapshot(elapsedS = 900, bootSeq = 1))
            val c = s.insert(snapshot(elapsedS = 10, bootSeq = 2))
            val d = s.insert(snapshot(elapsedS = 20, bootSeq = 2))
            val cd = windows.insert(window(c, d))
            val ab = windows.insert(window(a, b))
            val bc = windows.insert(window(b, c))
            windows.insert(window(a, d, kind = WindowKind.DAY))

            val all = windows.windowsEndingIn(WindowKind.CONSECUTIVE, 0, 0, Int.MAX_VALUE, Long.MAX_VALUE)
            assertThat(all.map { it.id }).containsExactly(ab, bc, cd).inOrder()
            // From the end of `ab` (exclusive) to the end of `bc` (inclusive).
            assertThat(windows.windowsEndingIn(WindowKind.CONSECUTIVE, 1, 900 * NS_PER_S, 2, 10 * NS_PER_S).map { it.id })
                .containsExactly(bc)
            windows.observeRecent(WindowKind.CONSECUTIVE, limit = 2).test {
                assertThat(awaitItem().map { it.id }).containsExactly(cd, bc).inOrder()
            }
        }

    @Test
    fun detailRetentionFollowsTheWindowAndSessionKind() =
        runTest {
            val s = db.snapshotDao()
            val a = s.insert(snapshot(elapsedS = 10))
            val b = s.insert(snapshot(elapsedS = 20))
            val c = s.insert(snapshot(elapsedS = 30))
            val screenOff = sessions.insert(session(SessionKind.SCREEN_OFF, startSnapshotId = a))
            val discharge = sessions.insert(session(SessionKind.DISCHARGE, startSnapshotId = a))
            val consecutive = windows.insert(window(a, b), detail())
            val day = windows.insert(window(a, b, kind = WindowKind.DAY), detail())
            val screenOffWindow = windows.insert(window(a, b, WindowKind.SESSION, screenOff), detail())
            val dischargeWindow = windows.insert(window(a, b, WindowKind.SESSION, discharge), detail())
            val recent = windows.insert(window(b, c), detail())

            val cut = 25 * NS_PER_S
            val prunable =
                windows.detailBefore(listOf(WindowKind.CONSECUTIVE, WindowKind.REFERENCE), 1, cut) +
                    windows.sessionDetailBefore(listOf(SessionKind.SCREEN_ON, SessionKind.SCREEN_OFF), 1, cut)
            assertThat(prunable).containsExactly(consecutive, screenOffWindow)
            windows.pruneDetail(prunable)

            assertThat(windows.rows(consecutive)).isEqualTo(WindowRows())
            assertThat(windows.window(consecutive)?.detailPruned).isTrue()
            for (kept in listOf(day, dischargeWindow, recent)) assertThat(windows.rows(kept).uids).hasSize(1)
            assertThat(windows.detailBefore(listOf(WindowKind.CONSECUTIVE), 1, cut)).isEmpty()
        }

    @Test
    fun sessionDetailsFollowTheirParents() =
        runTest {
            val id = sessions.insert(session(SessionKind.CHARGE))
            assertThat(sessions.open().map { it.id }).containsExactly(id)
            sessions.update(session(SessionKind.CHARGE).copy(id = id, closeReason = SessionCloseReason.REBOOT))
            assertThat(sessions.open()).isEmpty()

            val charger =
                sessions.insertChargerProfile(
                    ChargerProfileEntity(name = "Desk", plugType = 1, baselineW = 25.0, firstSeenWall = 1, lastSeenWall = 2, notes = null),
                )
            sessions.upsertChargeDetail(chargeDetail(id, charger))
            sessions.deleteChargerProfile(charger)
            assertThat(sessions.chargeDetail(id)?.chargerProfileId).isNull()

            val from = db.snapshotDao().insert(snapshot(elapsedS = 10))
            val to = db.snapshotDao().insert(snapshot(elapsedS = 20))
            val windowId = windows.insert(window(from, to, WindowKind.SESSION, id))
            sql("DELETE FROM session WHERE id = $id")
            assertThat(sessions.chargeDetail(id)).isNull()
            assertThat(windows.window(windowId)?.sessionId).isNull()
        }

    @Test
    fun capacityEstimatesKeepTheirSessionAndComeNewestFirst() =
        runTest {
            val id = sessions.insert(session(SessionKind.CHARGE))
            // Wall times out of order: the estimates' order is the order they were made.
            for (wall in listOf(30L, 10L, 20L)) {
                sessions.insertCapacityEstimate(
                    CapacityEstimateEntity(
                        sessionId = id,
                        capacityMah = 4_700.0 + wall,
                        weight = 0.6,
                        deltaSocPct = 40.0,
                        deltaChargeMah = 1_900.0,
                        tempMeanDeciC = 300,
                        createdWall = wall,
                    ),
                )
            }
            assertThat(sessions.latestCapacityEstimates(limit = 2).map { it.createdWall }).containsExactly(20L, 10L).inOrder()
            assertThat(failure { sql("DELETE FROM session WHERE id = $id") }).isInstanceOf(SQLiteConstraintException::class.java)
        }

    @Test
    fun recentSessionsOrderByBootThenElapsedTime() =
        runTest {
            sessions.insert(session(bootSeq = 1, startElapsedNs = 9_000))
            sessions.insert(session(bootSeq = 2, startElapsedNs = 1_000))
            sessions.insert(session(kind = SessionKind.CHARGE, bootSeq = 3, startElapsedNs = 1))
            sessions.observeRecent(SessionKind.DISCHARGE, limit = 10).test {
                assertThat(awaitItem().map { it.bootSeq }).containsExactly(2, 1).inOrder()
            }
            assertThat(string("SELECT kind FROM session WHERE bootSeq = 3")).isEqualTo("CHARGE")
        }

    private fun chargeDetail(
        sessionId: Long,
        chargerProfileId: Long,
    ) = ChargeDetailEntity(
        sessionId = sessionId,
        plugType = 1,
        chargerProfileId = chargerProfileId,
        peakW = null,
        avgW = 18.0,
        ccToCvLevel = 72,
        minutesAbove80 = 0,
        minutesAbove38C = 4,
        targetLevel = 80,
        protectionMode = null,
    )
}

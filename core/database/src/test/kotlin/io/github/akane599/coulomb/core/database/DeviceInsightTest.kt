// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.ActionLogEntity
import io.github.akane599.coulomb.core.database.entity.InsightEntity
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.ActionOrigin
import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.model.InsightState
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceInsightTest : DatabaseTest() {
    private val device get() = db.deviceDao()
    private val insights get() = db.insightDao()

    @Test
    fun aBootIsNumberedOnceAndRecognisedAfterARestart() =
        runTest {
            val first = device.enterBoot(rawBootCount = 41, fingerprint = "fp", wallMs = 1_000, elapsedNs = 5 * NS_PER_S)
            assertThat(first.bootSeq).isEqualTo(41)
            // The app restarts in the same boot: same count, elapsed time went on.
            assertThat(device.enterBoot(41, "fp", 9_000, 60 * NS_PER_S)).isEqualTo(first)
            // Elapsed time went back with the same count: a boot the count missed.
            assertThat(device.enterBoot(41, "fp", 10_000, 1 * NS_PER_S).bootSeq).isEqualTo(42)
            assertThat(device.enterBoot(43, "fp", 20_000, 1 * NS_PER_S).bootSeq).isEqualTo(43)
        }

    @Test
    fun aBootAfterAnImportSortsAfterTheImportedOnes() =
        runTest {
            device.enterBoot(rawBootCount = 3, fingerprint = "new", wallMs = 1_000, elapsedNs = NS_PER_S)
            // An imported phone was at boot 812 (SPEC 14.5).
            sql(
                "INSERT INTO boot (bootSeq, rawBootCount, fingerprint, firstWallMs, firstElapsedNs, historyOffsetMs, imported) " +
                    "VALUES (812, 812, 'old', 0, 0, NULL, 1)",
            )
            assertThat(device.enterBoot(4, "new", 2_000, NS_PER_S).bootSeq).isEqualTo(813)
            assertThat(device.enterBoot(4, "new", 3_000, 2 * NS_PER_S).bootSeq).isEqualTo(813)
            assertThat(device.enterBoot(5, "new", 4_000, NS_PER_S).bootSeq).isEqualTo(814)
        }

    @Test
    fun anUnreadableBootCountAlwaysStartsANewBoot() =
        runTest {
            assertThat(device.enterBoot(null, "fp", 1_000, NS_PER_S).bootSeq).isEqualTo(1)
            assertThat(device.enterBoot(null, "fp", 2_000, 2 * NS_PER_S).bootSeq).isEqualTo(2)
            device.setHistoryOffset(2, offsetMs = 77)
            assertThat(device.boot(2)?.historyOffsetMs).isEqualTo(77)
        }

    @Test
    fun aUidRemembersEveryPackageItHad() =
        runTest {
            device.recordUidPackages(10_123, listOf("com.example.old"), wall = 100)
            device.recordUidPackages(10_123, listOf("com.example.old"), wall = 300)
            device.recordUidPackages(10_123, listOf("com.example.new"), wall = 900)
            device.recordUidPackages(10_123, listOf("com.example.old"), wall = 50)
            assertThat(device.uidPackages(10_123).map { Triple(it.packageName, it.firstSeenWall, it.lastSeenWall) })
                .containsExactly(Triple("com.example.new", 900L, 900L), Triple("com.example.old", 50L, 300L))
                .inOrder()
        }

    @Test
    fun theLatestInsightIsTheLastWrittenAndOutlivesItsWindow() =
        runTest {
            val from = db.snapshotDao().insert(snapshot(elapsedS = 10))
            val to = db.snapshotDao().insert(snapshot(elapsedS = 20))
            val windowId = db.windowDao().insert(window(from, to))
            insights.insert(insight(windowId, subjectKey = null, createdWall = 50))
            // Written later with a clock that went back: still the latest.
            val later = insights.insert(insight(windowId, subjectKey = null, createdWall = 10))
            insights.insert(insight(windowId, subjectKey = "uid:10123", createdWall = 20))
            assertThat(insights.latest("R07", null)?.id).isEqualTo(later)
            assertThat(insights.latest("R07", "uid:10123")?.createdWall).isEqualTo(20)
            assertThat(insights.latest("R08", null)).isNull()

            insights.setState(later, InsightState.DISMISSED, wall = 70)
            val dismissed = checkNotNull(insights.latest("R07", null))
            assertThat(dismissed.state).isEqualTo(InsightState.DISMISSED)
            assertThat(dismissed.stateChangedWall).isEqualTo(70)

            sql("DELETE FROM window_result WHERE id = $windowId")
            insights.observeInState(listOf(InsightState.NEW, InsightState.DISMISSED)).test {
                assertThat(awaitItem().map { it.windowId }).containsExactly(null, null, null)
            }
        }

    @Test
    fun actionsRevertInTheOrderTheyRan() =
        runTest {
            val first = insights.insertAction(action(executedWall = 500))
            val second = insights.insertAction(action(executedWall = 100))
            val third = insights.insertAction(action(executedWall = 300))
            insights.updateAction(action(executedWall = 300).copy(id = third, revertedWall = 400))
            assertThat(insights.unreverted().map { it.id }).containsExactly(second, first).inOrder()
            insights.observeActions().test {
                assertThat(awaitItem().map { it.id }).containsExactly(third, second, first).inOrder()
            }
        }

    private fun insight(
        windowId: Long,
        subjectKey: String?,
        createdWall: Long,
    ) = InsightEntity(
        ruleId = "R07",
        windowId = windowId,
        severity = InsightSeverity.WATCH,
        subjectKey = subjectKey,
        subjectUid = null,
        evidenceJson = "{}",
        state = InsightState.NEW,
        createdWall = createdWall,
        stateChangedWall = createdWall,
        snoozeUntilWall = null,
    )

    private fun action(executedWall: Long) =
        ActionLogEntity(
            actionType = "restrict_background",
            target = "com.example",
            beforeValue = "allow",
            afterValue = "ignore",
            tier = AccessTier.T2_PRIVILEGED_WORKER,
            executedWall = executedWall,
            revertedWall = null,
            origin = ActionOrigin.USER,
            insightId = null,
            externalDriftWall = null,
            externalDriftValue = null,
        )
}

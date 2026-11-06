// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.HistoryGapEntity
import io.github.akane599.coulomb.core.model.HistoryGapReason
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDaoTest : DatabaseTest() {
    private val dao get() = db.historyDao()

    private fun gap(
        startBoot: Int,
        startElapsedNs: Long,
        endBoot: Int,
        endElapsedNs: Long,
        reason: HistoryGapReason = HistoryGapReason.CURSOR_SKIPPED,
    ) = HistoryGapEntity(
        startMonotonicMs = startBoot * 1_000_000L + startElapsedNs / 1_000_000,
        endMonotonicMs = endBoot * 1_000_000L + endElapsedNs / 1_000_000,
        startBootSeq = startBoot,
        startElapsedNs = startElapsedNs,
        endBootSeq = endBoot,
        endElapsedNs = endElapsedNs,
        reason = reason,
    )

    @Test
    fun tagsAreStoredOnceAndReadBackWithTheirEvents() =
        runTest {
            val lock = "*job*/com.example/.SyncService"
            dao.insertRead(
                listOf(
                    event(1, 100, tag = lock, type = HistoryKind.WAKE_LOCK, change = HistoryChange.START, baseline = true),
                    event(1, 200, tag = lock, type = HistoryKind.WAKE_LOCK, change = HistoryChange.STOP),
                    event(1, 300),
                ),
                emptyList(),
            )
            dao.insertRead(listOf(event(2, 50, tag = lock, type = HistoryKind.WAKE_LOCK, change = HistoryChange.START)), emptyList())
            assertThat(long("SELECT COUNT(*) FROM history_tag")).isEqualTo(1)

            val events = dao.events(1, 0, 3, 0)
            assertThat(events.map { it.tag }).containsExactly(lock, lock, null, lock).inOrder()
            assertThat(events.map { it.event.baseline }).containsExactly(true, false, false, false).inOrder()
            assertThat(events.map { it.event.bootSeq to it.event.elapsedNs })
                .containsExactly(1 to 100L, 1 to 200L, 1 to 300L, 2 to 50L)
                .inOrder()
            assertThat(dao.events(1, 200, 2, 50).map { it.event.elapsedNs }).containsExactly(200L, 300L).inOrder()
        }

    @Test
    fun aGapCanSpanAReboot() =
        runTest {
            val acrossBoot = gap(1, 800, 2, 100)
            dao.insertRead(emptyList(), listOf(acrossBoot, gap(2, 500, 2, 600, HistoryGapReason.OVERFLOW)))
            assertThat(dao.gaps(1, 0, 1, 900).map { it.copy(id = 0) }).containsExactly(acrossBoot)
            assertThat(dao.gaps(2, 0, 2, 200).map { it.copy(id = 0) }).containsExactly(acrossBoot)
            assertThat(dao.gaps(2, 100, 2, 500)).isEmpty()
            assertThat(dao.gaps(1, 0, 3, 0)).hasSize(2)
        }

    @Test
    fun theRingCutsWhereTheSnapshotsSayAndDropsUnusedTags() =
        runTest {
            val snapshots = db.snapshotDao()
            // Each read is stored with the snapshot taken after it, which marks the ids it stored. These
            // reads take no time, so the snapshots' times are where the reads ended.
            val first = dao.insertRead(listOf(event(1, 900, tag = "old"), event(2, 100, tag = "old")), listOf(gap(1, 0, 1, 50)))
            snapshots.insert(snapshot(elapsedS = 0, bootSeq = 2, historyEventId = first).copy(elapsedNs = 150, durationMs = 0))
            val second = dao.insertRead(listOf(event(2, 500, tag = "kept"), event(2, 700)), listOf(gap(1, 950, 2, 600)))
            snapshots.insert(snapshot(elapsedS = 0, bootSeq = 2, historyEventId = second).copy(elapsedNs = 800, durationMs = 0))
            // A failed read marks nothing and is skipped.
            snapshots.insert(snapshot(elapsedS = 0, bootSeq = 2, historyEventId = null).copy(elapsedNs = 850, durationMs = 0))
            assertThat(dao.events(2, 150, 2, 850).map { it.event.elapsedNs }).containsExactly(500L, 700L).inOrder()

            dao.deleteBefore(bootSeq = 2, elapsedNs = 500)
            assertThat(dao.events(0, 0, 9, 0).map { it.event.elapsedNs }).containsExactly(500L, 700L).inOrder()
            // A gap that straddles the cut stays.
            assertThat(dao.gaps(0, 0, 9, 0).map { it.endElapsedNs }).containsExactly(600L)
            assertThat(string("SELECT group_concat(tag) FROM history_tag")).isEqualTo("kept")
            // Events the snapshot before the cut had not stored stay, whatever their time.
            dao.deleteBefore(bootSeq = 2, elapsedNs = 750)
            assertThat(dao.events(0, 0, 9, 0)).hasSize(2)
            dao.deleteBefore(bootSeq = 2, elapsedNs = 801)
            assertThat(dao.events(0, 0, 9, 0)).isEmpty()
        }

    @Test
    fun anEventARunningReadStoredAfterItsSnapshotsTimeIsFound() =
        runTest {
            // The snapshot is taken at 100 s and its read, 900 ms long, stores an event at 100.5 s.
            val mark = dao.insertRead(listOf(event(1, 99 * NS_PER_S), event(1, 100_500_000_000)), emptyList())
            db.snapshotDao().insert(snapshot(elapsedS = 100, historyEventId = mark))
            assertThat(dao.events(1, 100_200_000_000, 1, 200 * NS_PER_S).map { it.event.elapsedNs }).containsExactly(100_500_000_000)
            assertThat(dao.events(1, 101 * NS_PER_S, 1, 200 * NS_PER_S)).isEmpty()
        }

    @Test
    fun kindsAreStoredAsCodes() =
        runTest {
            dao.insertRead(listOf(event(1, 1, type = HistoryKind.BATTERY_TEMPERATURE, change = HistoryChange.STOP)), emptyList())
            assertThat(string("SELECT type || ',' || change FROM history_event")).isEqualTo("9,1")
        }
}

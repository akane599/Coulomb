// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.dao.NewHistoryEvent
import io.github.akane599.coulomb.core.database.dao.StoredLevelChange
import io.github.akane599.coulomb.core.database.entity.CorruptChunkException
import io.github.akane599.coulomb.core.database.entity.HistoryChunkCodec
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
            assertThat(events.map { it.event.bootSeq to it.event.elapsedMs })
                .containsExactly(1 to 100L, 1 to 200L, 1 to 300L, 2 to 50L)
                .inOrder()
            assertThat(dao.events(1, 200 * MS, 2, 50 * MS).map { it.event.elapsedMs }).containsExactly(200L, 300L).inOrder()
            // Milliseconds compare exactly with a nanosecond span.
            assertThat(dao.events(1, 200 * MS + 1, 2, 50 * MS).map { it.event.elapsedMs }).containsExactly(300L)
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
            val first =
                dao.insertRead(listOf(event(1, 900, tag = "old"), event(2, 100, tag = "old")), listOf(gap(1, 0, 1, 50 * MS)))
            snapshots.insert(snapshot(elapsedS = 0, bootSeq = 2, historyEventId = first).copy(elapsedNs = 150 * MS, durationMs = 0))
            val second = dao.insertRead(listOf(event(2, 500, tag = "kept"), event(2, 700)), listOf(gap(1, 950 * MS, 2, 600 * MS)))
            snapshots.insert(snapshot(elapsedS = 0, bootSeq = 2, historyEventId = second).copy(elapsedNs = 800 * MS, durationMs = 0))
            // A failed read marks nothing and is skipped.
            snapshots.insert(snapshot(elapsedS = 0, bootSeq = 2, historyEventId = null).copy(elapsedNs = 850 * MS, durationMs = 0))
            assertThat(dao.events(2, 150 * MS, 2, 850 * MS).map { it.event.elapsedMs }).containsExactly(500L, 700L).inOrder()

            dao.deleteBefore(bootSeq = 2, elapsedNs = 500 * MS)
            assertThat(dao.events(0, 0, 9, 0).map { it.event.elapsedMs }).containsExactly(500L, 700L).inOrder()
            // A gap that straddles the cut stays.
            assertThat(dao.gaps(0, 0, 9, 0).map { it.endElapsedNs }).containsExactly(600 * MS)
            assertThat(string("SELECT group_concat(tag) FROM history_tag")).isEqualTo("kept")
            // Events the snapshot before the cut had not stored stay, whatever their time.
            dao.deleteBefore(bootSeq = 2, elapsedNs = 750 * MS)
            assertThat(dao.events(0, 0, 9, 0)).hasSize(2)
            dao.deleteBefore(bootSeq = 2, elapsedNs = 801 * MS)
            assertThat(dao.events(0, 0, 9, 0)).isEmpty()
        }

    private fun level(
        elapsedMs: Long,
        value: Long,
        baseline: Boolean = false,
    ) = NewHistoryEvent(1, elapsedMs, HistoryKind.BATTERY_LEVEL, HistoryChange.SET, baseline, uid = null, tag = null, value = value)

    @Test
    fun levelChangesAreTheSpansRecordedLevelsWithoutRestatedOnes() =
        runTest {
            val mark =
                dao.insertRead(
                    listOf(
                        level(200, 80, baseline = true),
                        level(300, 79),
                        event(1, 350, tag = "lock", type = HistoryKind.WAKE_LOCK, change = HistoryChange.START),
                        level(400, 78),
                        level(900, 77),
                    ),
                    emptyList(),
                )
            db.snapshotDao().insert(snapshot(elapsedS = 1, historyEventId = mark).copy(durationMs = 0))

            assertThat(dao.readAfter(1, 800 * MS)).isTrue()
            assertThat(dao.readAfter(1, 2_000 * MS)).isFalse()
            // The restated level, the wakelock and the change after the span are left out (ADR-0049).
            assertThat(dao.levelChanges(1, 150 * MS, 1, 800 * MS))
                .containsExactly(StoredLevelChange(1, 300, 79), StoredLevelChange(1, 400, 78))
                .inOrder()
        }

    @Test
    fun anEventARunningReadStoredAfterItsSnapshotsTimeIsFound() =
        runTest {
            // The snapshot is taken at 100 s and its read, 900 ms long, stores an event at 100.5 s.
            val mark = dao.insertRead(listOf(event(1, 99_000), event(1, 100_500)), emptyList())
            db.snapshotDao().insert(snapshot(elapsedS = 100, historyEventId = mark))
            assertThat(dao.events(1, 100_200_000_000, 1, 200 * NS_PER_S).map { it.event.elapsedMs }).containsExactly(100_500L)
            assertThat(dao.events(1, 101 * NS_PER_S, 1, 200 * NS_PER_S)).isEmpty()
        }

    @Test
    fun aReadIsPackedIntoChunksAndEveryEventKeepsAnId() =
        runTest {
            val many = List(HistoryChunkCodec.MAX_EVENTS + 904) { event(1, it.toLong(), tag = "t${it % 7}".takeIf { _ -> it % 3 == 0 }) }
            assertThat(dao.insertRead(many, emptyList())).isEqualTo(5_000)
            assertThat(dao.insertRead(listOf(event(2, 5)), emptyList())).isEqualTo(5_001)
            // A read with no events stores nothing and marks the event before it.
            assertThat(dao.insertRead(emptyList(), emptyList())).isEqualTo(5_001)

            assertThat(string("SELECT group_concat(firstId || ':' || count, ' ') FROM (SELECT * FROM history_chunk ORDER BY firstId)"))
                .isEqualTo("1:4096 4097:904 5001:1")
            val events = dao.events(0, 0, 9, 0)
            assertThat(events.map { it.event.id }).isEqualTo((1L..5_001L).toList())
            assertThat(events.map { it.event.bootSeq to it.event.elapsedMs }).isEqualTo(many.map { it.bootSeq to it.elapsedMs } + (2 to 5L))
            assertThat(events.map { it.tag }).isEqualTo(many.map { it.tag } + null)
            assertThat(events.take(many.size).map { it.event.uid }).isEqualTo(many.map { it.uid })
        }

    @Test
    fun aSpanReadsOnlyItsEventsFromTheChunksAroundIt() =
        runTest {
            val snapshots = db.snapshotDao()
            // Three reads, each marked by a snapshot at its end; the reads take no time.
            for (read in 0 until 3) {
                val mark = dao.insertRead(List(10) { event(1, read * 1_000L + it * 100) }, emptyList())
                snapshots.insert(snapshot(elapsedS = read + 1L, historyEventId = mark).copy(durationMs = 0))
            }
            // Inside the second read: the first read's chunk is before the span's snapshot mark.
            assertThat(dao.events(1, 1_250 * MS, 1, 1_650 * MS).map { it.event.id }).containsExactly(14L, 15L, 16L, 17L).inOrder()
            // Across two reads.
            assertThat(
                dao.events(1, 1_900 * MS, 1, 2_100 * MS + 1).map { it.event.elapsedMs },
            ).containsExactly(1_900L, 2_000L, 2_100L).inOrder()
        }

    @Test
    fun idsRunOnAfterTheRingHasEmptied() =
        runTest {
            val mark = dao.insertRead(listOf(event(1, 100), event(1, 200)), emptyList())
            db.snapshotDao().insert(snapshot(elapsedS = 1, historyEventId = mark).copy(durationMs = 0))
            dao.deleteBefore(bootSeq = 1, elapsedNs = 5 * NS_PER_S)
            assertThat(long("SELECT COUNT(*) FROM history_chunk")).isEqualTo(0)
            // An id at or below the snapshot's mark would read as already cut (ADR-0021).
            assertThat(dao.insertRead(emptyList(), emptyList())).isEqualTo(2)
            assertThat(dao.insertRead(listOf(event(1, 9_000)), emptyList())).isEqualTo(3)
            assertThat(dao.events(1, 2 * NS_PER_S, 2, 0).map { it.event.id }).containsExactly(3L)
        }

    @Test
    fun aTagStaysWhileAStoredEventUsesIt() =
        runTest {
            val snapshots = db.snapshotDao()
            val first = dao.insertRead(listOf(event(1, 100, tag = "both"), event(1, 200, tag = "first only")), emptyList())
            snapshots.insert(snapshot(elapsedS = 1, historyEventId = first).copy(durationMs = 0))
            val second = dao.insertRead(listOf(event(1, 1_100, tag = "both"), event(1, 1_200, tag = "second only")), emptyList())
            snapshots.insert(snapshot(elapsedS = 2, historyEventId = second).copy(durationMs = 0))

            // Before any cut, nothing is unused.
            dao.deleteUnusedTags()
            assertThat(long("SELECT COUNT(*) FROM history_tag")).isEqualTo(3)

            dao.deleteBefore(bootSeq = 1, elapsedNs = 1_500 * MS)
            assertThat(
                string("SELECT group_concat(tag, ',') FROM (SELECT tag FROM history_tag ORDER BY tag)"),
            ).isEqualTo("both,second only")
            assertThat(dao.events(0, 0, 9, 0).map { it.tag }).containsExactly("both", "second only").inOrder()

            dao.deleteBefore(bootSeq = 1, elapsedNs = 5 * NS_PER_S)
            assertThat(long("SELECT COUNT(*) FROM history_tag")).isEqualTo(0)
        }

    @Test
    fun aMarkInsideAChunkBoundsTheReadsAndKeepsTheChunk() =
        runTest {
            val snapshots = db.snapshotDao()
            // One read of ten events, ids 1 to 10, in one chunk. A snapshot marking id 4 cuts it in the
            // middle; no version writes one, but the reads go by id and must not depend on that.
            val mark = dao.insertRead(List(10) { level((it + 1) * 100L, 90L - it) }, emptyList())
            snapshots.insert(snapshot(elapsedS = 0, historyEventId = 4).copy(elapsedNs = 450 * MS, durationMs = 0))
            snapshots.insert(snapshot(elapsedS = 2, historyEventId = mark).copy(durationMs = 0))

            assertThat(dao.events(1, 500 * MS, 1, 1_500 * MS).map { it.event.id }).isEqualTo((5L..10L).toList())
            assertThat(dao.levelChanges(1, 500 * MS, 1, 750 * MS).map { it.elapsedMs }).containsExactly(500L, 600L, 700L).inOrder()
            assertThat(dao.events(1, 300 * MS, 1, 1_500 * MS).map { it.event.id }).isEqualTo((3L..10L).toList())

            // The cut keeps a chunk that reaches past the mark whole, the events below it too.
            dao.deleteBefore(bootSeq = 1, elapsedNs = 500 * MS)
            assertThat(string("SELECT group_concat(firstId || ':' || count) FROM history_chunk")).isEqualTo("1:10")
            assertThat(dao.events(0, 0, 9, 0)).hasSize(10)
            dao.deleteBefore(bootSeq = 1, elapsedNs = 3 * NS_PER_S)
            assertThat(long("SELECT COUNT(*) FROM history_chunk")).isEqualTo(0)
        }

    @Test
    fun aReadWithMoreTagsThanABatchStoresAndMarksEveryOne() =
        runTest {
            // 1,200 tags: more than one batch of ids for the lookup and for the marks.
            val many = List(1_200) { event(1, it.toLong(), tag = "tag $it", type = HistoryKind.WAKE_LOCK, change = HistoryChange.START) }
            val mark = dao.insertRead(many, emptyList())
            assertThat(long("SELECT COUNT(*) FROM history_tag")).isEqualTo(1_200)
            assertThat(long("SELECT COUNT(*) FROM history_tag WHERE lastEventId = $mark")).isEqualTo(1_200)
            assertThat(dao.events(0, 0, 9, 0).map { it.tag }).isEqualTo(many.map { it.tag })

            db.snapshotDao().insert(snapshot(elapsedS = 5, historyEventId = mark).copy(durationMs = 0))
            dao.deleteBefore(bootSeq = 1, elapsedNs = 6 * NS_PER_S)
            assertThat(long("SELECT COUNT(*) FROM history_tag")).isEqualTo(0)
        }

    @Test
    fun aCorruptChunkFailsTheReadInsteadOfInventingEvents() =
        runTest {
            dao.insertRead(listOf(event(1, 100), event(1, 200)), emptyList())
            sql("UPDATE history_chunk SET count = 3")
            assertThat(failure { dao.events(0, 0, 9, 0) }).isInstanceOf(CorruptChunkException::class.java)
        }

    private companion object {
        const val MS = 1_000_000L
    }
}

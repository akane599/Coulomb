// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SnapshotKind
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapshotDaoTest : DatabaseTest() {
    private val dao get() = db.snapshotDao()

    @Test
    fun aSnapshotStoresItsCountersAndTakesThemAlong() =
        runTest {
            val id = dao.insert(snapshot(elapsedS = 1), counters())
            val stored = dao.rows(id)
            val expected = counters(id)
            assertThat(stored.uidPower).containsExactlyElementsIn(expected.uidPower)
            assertThat(stored.wakeStats.map { it.copy(id = 0) }).containsExactlyElementsIn(expected.wakeStats)
            assertThat(stored.wakeStats.single().snapshotId).isEqualTo(id)
            assertThat(stored.copy(uidPower = expected.uidPower, wakeStats = expected.wakeStats)).isEqualTo(expected)
            // Enums are stored by name, so reordering an enum never changes stored rows.
            assertThat(string("SELECT provenance FROM uid_power LIMIT 1")).isEqualTo("MODELED")

            sql("DELETE FROM snapshot WHERE id = $id")
            assertThat(dao.rows(id)).isEqualTo(SnapshotRows())
        }

    @Test
    fun theLatestSnapshotIsTheLatestBootsWhateverTheClockOrIdSays() =
        runTest {
            val heavy = dao.insert(snapshot(elapsedS = 900, bootSeq = 5, wallMs = 9_000))
            val light = dao.insert(snapshot(elapsedS = 10, bootSeq = 6, wallMs = 0, kind = SnapshotKind.LIGHT))
            // Imported later, so with a higher id, but from boots before this device's.
            dao.insert(snapshot(elapsedS = 99_999, bootSeq = 4, wallMs = 99_999_000))
            assertThat(dao.latest()?.id).isEqualTo(light)
            assertThat(dao.latestHeavy()?.id).isEqualTo(heavy)
        }

    @Test
    fun countersStayOnlyWhereAWindowCanStillStart() =
        runTest {
            val plain = dao.insert(snapshot(elapsedS = 10), counters())
            val referenced = dao.insert(snapshot(elapsedS = 20), counters())
            val sessionStart = dao.insert(snapshot(elapsedS = 30), counters())
            val closedStart = dao.insert(snapshot(elapsedS = 40), counters())
            // A wall clock running far behind does not make these look older or newer.
            val latest = dao.insert(snapshot(elapsedS = 50, wallMs = 1), counters())
            dao.insert(snapshot(elapsedS = 60, kind = SnapshotKind.LIGHT))
            dao.insertUserReference("Before the update", referenced, createdWall = 20)
            db.sessionDao().insert(session(startSnapshotId = sessionStart))
            db.sessionDao().insert(session(startSnapshotId = closedStart).copy(closeReason = SessionCloseReason.REBOOT))

            assertThat(dao.pruneCounters(cutBootSeq = 2, cutElapsedNs = 0)).isEqualTo(2)
            assertThat(dao.rows(plain)).isEqualTo(SnapshotRows())
            assertThat(dao.rows(closedStart)).isEqualTo(SnapshotRows())
            for (kept in listOf(referenced, sessionStart, latest)) assertThat(dao.rows(kept).uidPower).hasSize(2)
            assertThat(listOf(plain, referenced, closedStart).map { dao.snapshot(it)?.countersPruned })
                .containsExactly(true, false, true)
                .inOrder()
            assertThat(dao.pruneCounters(2, 0)).isEqualTo(0)
        }

    @Test
    fun countersNewerThanTheCutStay() =
        runTest {
            val old = dao.insert(snapshot(elapsedS = 10), counters())
            val recent = dao.insert(snapshot(elapsedS = 20), counters())
            dao.insert(snapshot(elapsedS = 30), counters())
            assertThat(dao.pruneCounters(cutBootSeq = 1, cutElapsedNs = 15 * NS_PER_S)).isEqualTo(1)
            assertThat(dao.snapshot(old)?.countersPruned).isTrue()
            assertThat(dao.snapshot(recent)?.countersPruned).isFalse()
        }

    @Test
    fun blobsGoByKeyAndTheirPathsAreReturned() =
        runTest {
            val old = dao.insert(snapshot(elapsedS = 10, bootSeq = 1, wallMs = 99_999_999))
            dao.insert(snapshot(elapsedS = 5, bootSeq = 2, wallMs = 0))
            dao.insert(snapshot(elapsedS = 6, bootSeq = 2, blobPath = null))
            assertThat(dao.pruneBlobs(cutBootSeq = 2, cutElapsedNs = 6 * NS_PER_S))
                .containsExactly("snapshots/1-10.gz", "snapshots/2-5.gz")
            assertThat(dao.snapshot(old)?.blobPath).isNull()
            assertThat(dao.blobPaths()).isEmpty()
            assertThat(dao.pruneBlobs(2, 6 * NS_PER_S)).isEmpty()
        }

    @Test
    fun anAutomaticReferenceMovesAndFreesItsOldSnapshot() =
        runTest {
            val first = dao.insert(snapshot(elapsedS = 10), counters())
            val second = dao.insert(snapshot(elapsedS = 20), counters())
            dao.insert(snapshot(elapsedS = 30), counters())
            dao.moveAutoReference(AutoReference.SINCE_UNPLUGGED, first, createdWall = 10)
            dao.moveAutoReference(AutoReference.SINCE_UNPLUGGED, second, createdWall = 20)
            val reference = checkNotNull(dao.autoReference(AutoReference.SINCE_UNPLUGGED))
            assertThat(reference.snapshotId).isEqualTo(second)
            assertThat(reference.name).isNull()
            assertThat(long("SELECT COUNT(*) FROM reference")).isEqualTo(1)

            assertThat(dao.pruneCounters(2, 0)).isEqualTo(1)
            assertThat(dao.snapshot(first)?.countersPruned).isTrue()
            // An automatic reference is not the user's to delete.
            assertThat(dao.deleteUserReference(reference.id)).isEqualTo(0)
        }

    /** Each rule that keeps a snapshot row past the history ring's cut (ADR-0021), one row each. */
    @Test
    fun afterTheCutOnlySnapshotsNothingNeedsGo() =
        runTest {
            fun bare(
                elapsedS: Long,
                bootSeq: Int,
                kind: SnapshotKind = SnapshotKind.LIGHT,
            ) = snapshot(elapsedS, bootSeq, kind, blobPath = null).copy(historyCursorMs = null)
            val start = dao.insert(bare(10, 1))
            val end = dao.insert(bare(20, 1))
            val withBlob = dao.insert(snapshot(30, 1, SnapshotKind.LIGHT).copy(historyCursorMs = null))
            val withCounters = dao.insert(bare(40, 1, SnapshotKind.HEAVY), counters())
            val cursor = dao.insert(bare(45, 1).copy(historyCursorMs = 1_234))
            val plain = dao.insert(bare(50, 1))
            val lastOfBoot = dao.insert(bare(60, 1))
            val olderMark = dao.insert(bare(5, 2).copy(historyEventId = 4))
            val ringAnchor = dao.insert(bare(10, 2).copy(historyEventId = 5))
            val latestHeavy = dao.insert(bare(12, 2, SnapshotKind.HEAVY).copy(countersPruned = true))
            val plainToo = dao.insert(bare(14, 2))
            val afterCut = dao.insert(bare(30, 2))
            db.sessionDao().insert(session(startSnapshotId = start).copy(endSnapshotId = end, endElapsedNs = 20 * NS_PER_S))

            assertThat(dao.deleteUnneeded(cutBootSeq = 2, cutElapsedNs = 20 * NS_PER_S)).isEqualTo(3)

            val all =
                listOf(
                    start,
                    end,
                    withBlob,
                    withCounters,
                    cursor,
                    plain,
                    lastOfBoot,
                    olderMark,
                    ringAnchor,
                    latestHeavy,
                    plainToo,
                    afterCut,
                )
            assertThat(all.filter { dao.snapshot(it) != null })
                .containsExactly(start, end, withBlob, withCounters, cursor, lastOfBoot, ringAnchor, latestHeavy, afterCut)
        }

    @Test
    fun aSnapshotSomethingDependsOnCannotBeDeleted() =
        runTest {
            val referenced = dao.insert(snapshot(elapsedS = 10))
            val from = dao.insert(snapshot(elapsedS = 20))
            val to = dao.insert(snapshot(elapsedS = 30))
            dao.insertUserReference("Mine", referenced, createdWall = 1)
            db.windowDao().insert(window(from, to))
            for (id in listOf(referenced, from, to)) {
                assertThat(failure { sql("DELETE FROM snapshot WHERE id = $id") })
                    .isInstanceOf(SQLiteConstraintException::class.java)
            }
        }
}

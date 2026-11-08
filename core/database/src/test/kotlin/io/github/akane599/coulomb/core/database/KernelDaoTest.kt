// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.dao.KernelRows
import io.github.akane599.coulomb.core.database.entity.KernelReadEntity
import io.github.akane599.coulomb.core.database.entity.KmsgSpan
import io.github.akane599.coulomb.core.database.entity.WakeupSourceStatEntity
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.kernel.KernelLogEvent
import io.github.akane599.coulomb.core.model.kernel.KernelLogRecord
import io.github.akane599.coulomb.core.model.kernel.LastWakeup
import io.github.akane599.coulomb.core.model.kernel.SeqGap
import io.github.akane599.coulomb.core.model.kernel.SuspendStats
import io.github.akane599.coulomb.core.model.kernel.SuspendStep
import io.github.akane599.coulomb.core.model.kernel.WakeupCause
import io.github.akane599.coulomb.core.model.kernel.WakeupIrq
import io.github.akane599.coulomb.core.model.kernel.WakeupSourceInstance
import io.github.akane599.coulomb.core.model.kernel.WakeupSourceStats
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** The schema v3 kernel tables (ADR-0029 §3, §5). */
@RunWith(AndroidJUnit4::class)
class KernelDaoTest : DatabaseTest() {
    private val dao get() = db.snapshotDao()

    @Test
    fun kernelReadsGoInWithTheSnapshotAndComeBackAsRead() =
        runTest {
            val id = dao.insert(snapshot(elapsedS = 10), kernel = rows(BOOT_A, after = 100, last = 110, events = EVENTS))

            val read = checkNotNull(dao.kernelRead(id))
            assertThat(read.snapshotId).isEqualTo(id)
            assertThat(read.suspendStats).isEqualTo(STATS)
            assertThat(read.lastWakeup).isEqualTo(WAKEUP)
            assertThat(read.kmsgGapList).containsExactly(SeqGap(103, 103))
            assertThat(dao.wakeupSources(id).map { it.source }).containsExactly(SOURCE_1, SOURCE_7).inOrder()
            // Entry and exit lines are not stored; the rest come back in sequence order with their tags.
            assertThat(dao.kernelLog(BOOT_A, afterSeq = 100, lastSeq = 110))
                .containsExactly(EVENTS[1], EVENTS[3], EVENTS[4])
                .inOrder()

            sql("DELETE FROM snapshot WHERE id = $id")
            assertThat(dao.kernelRead(id)).isNull()
            assertThat(dao.wakeupSources(id)).isEmpty()
            assertThat(long("SELECT COUNT(*) FROM kernel_log_event")).isEqualTo(0)
        }

    @Test
    fun aRecordReadTwiceIsStoredOnceAndBootsNeverMix() =
        runTest {
            dao.insert(snapshot(elapsedS = 10), kernel = rows(BOOT_A, after = 100, last = 110, events = EVENTS))
            // A retried read covering the same records, and another boot reusing the same numbers.
            dao.insert(snapshot(elapsedS = 20), kernel = rows(BOOT_A, after = 100, last = 111, events = EVENTS))
            dao.insert(snapshot(elapsedS = 5, bootSeq = 2), kernel = rows(BOOT_B, after = null, last = 110, events = EVENTS))

            assertThat(dao.kernelLog(BOOT_A, 0, Long.MAX_VALUE).map { it.seq }).containsExactly(102L, 105L, 107L).inOrder()
            assertThat(dao.kernelLog(BOOT_B, 0, Long.MAX_VALUE).map { it.seq }).containsExactly(102L, 105L, 107L).inOrder()
            assertThat(long("SELECT COUNT(*) FROM kernel_log_event")).isEqualTo(6)
        }

    @Test
    fun theCursorIsTheFurthestStoredReadOfTheSameBoot() =
        runTest {
            assertThat(dao.kmsgCursor(BOOT_A)).isNull()
            dao.insert(snapshot(elapsedS = 10), kernel = rows(BOOT_A, after = null, last = 110))
            dao.insert(snapshot(elapsedS = 20), kernel = rows(BOOT_A, after = 110, last = 140))
            // Nothing past the cursor: the read covered up to it.
            val quiet = dao.insert(snapshot(elapsedS = 30), kernel = rows(BOOT_A, after = 140, last = null))
            dao.insert(snapshot(elapsedS = 40), kernel = KernelRows(KernelReadEntity.of(0, BOOT_A, suspendStats = STATS)))
            dao.insert(snapshot(elapsedS = 5, bootSeq = 2), kernel = rows(BOOT_B, after = null, last = 7))

            assertThat(dao.kernelRead(quiet)?.kmsgLastSeq).isEqualTo(140)
            assertThat(dao.kmsgCursor(BOOT_A)).isEqualTo(140)
            assertThat(dao.kmsgCursor(BOOT_B)).isEqualTo(7)
        }

    @Test
    fun aWindowSeesTheLogReadsAfterItsStartUpToItsEnd() =
        runTest {
            val start = dao.insert(snapshot(elapsedS = 10), kernel = rows(BOOT_A, after = null, last = 110))
            val a = dao.insert(snapshot(elapsedS = 20), kernel = rows(BOOT_A, after = 110, last = 120))
            dao.insert(snapshot(elapsedS = 25), kernel = KernelRows(KernelReadEntity.of(0, BOOT_A, suspendStats = STATS)))
            val b = dao.insert(snapshot(elapsedS = 30), kernel = rows(BOOT_A, after = 120, last = 130))
            dao.insert(snapshot(elapsedS = 40), kernel = rows(BOOT_A, after = 130, last = 140))

            val reads = dao.kmsgReadsBetween(1, 10 * NS_PER_S, 1, 30 * NS_PER_S)
            assertThat(reads.map { it.snapshotId }).containsExactly(a, b).inOrder()
            assertThat(reads.map { it.snapshotId }).doesNotContain(start)
        }

    @Test
    fun counterPruningClearsSuspendStatsAndSourcesButKeepsTheLogSpan() =
        runTest {
            val both = dao.insert(snapshot(elapsedS = 10), kernel = rows(BOOT_A, after = null, last = 110))
            val countersOnly = dao.insert(snapshot(elapsedS = 20), kernel = KernelRows(KernelReadEntity.of(0, BOOT_A, STATS, WAKEUP)))
            val latest = dao.insert(snapshot(elapsedS = 30), kernel = rows(BOOT_A, after = 110, last = 120))

            assertThat(dao.pruneCounters(1, 25 * NS_PER_S)).isEqualTo(2)

            val pruned = checkNotNull(dao.kernelRead(both))
            assertThat(pruned.suspendStats).isNull()
            assertThat(pruned.lastWakeup).isNull()
            assertThat(pruned.kmsgLastSeq).isEqualTo(110)
            assertThat(dao.wakeupSources(both)).isEmpty()
            assertThat(dao.kernelRead(countersOnly)).isNull()
            assertThat(dao.kernelRead(latest)?.suspendStats).isEqualTo(STATS)
            assertThat(dao.wakeupSources(latest)).hasSize(2)
        }

    @Test
    fun theLogRingDropsOldEventsWithTheirSpansAndFreesOnlyUnusedTags() =
        runTest {
            val old = dao.insert(snapshot(elapsedS = 10), kernel = rows(BOOT_A, after = null, last = 110, events = EVENTS))
            val newer =
                dao.insert(
                    snapshot(elapsedS = 30),
                    kernel =
                        rows(
                            BOOT_A,
                            after = 110,
                            last = 120,
                            events = listOf(KernelLogRecord(115, 9_000_000, KernelLogEvent.Abort("Pending Wakeup Sources: wlan"))),
                        ),
                )
            // A history event sharing a tag with an old kernel event keeps that tag alive.
            db.historyDao().insertRead(listOf(event(1, 40_000, tag = "qcom,smp2p-modem")), emptyList())

            dao.pruneKernelLog(1, 20 * NS_PER_S)
            db.historyDao().deleteBefore(1, 20 * NS_PER_S)

            assertThat(dao.kernelRead(old)).isNotNull() // S25 and S26 are still there.
            assertThat(dao.kernelRead(old)?.kmsgGaps).isNull()
            assertThat(dao.kernelLog(BOOT_A, 0, Long.MAX_VALUE).map { it.seq }).containsExactly(115L)
            assertThat(dao.kmsgReadsBetween(0, 0, 1, Long.MAX_VALUE).map { it.snapshotId }).containsExactly(newer)
            val tags =
                db.openHelper.readableDatabase.query("SELECT tag FROM history_tag ORDER BY tag").use {
                    buildList { while (it.moveToNext()) add(it.getString(0)) }
                }
            assertThat(tags).containsExactly("Pending Wakeup Sources: wlan", "qcom,smp2p-modem")

            // Once its counters go too, the old read's row has nothing left and goes.
            dao.pruneCounters(1, 20 * NS_PER_S)
            assertThat(dao.kernelRead(old)).isNull()
        }

    /** A retried read covers records an earlier read stored; the ring keeps them while its span stays. */
    @Test
    fun aRetriedReadKeepsTheEventsItCoversUntilItsOwnSpanGoes() =
        runTest {
            val first = dao.insert(snapshot(elapsedS = 10), kernel = rows(BOOT_A, after = 100, last = 111, events = EVENTS))
            val retry = dao.insert(snapshot(elapsedS = 20), kernel = rows(BOOT_A, after = 100, last = 111, events = EVENTS))
            // The first read owns the rows; the retry's inserts were ignored.
            assertThat(long("SELECT COUNT(*) FROM kernel_log_event WHERE readSnapshotId = $first")).isEqualTo(3)

            dao.pruneKernelLog(1, 15 * NS_PER_S)
            assertThat(dao.kernelRead(first)?.kmsgGaps).isNull()
            assertThat(dao.kmsgReadsBetween(0, 0, 1, Long.MAX_VALUE).map { it.snapshotId }).containsExactly(retry)
            assertThat(dao.kernelLog(BOOT_A, 100, 111).map { it.seq }).containsExactly(102L, 105L, 107L).inOrder()

            dao.pruneKernelLog(1, 25 * NS_PER_S)
            assertThat(dao.kmsgReadsBetween(0, 0, 1, Long.MAX_VALUE)).isEmpty()
            assertThat(dao.kernelLog(BOOT_A, null, Long.MAX_VALUE)).isEmpty()
        }

    /** S25, S26 and S9 each keep the row on their own; a read exactly at the cut is kept. */
    @Test
    fun theLogRingLeavesSuspendStatsWakeupReasonsSourcesAndReadsAtTheCut() =
        runTest {
            val suspendOnly = dao.insert(snapshot(elapsedS = 10), kernel = KernelRows(KernelReadEntity.of(0, BOOT_A, suspendStats = STATS)))
            val wakeupOnly = dao.insert(snapshot(elapsedS = 11), kernel = KernelRows(KernelReadEntity.of(0, BOOT_A, lastWakeup = WAKEUP)))
            val sourcesOnly =
                dao.insert(
                    snapshot(elapsedS = 12),
                    kernel = KernelRows(KernelReadEntity.of(0, BOOT_A), listOf(WakeupSourceStatEntity.of(0, SOURCE_1))),
                )
            val atCut = dao.insert(snapshot(elapsedS = 20), kernel = rows(BOOT_A, after = 100, last = 110, events = EVENTS))

            dao.pruneKernelLog(1, 20 * NS_PER_S)

            assertThat(dao.kernelRead(suspendOnly)?.suspendStats).isEqualTo(STATS)
            assertThat(dao.kernelRead(wakeupOnly)?.lastWakeup).isEqualTo(WAKEUP)
            assertThat(dao.kernelRead(sourcesOnly)?.kernelBoot).isEqualTo(BOOT_A)
            assertThat(dao.kernelRead(atCut)?.kmsgLastSeq).isEqualTo(110)
            assertThat(dao.kernelLog(BOOT_A, null, Long.MAX_VALUE)).hasSize(3)
        }

    @Test
    fun deletingAnUnneededSnapshotTakesItsKernelRows() =
        runTest {
            val old =
                dao.insert(
                    snapshot(elapsedS = 10, kind = SnapshotKind.LIGHT, blobPath = null),
                    kernel = rows(BOOT_A, after = 100, last = 110, events = EVENTS),
                )
            dao.insert(snapshot(elapsedS = 30, kind = SnapshotKind.LIGHT, blobPath = null))

            assertThat(dao.deleteUnneeded(1, 20 * NS_PER_S)).isEqualTo(1)

            assertThat(dao.snapshot(old)).isNull()
            assertThat(long("SELECT COUNT(*) FROM kernel_read")).isEqualTo(0)
            assertThat(long("SELECT COUNT(*) FROM wakeup_source_stat")).isEqualTo(0)
            assertThat(long("SELECT COUNT(*) FROM kernel_log_event")).isEqualTo(0)
        }

    private fun rows(
        boot: Long,
        after: Long?,
        last: Long?,
        events: List<KernelLogRecord> = emptyList(),
    ) = KernelRows(
        read =
            KernelReadEntity.of(
                snapshotId = 0,
                kernelBoot = boot,
                suspendStats = STATS,
                lastWakeup = WAKEUP,
                kmsg =
                    KmsgSpan(
                        after,
                        events.firstOrNull()?.seq ?: last,
                        last,
                        if (events.isEmpty()) emptyList() else listOf(SeqGap(103, 103)),
                    ),
            ),
        wakeupSources = listOf(WakeupSourceStatEntity.of(0, SOURCE_1), WakeupSourceStatEntity.of(0, SOURCE_7)),
        events = events,
    )

    private companion object {
        const val BOOT_A = 0x1234_5678_9abc_def0L
        const val BOOT_B = -0x0123_4567_89ab_cdefL

        val STATS =
            SuspendStats(
                success = 412,
                fail = 9,
                failedSteps = mapOf(SuspendStep.FREEZE to 2L, SuspendStep.SUSPEND to 7L),
                lastFailedDev = "alarmtimer.0.auto",
                lastFailedErrno = -16,
                lastFailedStep = "suspend",
            )
        val WAKEUP = LastWakeup(WakeupCause.Irqs(listOf(WakeupIrq(200, "qcom,smp2p-modem"))), 12_000_000, 3_600_000_000_000)
        val SOURCE_1 = WakeupSourceInstance(1, WakeupSourceStats("wlan_rx_wake", 40, 41, 3, 0, 0, 5_000, 900, 1_000_000, 200))
        val SOURCE_7 = WakeupSourceInstance(7, WakeupSourceStats("alarmtimer", 9, 9, 1, 0, 120, 800, 400, 2_000_000, 0))

        val EVENTS =
            listOf(
                KernelLogRecord(101, 1_000_000, KernelLogEvent.SuspendEntry("deep")),
                KernelLogRecord(102, 1_000_500, KernelLogEvent.ResumeIrq(WakeupIrq(200, "qcom,smp2p-modem"))),
                KernelLogRecord(104, 1_000_900, KernelLogEvent.SuspendExit),
                KernelLogRecord(105, 2_000_000, KernelLogEvent.DeviceFailed("alarmtimer alarmtimer.0.auto", "suspend", -16)),
                KernelLogRecord(107, 2_000_100, KernelLogEvent.Abort("Last active Wakeup Source: eventpoll")),
            )
    }
}

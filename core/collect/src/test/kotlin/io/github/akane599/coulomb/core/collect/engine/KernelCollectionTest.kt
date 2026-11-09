// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.snapshot.KernelReader
import io.github.akane599.coulomb.core.collect.snapshot.KernelWindow
import io.github.akane599.coulomb.core.collect.snapshot.SourceStatus
import io.github.akane599.coulomb.core.collect.snapshot.sourceStatus
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.SourceStatusCodes
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.model.PrivilegeMode
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Root kernel data through the whole engine across Shizuku mode switches (ADR-0028, ADR-0029): a
 * span without root is "not collected", never zero, and no kernel record is counted twice.
 */
@RunWith(AndroidJUnit4::class)
class KernelCollectionTest {
    private lateinit var db: CoulombDatabase
    private val clock = FakeClock()
    private val device = FakeDevice(clock)
    private val stats = FakeStats(clock)
    private val kernel get() = stats.kernel
    private var hours = 1.0

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CoulombDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun engine() = CollectorEngine(db, device, stats, FakeIdentity(), clock, MemoryBlobs(), FakeLabels())

    /** A heavy snapshot an hour after the last, with the screen toggled so the trigger asks for one. */
    private suspend fun CollectorEngine.heavy(): SnapshotEntity {
        hours += 1
        device.set(hours = hours, level = 50, chargeMah = 2_500.0, screenOn = hours.toInt() % 2 == 0)
        assertThat(handle(if (hours.toInt() % 2 == 0) SnapshotTrigger.SCREEN_ON else SnapshotTrigger.SCREEN_OFF)).isEqualTo(Action.HEAVY)
        return checkNotNull(db.snapshotDao().latest())
    }

    private suspend fun windows(): List<WindowResultEntity> =
        db
            .windowDao()
            .observeRecent(WindowKind.CONSECUTIVE, 100)
            .first()
            .sortedBy { it.toSnapshotId }

    private suspend fun rows(window: WindowResultEntity): WindowRows = db.windowDao().rows(window.id)

    private fun WindowRows.kernelWakes(kind: WakeKind) =
        wakes.filter { it.kind == kind }.associate {
            it.tag to
                (it.wakeups ?: it.count.toLong())
        }

    private fun WindowRows.suspends() = stats.firstOrNull { it.key == KernelWindow.S25_SUCCESS }?.value

    private fun WindowRows.hasKernelRows() =
        stats.any { it.key.startsWith(KernelWindow.S25_KEY_PREFIX) } || wakes.any { it.kind in KERNEL_KINDS }

    private fun count(query: String): Long =
        db.openHelper.readableDatabase.query(query).use {
            it.moveToFirst()
            it.getLong(0)
        }

    @Test
    fun rootSnapshotsStoreKernelDataAndTheirWindowCountsIt() =
        runTest {
            stats.mode = PrivilegeMode.ROOT
            val engine = engine()
            engine.heavy()
            kernel.suspend(3)
            kernel.abort("Pending Wakeup Sources: wlan")
            val second = engine.heavy()

            assertThat(second.privilegeMode).isEqualTo(PrivilegeMode.ROOT)
            assertThat(second.uptimeMs).isNotNull()
            assertThat(
                second.sourceStatus,
            ).isEqualTo(SourceStatusCodes.fromJson("""{"S25":"ok","S26":"ok","S27":"ok","S4":"off","S5":"ok","S7":"failed","S9":"ok"}"""))
            val window = windows().single()
            assertThat(window.kernelCoverage).isEqualTo(WHOLE)
            val rows = rows(window)
            assertThat(rows.suspends()).isEqualTo(3)
            assertThat(rows.stats.single { it.key == KernelWindow.S25_FAIL }.value).isEqualTo(1)
            assertThat(rows.kernelWakes(WakeKind.WAKEUP_SOURCE)).containsExactly("wlan_rx_wake", 3L)
            assertThat(rows.kernelWakes(WakeKind.RESUME_CAUSE)).containsExactly("qcom,smp2p-modem", 3L)
            // The device failure printed beside the abort is detail, never a second abort.
            assertThat(rows.kernelWakes(WakeKind.SUSPEND_ABORT)).containsExactly("Pending Wakeup Sources: wlan", 1L)
        }

    @Test
    fun rootToShellAndBackLeavesTheShellSpanNotCollectedAndCountsNothingTwice() =
        runTest {
            stats.mode = PrivilegeMode.ROOT
            val engine = engine()
            engine.heavy()
            kernel.suspend(2)
            engine.heavy()

            stats.mode = PrivilegeMode.SHELL
            stats.connection++
            kernel.suspend(5)
            val shell = engine.heavy()
            kernel.suspend(4)

            stats.mode = PrivilegeMode.ROOT
            stats.connection++
            val back = engine.heavy()
            kernel.suspend(1)
            engine.heavy()

            assertThat(shell.privilegeMode).isEqualTo(PrivilegeMode.SHELL)
            assertThat(shell.sourceStatus).isEqualTo(SourceStatusCodes.fromJson("""{"S4":"off","S5":"ok","S7":"failed"}"""))
            assertThat(db.snapshotDao().kernelRead(shell.id)).isNull()
            assertThat(back.privilegeMode).isEqualTo(PrivilegeMode.ROOT)

            val all = windows()
            val (rootSpan, toShell, fromShell) = all
            val rootAgain = all[3]
            assertThat(rootSpan.kernelCoverage).isEqualTo(WHOLE)
            assertThat(rows(rootSpan).suspends()).isEqualTo(2)
            // Both windows touching the shell snapshot say "not collected" and hold no zero rows.
            for (window in listOf(toShell, fromShell)) {
                assertThat(window.kernelCoverage).isNull()
                assertThat(rows(window).hasKernelRows()).isFalse()
            }
            assertThat(rootAgain.kernelCoverage).isEqualTo(WHOLE)
            assertThat(rows(rootAgain).suspends()).isEqualTo(1)
            assertThat(rows(rootAgain).kernelWakes(WakeKind.RESUME_CAUSE)).containsExactly("qcom,smp2p-modem", 1L)

            // The records from the shell span were read back once root returned, and stored once.
            assertThat(count("SELECT COUNT(*) FROM kernel_log_event")).isEqualTo(12)
            assertThat(count("SELECT COUNT(DISTINCT seq) FROM kernel_log_event")).isEqualTo(12)
        }

    @Test
    fun shellToRootStartsTheLogFromTheRingAndTheFirstRootWindowIsNotCollected() =
        runTest {
            val engine = engine()
            engine.heavy()
            kernel.suspend(2)
            engine.heavy()

            stats.mode = PrivilegeMode.ROOT
            stats.connection++
            engine.heavy()
            kernel.suspend(3)
            engine.heavy()

            val (shellSpan, switch, root) = windows()
            assertThat(shellSpan.kernelCoverage).isNull()
            assertThat(switch.kernelCoverage).isNull()
            assertThat(rows(switch).hasKernelRows()).isFalse()
            assertThat(root.kernelCoverage).isEqualTo(WHOLE)
            assertThat(rows(root).suspends()).isEqualTo(3)
            // The first root read had no stored cursor; the next one started where it ended.
            assertThat(stats.logCursors.first()).isEqualTo(-1)
            assertThat(stats.logCursors.last()).isGreaterThan(-1)
        }

    @Test
    fun aSwitchInsideASnapshotKeepsNoneOfItsKernelReads() =
        runTest {
            stats.mode = PrivilegeMode.ROOT
            val engine = engine()
            engine.heavy()
            kernel.suspend(2)
            stats.switchInsideSnapshot = true
            val split = engine.heavy()
            stats.switchInsideSnapshot = false
            kernel.suspend(1)
            engine.heavy()
            kernel.suspend(4)
            engine.heavy()

            assertThat(
                split.sourceStatus,
            ).isEqualTo(
                SourceStatusCodes.fromJson(
                    """{"S25":"failed","S26":"failed","S27":"failed","S4":"off","S5":"ok","S7":"failed","S9":"failed"}""",
                ),
            )
            assertThat(db.snapshotDao().kernelRead(split.id)).isNull()
            val (toSplit, fromSplit, after) = windows()
            assertThat(toSplit.kernelCoverage).isNull()
            assertThat(fromSplit.kernelCoverage).isNull()
            assertThat(after.kernelCoverage).isEqualTo(WHOLE)
            assertThat(rows(after).suspends()).isEqualTo(4)
            assertThat(count("SELECT COUNT(*) FROM kernel_log_event")).isEqualTo(7)
        }

    @Test
    fun kernelFilesReadWhileTheCpuSleptAreNotStoredButTheLogIs() =
        runTest {
            stats.mode = PrivilegeMode.ROOT
            val engine = engine()
            engine.heavy()
            kernel.suspend(2)
            // Over the 1 s sleep limit, but inside the 4 s budget, so S27 still runs.
            stats.kernelFilesSleepMs = 1_500
            val slept = engine.heavy()

            assertThat(slept.kind).isEqualTo(SnapshotKind.HEAVY)
            assertThat(
                slept.sourceStatus,
            ).isEqualTo(
                SourceStatusCodes.fromJson("""{"S25":"slept","S26":"slept","S27":"ok","S4":"off","S5":"ok","S7":"failed","S9":"slept"}"""),
            )
            val read = checkNotNull(db.snapshotDao().kernelRead(slept.id))
            assertThat(read.suspendStats).isNull()
            assertThat(db.snapshotDao().wakeupSources(slept.id)).isEmpty()
            // S27 records carry their own sequence numbers, so the window still counts them.
            val window = windows().single()
            assertThat(window.kernelCoverage).isEqualTo(KernelCoverage(Coverage.NONE, Coverage.NONE, Coverage.WHOLE))
            assertThat(rows(window).suspends()).isNull()
            assertThat(rows(window).kernelWakes(WakeKind.RESUME_CAUSE)).containsExactly("qcom,smp2p-modem", 2L)
        }

    @Test
    fun aLogReadCutShortEndsItsWindowInPartAndStartsNone() =
        runTest {
            stats.mode = PrivilegeMode.ROOT
            val engine = engine()
            engine.heavy()
            kernel.suspend(3)
            engine.heavy()
            kernel.suspend(2)
            kernel.capAt = 1
            val cut = engine.heavy()
            kernel.capAt = null
            kernel.suspend(1)
            engine.heavy()

            assertThat(sourceStatus(cut.sourceStatus, KernelReader.S27)).isEqualTo(SourceStatus.PARTIAL)
            val (whole, toCut, fromCut) = windows()
            assertThat(rows(whole).kernelWakes(WakeKind.RESUME_CAUSE)).containsExactly(MODEM, 3L)
            // At least the one record the cut read kept; the other belongs here too but is not counted.
            assertThat(toCut.kernelCoverage).isEqualTo(KernelCoverage(Coverage.WHOLE, Coverage.WHOLE, Coverage.PARTIAL))
            assertThat(rows(toCut).kernelWakes(WakeKind.RESUME_CAUSE)).containsExactly(MODEM, 1L)
            // The cut read does not say where the log stood at its snapshot, so nothing starts there.
            assertThat(fromCut.kernelCoverage).isEqualTo(KernelCoverage(Coverage.WHOLE, Coverage.WHOLE, Coverage.NONE))
            assertThat(rows(fromCut).kernelWakes(WakeKind.RESUME_CAUSE)).isEmpty()
            assertThat(count("SELECT COUNT(*) FROM kernel_log_event")).isEqualTo(6)
        }

    @Test
    fun aFailedFilesReadStillContinuesTheLogFromItsStoredCursor() =
        runTest {
            stats.mode = PrivilegeMode.ROOT
            val engine = engine()
            engine.heavy()
            kernel.suspend(2)
            kernel.filesFail = true
            val noFiles = engine.heavy()

            assertThat(
                noFiles.sourceStatus,
            ).isEqualTo(
                SourceStatusCodes.fromJson(
                    """{"S25":"failed","S26":"failed","S27":"ok","S4":"off","S5":"ok","S7":"failed","S9":"failed"}""",
                ),
            )
            // The latest log read's boot gives the cursor, so the read does not start at the ring's oldest record.
            assertThat(stats.logCursors).containsExactly(-1L, 100L).inOrder()
            val window = windows().single()
            assertThat(window.kernelCoverage).isEqualTo(KernelCoverage(Coverage.NONE, Coverage.NONE, Coverage.WHOLE))
            assertThat(rows(window).kernelWakes(WakeKind.RESUME_CAUSE)).containsExactly(MODEM, 2L)
        }

    @Test
    fun withShizukuStoppedSnapshotsHaveNoModeAndNoKernelStatuses() =
        runTest {
            stats.mode = null
            val engine = engine()
            val snapshot = engine.heavy()

            assertThat(snapshot.privilegeMode).isNull()
            assertThat(snapshot.sourceStatus).isEqualTo(SourceStatusCodes.fromJson("""{"S4":"off","S5":"ok","S7":"failed"}"""))
        }

    private companion object {
        val WHOLE = KernelCoverage(Coverage.WHOLE, Coverage.WHOLE, Coverage.WHOLE)
        const val MODEM = "qcom,smp2p-modem"
        val KERNEL_KINDS = setOf(WakeKind.WAKEUP_SOURCE, WakeKind.RESUME_CAUSE, WakeKind.SUSPEND_ABORT)
    }
}

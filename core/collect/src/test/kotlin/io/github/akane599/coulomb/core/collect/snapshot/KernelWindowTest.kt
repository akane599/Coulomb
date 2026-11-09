// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.KernelReadEntity
import io.github.akane599.coulomb.core.database.entity.KmsgSpan
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.WakeupSourceStatEntity
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import io.github.akane599.coulomb.core.model.kernel.KernelLogEvent
import io.github.akane599.coulomb.core.model.kernel.KernelLogRecord
import io.github.akane599.coulomb.core.model.kernel.SeqGap
import io.github.akane599.coulomb.core.model.kernel.SuspendStats
import io.github.akane599.coulomb.core.model.kernel.SuspendStep
import io.github.akane599.coulomb.core.model.kernel.WakeupIrq
import io.github.akane599.coulomb.core.model.kernel.WakeupSourceInstance
import io.github.akane599.coulomb.core.model.kernel.WakeupSourceStats
import org.junit.Test

/** The window rules of ADR-0029 §4, one source at a time. */
class KernelWindowTest {
    @Test
    fun suspendStatsGiveTheirChangesUnderOneBootAndNothingOtherwise() {
        val from = end(1, read(BOOT, suspend = stats(10, 2, failedSuspend = 1)))
        val to = end(2, read(BOOT, suspend = stats(14, 3, failedSuspend = 2)))

        val (coverage, rows) = KernelWindow.suspendStats(from, to)
        assertThat(coverage).isEqualTo(Coverage.WHOLE)
        assertThat(rows.associate { it.key to it.value })
            .containsExactly(KernelWindow.S25_SUCCESS, 4L, KernelWindow.S25_FAIL, 1L, "s25.failed_suspend", 1L)

        val otherBoot = end(2, read(BOOT + 1, suspend = stats(1, 0, failedSuspend = 0)))
        assertThat(KernelWindow.suspendStats(from, otherBoot)).isEqualTo(Coverage.NONE to emptyList<Any>())
        val fell = end(2, read(BOOT, suspend = stats(9, 2, failedSuspend = 1)))
        assertThat(KernelWindow.suspendStats(from, fell).first).isEqualTo(Coverage.NONE)
        val notRead = end(2, read(BOOT))
        assertThat(KernelWindow.suspendStats(from, notRead).first).isEqualTo(Coverage.NONE)
    }

    @Test
    fun wakeupSourcesCompareInstancesAndSumByNameOnlyAtTheEnd() {
        val from =
            end(
                1,
                read(BOOT),
                source(1, "wlan", wakeups = 5, total = 1_000),
                source(2, "wlan", wakeups = 1, total = 100),
                source(3, "alarm", wakeups = 7, total = 400),
                source(4, "idle", wakeups = 0, total = 50, active = 30),
            )
        val to =
            end(
                2,
                read(BOOT),
                source(1, "wlan", wakeups = 8, total = 1_300),
                source(2, "wlan", wakeups = 3, total = 160),
                source(3, "alarm", wakeups = 7, total = 900),
                // The current active stretch ended: not a reset.
                source(4, "idle", wakeups = 0, total = 80, active = 0),
                // Created inside the window: its whole value is its change.
                source(5, "modem", wakeups = 2, total = 20),
            )

        val (coverage, rows) = KernelWindow.wakeupSources(from, to)

        assertThat(coverage).isEqualTo(Coverage.WHOLE)
        // Only sources whose wakeups rose; "alarm" held a wakelock but woke nothing.
        assertThat(rows.associate { it.tag to it.wakeups }).containsExactly("wlan", 5L, "modem", 2L)
        assertThat(rows.single { it.tag == "wlan" }.totalMs).isEqualTo(360)
        assertThat(rows.map { it.kind }.toSet()).containsExactly(WakeKind.WAKEUP_SOURCE)
    }

    @Test
    fun aResetTakenOverOrVanishedSourceMakesTheWindowPartial() {
        val from = end(1, read(BOOT), source(1, "wlan", wakeups = 5, total = 1_000), source(2, "alarm", wakeups = 3, total = 10))

        val reset = end(2, read(BOOT), source(1, "wlan", wakeups = 1, total = 10), source(2, "alarm", wakeups = 4, total = 20))
        val (resetCoverage, resetRows) = KernelWindow.wakeupSources(from, reset)
        assertThat(resetCoverage).isEqualTo(Coverage.PARTIAL)
        assertThat(resetRows.associate { it.tag to it.wakeups }).containsExactly("alarm", 1L)

        val takenOver = end(2, read(BOOT), source(1, "nfc", wakeups = 2, total = 5), source(2, "alarm", wakeups = 3, total = 10))
        val (takenCoverage, takenRows) = KernelWindow.wakeupSources(from, takenOver)
        assertThat(takenCoverage).isEqualTo(Coverage.PARTIAL)
        assertThat(takenRows.associate { it.tag to it.wakeups }).containsExactly("nfc", 2L)

        val vanished = end(2, read(BOOT), source(2, "alarm", wakeups = 3, total = 10))
        assertThat(KernelWindow.wakeupSources(from, vanished).first).isEqualTo(Coverage.PARTIAL)
    }

    @Test
    fun wakeupSourcesNotReadOkAtAnEndAreNotCollected() {
        val from = end(1, read(BOOT), source(1, "wlan", wakeups = 5, total = 1_000))
        val notOk = end(2, read(BOOT), source(1, "wlan", wakeups = 6, total = 1_100), s9 = SourceStatus.PARTIAL)
        assertThat(KernelWindow.wakeupSources(from, notOk)).isEqualTo(Coverage.NONE to emptyList<Any>())
        val otherBoot = end(2, read(BOOT + 1), source(1, "wlan", wakeups = 1, total = 10))
        assertThat(KernelWindow.wakeupSources(from, otherBoot).first).isEqualTo(Coverage.NONE)
    }

    @Test
    fun aLongWindowStepsThroughTheSnapshotsInsideItSoAResetIsNotHidden() {
        val from = end(1, read(BOOT), source(1, "wlan", wakeups = 10, total = 100))
        // The driver re-registered its source under the same number and name, then it woke 12 times.
        val reset = end(2, read(BOOT), source(1, "wlan", wakeups = 2, total = 20))
        val to = end(3, read(BOOT), source(1, "wlan", wakeups = 14, total = 140))

        // Its two ends alone would read 4 wakeups, whole.
        assertThat(KernelWindow.wakeupSources(from, to).first).isEqualTo(Coverage.WHOLE)
        val (coverage, rows) = KernelWindow.wakeupSources(from, to, listOf(reset))
        assertThat(coverage).isEqualTo(Coverage.PARTIAL)
        assertThat(rows.single().wakeups).isEqualTo(12)

        // Steps without a reset add up to the two ends' change.
        val quiet = end(2, read(BOOT), source(1, "wlan", wakeups = 12, total = 120))
        assertThat(KernelWindow.wakeupSources(from, to, listOf(quiet))).isEqualTo(KernelWindow.wakeupSources(from, to))

        // A snapshot inside read no S9, as in a stretch of shell mode: a reset there would go unseen.
        val shell = end(2, null, s9 = SourceStatus.FAILED)
        assertThat(KernelWindow.wakeupSources(from, to, listOf(shell)).first).isEqualTo(Coverage.PARTIAL)
        assertThat(KernelWindow.wakeupSources(from, to, listOf(end(2, read(BOOT + 1)))).first).isEqualTo(Coverage.PARTIAL)

        // A snapshot inside whose rows went with its counters, after two hours.
        val pruned = end(2, read(BOOT)).let { it.copy(snapshot = it.snapshot.copy(countersPruned = true)) }
        assertThat(pruned.sourcesOk).isFalse()
        assertThat(KernelWindow.wakeupSources(from, to, listOf(pruned, quiet)).first).isEqualTo(Coverage.PARTIAL)
    }

    @Test
    fun theLogRangeIsWholeOnlyWhenOneBootsReadsCoverIt() {
        val from = end(1, read(BOOT, kmsg = span(after = 90, last = 100)))
        val middle = read(BOOT, kmsg = span(after = 100, last = 130))
        val endRead = read(BOOT, kmsg = span(after = 130, last = 150))
        val to = end(3, endRead)

        assertThat(KernelWindow.logRange(from, listOf(middle, endRead), to)).isEqualTo(LogRange(BOOT, 100, 150, Coverage.WHOLE))

        // A gap: the ring overwrote records before a read reached them.
        val gappy = read(BOOT, kmsg = span(after = 100, last = 130, gaps = listOf(SeqGap(110, 115))))
        assertThat(KernelWindow.logRange(from, listOf(gappy, endRead), to)?.coverage).isEqualTo(Coverage.PARTIAL)

        // A missing read in between leaves records 131..150 covered and 101..130 not.
        assertThat(KernelWindow.logRange(from, listOf(endRead), to)?.coverage).isEqualTo(Coverage.PARTIAL)

        // The end did not read the log: what the reads before it covered is all the window can say.
        val noEnd = end(3, read(BOOT))
        assertThat(KernelWindow.logRange(from, listOf(middle), noEnd)).isEqualTo(LogRange(BOOT, 100, 130, Coverage.PARTIAL))

        // A read under another kernel boot in between.
        val other = read(BOOT + 1, kmsg = span(after = null, last = 20, first = 0))
        assertThat(KernelWindow.logRange(from, listOf(middle, other, endRead), to)?.coverage).isEqualTo(Coverage.PARTIAL)
    }

    @Test
    fun aStartWithoutALogSpanOrNothingReadSinceGivesNoRange() {
        val toRead = read(BOOT, kmsg = span(after = 100, last = 120))
        val to = end(2, toRead)
        assertThat(KernelWindow.logRange(end(1, read(BOOT)), listOf(toRead), to)).isNull()
        assertThat(KernelWindow.logRange(end(1, null), listOf(toRead), to)).isNull()

        val from = end(1, read(BOOT, kmsg = span(after = 90, last = 100)))
        assertThat(KernelWindow.logRange(from, emptyList(), end(2, read(BOOT)))).isNull()
        // Nothing new, and the end read the log to say so: a whole range of no records.
        val quiet = read(BOOT, kmsg = span(after = 100, last = null))
        assertThat(KernelWindow.logRange(from, listOf(quiet), end(2, quiet))).isEqualTo(LogRange(BOOT, 100, 100, Coverage.WHOLE))
    }

    @Test
    fun aLogReadCutShortCannotStartARangeAndEndsOneOnlyInPart() {
        val from = end(1, read(BOOT, kmsg = span(after = 90, last = 100)))
        val cut = read(BOOT, kmsg = span(after = 100, last = 120))
        // The records after the end's last kept one happened inside this window too.
        assertThat(KernelWindow.logRange(from, listOf(cut), end(2, cut, s27 = SourceStatus.PARTIAL)))
            .isEqualTo(LogRange(BOOT, 100, 120, Coverage.PARTIAL))

        // A start read from the ring's oldest record and capped at 300, after an earlier read had
        // reached 500: the next window must not count 301..500 again.
        val capped = end(2, read(BOOT, kmsg = span(after = null, last = 300, first = 50)), s27 = SourceStatus.PARTIAL)
        val next = read(BOOT, kmsg = span(after = 500, last = 600))
        assertThat(KernelWindow.logRange(capped, listOf(next), end(3, next))).isNull()
    }

    @Test
    fun abortsCountTheirAbortLinesAndResumesCountEachInterruptLine() {
        val range = LogRange(BOOT, 0, 100, Coverage.WHOLE)
        val events =
            listOf(
                record(1, KernelLogEvent.ResumeIrq(WakeupIrq(200, "modem"))),
                record(2, KernelLogEvent.ResumeIrq(WakeupIrq(201, "wlan"))),
                record(3, KernelLogEvent.ResumeIrq(WakeupIrq(200, "modem"))),
                record(4, KernelLogEvent.ResumeUnknown),
                record(5, KernelLogEvent.DeviceFailed("alarmtimer alarmtimer.0.auto", "suspend", -16)),
                record(6, KernelLogEvent.DevicesFailed),
                record(7, KernelLogEvent.Abort("Pending Wakeup Sources: wlan")),
                record(8, KernelLogEvent.FreezeFailed("user space tasks", aborted = true)),
            )

        val (coverage, rows) = KernelWindow.logRows(range, events)

        assertThat(coverage).isEqualTo(Coverage.WHOLE)
        assertThat(rows.filter { it.kind == WakeKind.RESUME_CAUSE }.associate { it.tag to it.count })
            .containsExactly("modem", 2, "wlan", 1, "Resume cause unknown", 1)
        assertThat(rows.filter { it.kind == WakeKind.SUSPEND_ABORT }.associate { it.tag to it.count })
            .containsExactly("Pending Wakeup Sources: wlan", 1)
    }

    @Test
    fun rowsPastTheCapMakeTheSourcePartialInsteadOfReadingAsZero() {
        val events = (1..KernelWindow.MAX_ROWS_PER_KIND + 1).map { record(it.toLong(), KernelLogEvent.Abort("reason $it")) }
        val (coverage, rows) = KernelWindow.logRows(LogRange(BOOT, 0, 1_000, Coverage.WHOLE), events)
        assertThat(coverage).isEqualTo(Coverage.PARTIAL)
        assertThat(rows).hasSize(KernelWindow.MAX_ROWS_PER_KIND)
    }

    @Test
    fun aWindowWithNoKernelSourceCollectedHasNoCoverage() {
        val built =
            KernelWindow.build(
                end(1, read(BOOT), s9 = SourceStatus.FAILED),
                end(2, read(BOOT), s9 = SourceStatus.FAILED),
                null,
                emptyList(),
            )
        assertThat(built.coverage).isNull()
        assertThat(built.wakes).isEmpty()
        assertThat(built.stats).isEmpty()
    }

    private fun end(
        id: Long,
        read: KernelReadEntity?,
        vararg sources: WakeupSourceStatEntity,
        s9: SourceStatus = SourceStatus.OK,
        s27: SourceStatus = SourceStatus.OK,
    ) = KernelEnd(
        snapshot(id, sourceStatusCode(mapOf("S5" to SourceStatus.OK, KernelReader.S9 to s9, KernelReader.S27 to s27))),
        read,
        sources.toList(),
    )

    private fun read(
        boot: Long,
        suspend: SuspendStats? = null,
        kmsg: KmsgSpan? = null,
    ) = KernelReadEntity.of(0, boot, suspend, null, kmsg)

    private fun span(
        after: Long?,
        last: Long?,
        first: Long? = after?.plus(1)?.takeIf { last != null },
        gaps: List<SeqGap> = emptyList(),
    ) = KmsgSpan(after, first, last, gaps)

    private fun stats(
        success: Long,
        fail: Long,
        failedSuspend: Long,
    ) = SuspendStats(success, fail, mapOf(SuspendStep.SUSPEND to failedSuspend), null, null, null)

    private fun source(
        instance: Int,
        name: String,
        wakeups: Long,
        total: Long,
        active: Long = 0,
    ) = WakeupSourceStatEntity.of(
        0,
        WakeupSourceInstance(instance, WakeupSourceStats(name, 10 + wakeups, 10 + wakeups, wakeups, 0, active, total, total, 0, 0)),
    )

    private fun record(
        seq: Long,
        event: KernelLogEvent,
    ) = KernelLogRecord(seq, seq * 1_000, event)

    private fun snapshot(
        id: Long,
        statuses: Long,
    ) = SnapshotEntity(
        id = id,
        bootSeq = 1,
        elapsedNs = id * 1_000_000_000,
        wallMs = id * 1_000,
        trigger = SnapshotTrigger.SCREEN_OFF,
        kind = SnapshotKind.HEAVY,
        levelPct = 50,
        chargeUah = null,
        currentRaw = null,
        voltageMv = null,
        tempDeciC = null,
        plugType = 0,
        status = null,
        statsStartWall = null,
        statsRealtimeMs = null,
        historyCursorMs = null,
        historyEventId = null,
        sourceStatus = statuses,
        blobPath = null,
        partial = false,
        countersPruned = false,
        durationMs = 0,
    )

    private companion object {
        const val BOOT = 0x0abc_def0_1234_5678L
    }
}

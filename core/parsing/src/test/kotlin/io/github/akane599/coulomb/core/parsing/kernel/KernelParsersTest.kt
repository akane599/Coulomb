// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.kernel

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.kernel.KernelBoot
import io.github.akane599.coulomb.core.model.kernel.KernelLogEvent
import io.github.akane599.coulomb.core.model.kernel.SeqGap
import io.github.akane599.coulomb.core.model.kernel.SuspendStep
import io.github.akane599.coulomb.core.model.kernel.WakeupCause
import io.github.akane599.coulomb.core.model.kernel.WakeupIrq
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class KernelParsersTest {
    // Values as kernel/power/main.c prints them: counters "%u\n", device and step "%s\n", errno "%d\n".
    private val stats =
        mapOf(
            "success" to "1234\n",
            "fail" to "7\n",
            "failed_freeze" to "1\n",
            "failed_prepare" to "0\n",
            "failed_suspend" to "5\n",
            "failed_suspend_late" to "0\n",
            "failed_suspend_noirq" to "1\n",
            "failed_resume" to "0\n",
            "failed_resume_early" to "0\n",
            "failed_resume_noirq" to "0\n",
            "last_failed_dev" to "alarmtimer.0.auto\n",
            "last_failed_errno" to "-16\n",
            "last_failed_step" to "suspend\n",
        )

    @Test
    fun suspendStatsReadEveryCounterAndTheLastFailure() {
        val (value, report) = SuspendStatsParser.parse(stats)
        assertThat(report.complete).isTrue()
        assertThat(report.issueCount).isEqualTo(0)
        checkNotNull(value)
        assertThat(value.success).isEqualTo(1234)
        assertThat(value.fail).isEqualTo(7)
        assertThat(value.failedSteps).hasSize(SuspendStep.entries.size)
        assertThat(value.failedSteps[SuspendStep.SUSPEND]).isEqualTo(5)
        assertThat(value.failedSteps[SuspendStep.PREPARE]).isEqualTo(0)
        assertThat(value.lastFailedDev).isEqualTo("alarmtimer.0.auto")
        assertThat(value.lastFailedErrno).isEqualTo(-16)
        assertThat(value.lastFailedStep).isEqualTo("suspend")
    }

    @Test
    fun aKernelThatNeverFailedReportsNoLastFailure() {
        // The emulator, as root: empty device and step lines, errno 0 (kernel/power/main.c initial state).
        val fresh =
            stats +
                mapOf(
                    "success" to "0\n",
                    "fail" to "0\n",
                    "last_failed_dev" to "\n",
                    "last_failed_errno" to "0\n",
                    "last_failed_step" to "\n",
                )
        val value = checkNotNull(SuspendStatsParser.parse(fresh).value)
        assertThat(value.success).isEqualTo(0)
        assertThat(value.lastFailedDev).isNull()
        assertThat(value.lastFailedErrno).isNull()
        assertThat(value.lastFailedStep).isNull()
    }

    @Test
    fun aMissingStepFileIsLeftOutNeverZero() {
        val (value, report) = SuspendStatsParser.parse(stats - "failed_resume_noirq")
        checkNotNull(value)
        assertThat(value.failedSteps).doesNotContainKey(SuspendStep.RESUME_NOIRQ)
        assertThat(report.complete).isFalse()
    }

    @Test
    fun withoutSuccessOrFailThereIsNoValue() {
        assertThat(SuspendStatsParser.parse(stats - "success").value).isNull()
        assertThat(SuspendStatsParser.parse(stats + ("fail" to "x\n")).value).isNull()
        assertThat(SuspendStatsParser.parse(stats + ("fail" to "-1\n")).value).isNull()
    }

    @Test
    fun unknownFilesAreCountedByNameOnly() {
        val (_, report) = SuspendStatsParser.parse(stats + ("total_hw_sleep" to "99\n"))
        assertThat(report.unknown).containsExactly("total_hw_sleep", 1)
    }

    @Test
    fun lastResumeReasonListsEveryLeafIrq() {
        val (value, report) =
            WakeupReasonParser.parse(
                "170 qcom,glink-smem-native-xprt-modem\n57 pmic_arb 66\n",
                "0.105320153 1803.412000117\n",
            )
        checkNotNull(value)
        assertThat(report.complete).isTrue()
        assertThat(
            value.reason,
        ).isEqualTo(WakeupCause.Irqs(listOf(WakeupIrq(170, "qcom,glink-smem-native-xprt-modem"), WakeupIrq(57, "pmic_arb 66"))))
        assertThat(value.suspendResumeNs).isEqualTo(105_320_153L)
        assertThat(value.sleepNs).isEqualTo(1_803_412_000_117L)
    }

    @Test
    fun lastResumeReasonReadsAbortAbnormalAndNothing() {
        assertThat(WakeupReasonParser.parse("Abort: Pending Wakeup Sources: alarmtimer", null).value?.reason)
            .isEqualTo(WakeupCause.Abort("Pending Wakeup Sources: alarmtimer"))
        assertThat(WakeupReasonParser.parse("-1 unknown wakeup", null).value?.reason).isEqualTo(WakeupCause.Abnormal("unknown wakeup"))
        // The emulator never suspends: empty reason, zero times.
        val (value, report) = WakeupReasonParser.parse("", "0.000000000 0.000000000\n")
        assertThat(value?.reason).isEqualTo(WakeupCause.None)
        assertThat(value?.sleepNs).isEqualTo(0)
        assertThat(report.complete).isTrue()
    }

    @Test
    fun badWakeupTextGivesNoInventedCause() {
        assertThat(WakeupReasonParser.parse("garbage line\n", null).value).isNull()
        val (value, report) = WakeupReasonParser.parse("12 name\n", "1.5 2\n")
        assertThat(value?.suspendResumeNs).isNull()
        assertThat(report.complete).isFalse()
    }

    private val source =
        mapOf(
            "name" to "PowerManagerService.WakeLocks\n",
            "active_count" to "88\n",
            "event_count" to "88\n",
            "wakeup_count" to "3\n",
            "expire_count" to "0\n",
            "active_time_ms" to "0\n",
            "total_time_ms" to "51234\n",
            "max_time_ms" to "4100\n",
            "last_change_ms" to "9876543\n",
            "prevent_suspend_time_ms" to "40000\n",
        )

    @Test
    fun aWakeupSourceNeedsEveryCounter() {
        val (value, report) = WakeupSourceParser.parse(source)
        checkNotNull(value)
        assertThat(report.complete).isTrue()
        assertThat(value.name).isEqualTo("PowerManagerService.WakeLocks")
        assertThat(value.wakeupCount).isEqualTo(3)
        assertThat(value.totalTimeMs).isEqualTo(51234)
        assertThat(value.preventSuspendTimeMs).isEqualTo(40000)
        assertThat(WakeupSourceParser.parse(source - "wakeup_count").value).isNull()
        assertThat(WakeupSourceParser.parse(source + ("name" to "\n")).value).isNull()
    }

    private fun kmsg(vararg lines: String): InputStream = ByteArrayInputStream(lines.joinToString("") { "$it\n" }.encodeToByteArray())

    private val boot = "8a5a3b74-1f2e-4d6c-9b7a-0c1d2e3f4a5b"
    private val bootKey = 0x8a5a3b741f2e4d6cuL.toLong()

    private fun begin(
        first: String,
        after: String = "-",
    ) = "#begin boot=$boot after=$after first=$first"

    @Test
    fun theBootKeyIsTheFirstHalfOfBootId() {
        assertThat(KernelBoot.key("$boot\n")).isEqualTo(bootKey)
        assertThat(KernelBoot.key("8A5A3B74-1F2E-4D6C-9B7A-0C1D2E3F4A5B")).isEqualTo(bootKey)
        assertThat(KernelBoot.key("8a5a3b741f2e4d6c9b7a0c1d2e3f4a5b")).isNull()
        assertThat(KernelBoot.key("8a5a3b74-1f2e-4d6c-9b7a-0c1d2e3f4a5g")).isNull()
        assertThat(KernelBoot.key("")).isNull()
    }

    @Test
    fun aWholeKernelLogReadKeepsRecordsAndTheSpan() {
        val (read, report) =
            KernelLogParser.parse(
                kmsg(
                    begin("1645"),
                    "6,1700,8123456,-;PM: suspend entry (deep)",
                    "6,1711,8123999,-;PM: suspend exit",
                    "6,1712,8124001,-;Resume caused by IRQ 170, qcom,glink-smem-native-xprt-modem",
                    "6,1720,9000000,-;Abort: Pending Wakeup Sources: alarmtimer",
                    "3,1721,9000100,-;PM: Some devices failed to suspend, or early wake event detected",
                    "3,1722,9000200,-;alarmtimer alarmtimer.0.auto: PM: failed to suspend: error -16",
                    "3,1723,9000300,-,caller=T123;Freezing user space processes failed after 20.006 seconds " +
                        "(1 tasks refusing to freeze, wq_busy=0):",
                    "6,1724,9000400,-;Resume caused by bluetooth",
                    "6,1725,9000500,-;Resume cause unknown",
                    "#end last=1800 lost=0",
                ),
            )
        assertThat(report.complete).isTrue()
        assertThat(read.bootKey).isEqualTo(bootKey)
        assertThat(read.firstSeq).isEqualTo(1645)
        assertThat(read.lastSeq).isEqualTo(1800)
        assertThat(read.gaps).isEmpty()
        assertThat(read.records.map { it.event })
            .containsExactly(
                KernelLogEvent.SuspendEntry("deep"),
                KernelLogEvent.SuspendExit,
                KernelLogEvent.ResumeIrq(WakeupIrq(170, "qcom,glink-smem-native-xprt-modem")),
                KernelLogEvent.Abort("Pending Wakeup Sources: alarmtimer"),
                KernelLogEvent.DevicesFailed,
                KernelLogEvent.DeviceFailed("alarmtimer alarmtimer.0.auto", "suspend", -16),
                KernelLogEvent.FreezeFailed("user space processes", aborted = false),
                KernelLogEvent.ResumeOther("bluetooth"),
                KernelLogEvent.ResumeUnknown,
            ).inOrder()
        assertThat(read.records.first().monotonicUs).isEqualTo(8_123_456L)
    }

    @Test
    fun aCutReadKeepsItsPrefixAndEndsAtTheLastRecord() {
        val (read, report) =
            KernelLogParser.parse(
                kmsg(begin("10"), "6,12,5,-;PM: suspend exit", "6,15,6,-;Resume cause unknown", "#gap from=16 to=19"),
            )
        assertThat(report.complete).isFalse()
        assertThat(read.records.map { it.seq }).containsExactly(12L, 15L).inOrder()
        assertThat(read.firstSeq).isEqualTo(10)
        assertThat(read.lastSeq).isEqualTo(15)
        // The gap after the last kept record is the next read's to report, once.
        assertThat(read.gaps).isEmpty()
        assertThat(read.lost).isEqualTo(0)
    }

    @Test
    fun aStreamThatFailsMidwayIsIncomplete() {
        val failing =
            object : InputStream() {
                private val head = "${begin("1")}\n6,2,3,-;PM: suspend exit\n6,3,4,-;Resume ca".encodeToByteArray()
                private var i = 0

                override fun read(): Int = if (i < head.size) head[i++].toInt() and 0xff else throw IOException("worker died")
            }
        val (read, report) = KernelLogParser.parse(failing)
        assertThat(report.complete).isFalse()
        assertThat(read.records.map { it.seq }).containsExactly(2L)
        assertThat(read.lastSeq).isEqualTo(2)
    }

    @Test
    fun aRingWithNothingPastTheCursorIsAWholeReadOfNothing() {
        val (read, report) = KernelLogParser.parse(kmsg(begin("-"), "#end last=- lost=0"))
        assertThat(report.complete).isTrue()
        assertThat(read.records).isEmpty()
        assertThat(read.firstSeq).isNull()
        assertThat(read.lastSeq).isNull()
    }

    @Test
    fun nothingNewSinceTheCursorIsAWholeReadOfNothing() {
        // Exactly what KernelLogCopier writes when every record is at or before its cursor
        // (KernelStreamsTest.nothingPastTheCursorIsAnEmptyWholeRead).
        val (read, report) = KernelLogParser.parse(kmsg(begin("-", after = "20"), "#end last=- lost=0"))
        assertThat(report.complete).isTrue()
        assertThat(read.afterSeq).isEqualTo(20)
        assertThat(read.firstSeq).isNull()
        assertThat(read.lastSeq).isNull()
        // A gap with no record past the cursor contradicts the trailer.
        val (_, contradiction) =
            KernelLogParser.parse(kmsg(begin("-", after = "20"), "#gap from=21 to=25", "#end last=- lost=5"))
        assertThat(contradiction.complete).isFalse()
    }

    @Test
    fun gapsAreKeptInOrderAndMustMatchTheTrailer() {
        // The ring no longer reached the record after the cursor (99), and lost 160..164 mid-read.
        val lines =
            arrayOf(
                begin("140", after = "99"),
                "#gap from=100 to=139",
                "6,150,1,-;PM: suspend exit",
                "#gap from=160 to=164",
                "6,170,2,-;Resume cause unknown",
            )
        val (read, report) = KernelLogParser.parse(kmsg(*lines, "#end last=900 lost=45"))
        assertThat(report.complete).isTrue()
        assertThat(read.afterSeq).isEqualTo(99)
        assertThat(read.gaps).containsExactly(SeqGap(100, 139), SeqGap(160, 164)).inOrder()
        assertThat(read.lost).isEqualTo(45)
        assertThat(read.lastSeq).isEqualTo(900)
        assertThat(KernelLogParser.parse(kmsg(*lines, "#end last=900 lost=40")).report.complete).isFalse()
    }

    @Test
    fun gapsOutOfOrderMakeTheReadIncomplete() {
        val overlap = KernelLogParser.parse(kmsg(begin("10"), "6,20,1,-;PM: suspend exit", "#gap from=20 to=25", "#end last=30 lost=6"))
        assertThat(overlap.report.complete).isFalse()
        val straddle = KernelLogParser.parse(kmsg(begin("10"), "#gap from=5 to=12", "#end last=30 lost=8"))
        assertThat(straddle.report.complete).isFalse()
        val backwards = KernelLogParser.parse(kmsg(begin("10"), "#gap from=15 to=12", "#end last=30 lost=0"))
        assertThat(backwards.report.complete).isFalse()
    }

    @Test
    fun outOfOrderOrBrokenRecordsMakeTheReadIncomplete() {
        val back = KernelLogParser.parse(kmsg(begin("1"), "6,9,1,-;PM: suspend exit", "6,8,2,-;PM: suspend exit", "#end last=9 lost=0"))
        assertThat(back.report.complete).isFalse()
        assertThat(back.value.records.map { it.seq }).containsExactly(9L)
        val beforeFirst = KernelLogParser.parse(kmsg(begin("5"), "6,4,1,-;PM: suspend exit", "#end last=9 lost=0"))
        assertThat(beforeFirst.report.complete).isFalse()
        val broken = KernelLogParser.parse(kmsg(begin("1"), "6,x,1,-;PM: suspend exit", "#end last=9 lost=0"))
        assertThat(broken.report.complete).isFalse()
        val noHeader = KernelLogParser.parse(kmsg("6,2,1,-;PM: suspend exit", "#end last=9 lost=0"))
        assertThat(noHeader.report.complete).isFalse()
        assertThat(noHeader.value.firstSeq).isNull()
        val noBoot = KernelLogParser.parse(kmsg("#begin after=- first=1", "#end last=1 lost=0"))
        assertThat(noBoot.report.complete).isFalse()
        assertThat(noBoot.value.bootKey).isNull()
        val lastBeforeRecord = KernelLogParser.parse(kmsg(begin("1"), "6,9,1,-;PM: suspend exit", "#end last=8 lost=0"))
        assertThat(lastBeforeRecord.report.complete).isFalse()
        val noLast = KernelLogParser.parse(kmsg(begin("1"), "#end last=- lost=0"))
        assertThat(noLast.report.complete).isFalse()
        val firstAtCursor = KernelLogParser.parse(kmsg(begin("40", after = "40"), "#end last=40 lost=0"))
        assertThat(firstAtCursor.report.complete).isFalse()
        val gapAtCursor = KernelLogParser.parse(kmsg(begin("45", after = "40"), "#gap from=40 to=44", "#end last=50 lost=5"))
        assertThat(gapAtCursor.report.complete).isFalse()
    }

    @Test
    fun anUnclassifiedRecordIsCountedAndStillMovesTheCursor() {
        val (read, report) =
            KernelLogParser.parse(kmsg(begin("1"), "6,2,1,-;PM: suspend entry deep", "6,2,2,-;PM: suspend exit", "#end last=2 lost=0"))
        assertThat(read.records).isEmpty()
        assertThat(report.complete).isFalse()
        assertThat(report.unknown).containsExactly("unclassified line", 1)
        val (_, whole) = KernelLogParser.parse(kmsg(begin("1"), "6,2,1,-;PM: suspend entry deep", "#end last=2 lost=0"))
        assertThat(whole.complete).isTrue()
    }

    @Test
    fun theClassifierRejectsNearMisses() {
        assertThat(KernelLogLines.classify("Resume caused by IRQ x, name")).isNull()
        assertThat(KernelLogLines.classify("Resume caused by IRQ 5,")).isNull()
        assertThat(KernelLogLines.classify("Freezing user space processes completed (elapsed 0.010 seconds)")).isNull()
        assertThat(KernelLogLines.classify("dev: PM: failed to suspend: error x")).isNull()
        assertThat(KernelLogLines.classify("PM: suspend exit now")).isNull()
        assertThat(KernelLogLines.classify("Freezing remaining freezable tasks aborted after 0.003 seconds"))
            .isEqualTo(KernelLogEvent.FreezeFailed("remaining freezable tasks", aborted = true))
    }
}

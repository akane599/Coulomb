// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.PrivilegeMode
import io.github.akane599.coulomb.core.model.kernel.KernelBoot
import io.github.akane599.coulomb.core.model.kernel.KernelPaths
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.parsing.kernel.KernelFiles
import io.github.akane599.coulomb.core.parsing.kernel.KernelFilesParser
import io.github.akane599.coulomb.core.parsing.kernel.KernelLogParser
import io.github.akane599.coulomb.core.parsing.kernel.WakeupSourceParser
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.FailureReason
import io.github.akane599.coulomb.core.privileged.PartialReason
import io.github.akane599.coulomb.core.privileged.Route
import io.github.akane599.coulomb.core.privileged.WorkerOrigin
import org.junit.Test
import java.io.ByteArrayInputStream

/** How a snapshot's kernel reads become statuses and rows (ADR-0029 §1, §5). */
class KernelReadsTest {
    private val bootId = "8a5a3b74-1f2e-4d6c-9b7a-0c1d2e3f4a5b"
    private val bootKey = checkNotNull(KernelBoot.key(bootId))
    private val root = WorkerOrigin(0, 4)

    private fun file(
        path: String,
        body: String,
    ) = "@$path ${body.encodeToByteArray().size}\n$body\n"

    private fun suspendStats() =
        (
            listOf(
                "success" to "12\n",
                "fail" to "1\n",
                "last_failed_dev" to "\n",
                "last_failed_errno" to "0\n",
                "last_failed_step" to "\n",
            ) +
                SUSPEND_STEPS.map { "failed_$it" to "0\n" }
        ).map { (name, body) -> file("${KernelPaths.SUSPEND_STATS}/$name", body) }

    private fun wakeupReasons() =
        listOf(
            file("${KernelPaths.WAKEUP_REASONS}/${KernelPaths.LAST_RESUME_REASON}", "170 qcom,glink\n"),
            file("${KernelPaths.WAKEUP_REASONS}/${KernelPaths.LAST_SUSPEND_TIME}", "0.100000000 12.000000000\n"),
        )

    private fun source(n: Int) =
        WakeupSourceParser.ATTRIBUTES.map { attr ->
            file("${KernelPaths.WAKEUP_CLASS}/wakeup$n/$attr", if (attr == "name") "eventpoll\n" else "4\n")
        }

    private fun files(
        frames: List<String>,
        result: (Parsed<KernelFiles>) -> DumpResult<Parsed<KernelFiles>> = { DumpResult.Complete(it, Route.WORKER, 0, 0, root) },
    ): KernelFilesRead =
        KernelReader.filesOf(result(KernelFilesParser.parse(ByteArrayInputStream(frames.joinToString("").encodeToByteArray()))))

    @Test
    fun aWholeFilesReadIsOkForEverySource() {
        val read =
            files(listOf(file(KernelPaths.BOOT_ID, "$bootId\n")) + suspendStats() + wakeupReasons() + source(0) + source(3) + "#end\n")

        assertThat(read.statuses).containsExactly("S25", SourceStatus.OK, "S26", SourceStatus.OK, "S9", SourceStatus.OK)
        assertThat(read.bootKey).isEqualTo(bootKey)
        assertThat(read.suspendStats?.success).isEqualTo(12)
        assertThat(read.wakeupSources?.map { it.instance }).containsExactly(0, 3)
        assertThat(read.origin).isEqualTo(root)
    }

    @Test
    fun aReadCutInsideS9KeepsTheSourcesBeforeItAndNothingOfS9() {
        val cut =
            files(listOf(file(KernelPaths.BOOT_ID, "$bootId\n")) + suspendStats() + wakeupReasons() + source(0).take(3)) {
                DumpResult.Partial(it, Route.WORKER, 0, PartialReason.TIMEOUT, null, root)
            }

        assertThat(cut.statuses).containsExactly("S25", SourceStatus.OK, "S26", SourceStatus.OK, "S9", SourceStatus.PARTIAL)
        assertThat(cut.suspendStats).isNotNull()
        assertThat(cut.wakeupSources).isNull()
    }

    @Test
    fun noBootIdOrNoWorkerStoresNothing() {
        val noBoot = files(suspendStats() + "#end\n")
        assertThat(noBoot.statuses.values.toSet()).containsExactly(SourceStatus.FAILED)
        assertThat(noBoot.suspendStats).isNull()

        val unavailable = KernelReader.filesOf(DumpResult.Unavailable(FailureReason.ACCESS_DENIED, "not root"))
        assertThat(unavailable.statuses.values.toSet()).containsExactly(SourceStatus.FAILED)
        assertThat(unavailable.origin).isNull()
    }

    @Test
    fun aLogReadCutShortKeepsItsPrefixAndOneWithoutAHeaderNothing() {
        val text = "#begin boot=$bootId after=10 first=11\n6,12,1000,-;Abort: Pending Wakeup Sources: wlan\n"
        val parsed = KernelLogParser.parse(ByteArrayInputStream(text.encodeToByteArray()))
        val cut = KernelReader.logOf(DumpResult.Partial(parsed, Route.WORKER, 0, PartialReason.TIMEOUT, null, root))
        assertThat(cut.status).isEqualTo(SourceStatus.PARTIAL)
        assertThat(cut.read?.lastSeq).isEqualTo(12)

        val whole = KernelLogParser.parse(ByteArrayInputStream("$text#end last=15 lost=0\n".encodeToByteArray()))
        assertThat(KernelReader.logOf(DumpResult.Complete(whole, Route.WORKER, 0, 0, root)).status).isEqualTo(SourceStatus.OK)

        val headless = KernelLogParser.parse(ByteArrayInputStream("6,12,1000,-;Abort: x\n".encodeToByteArray()))
        val failed = KernelReader.logOf(DumpResult.Partial(headless, Route.WORKER, 0, PartialReason.TRUNCATED, null, root))
        assertThat(failed.status).isEqualTo(SourceStatus.FAILED)
        assertThat(failed.read).isNull()
    }

    @Test
    fun kernelReadsAreDueOnlyOnARootServerAndOtherwiseLeaveTheModeToSayWhy() {
        val outcome = KernelSnapshot.combine(null, null, PrivilegeMode.SHELL)
        assertThat(outcome.statuses).isEmpty()
        assertThat(outcome.rows).isNull()
        assertThat(outcome.mode).isEqualTo(PrivilegeMode.SHELL)
    }

    @Test
    fun readsSplitAcrossTwoConnectionsKeepNothing() {
        val filesRead = whole(root)
        val log = logRead(WorkerOrigin(0, 5))

        val outcome = KernelSnapshot.combine(filesRead, log, PrivilegeMode.ROOT)

        assertThat(outcome.statuses.values.toSet()).containsExactly(SourceStatus.FAILED)
        assertThat(outcome.rows).isNull()
        assertThat(outcome.mode).isEqualTo(PrivilegeMode.ROOT)
    }

    @Test
    fun aResultFromANonRootWorkerIsNotKernelData() {
        val shell = WorkerOrigin(2_000, 4)
        val outcome = KernelSnapshot.combine(whole(shell), logRead(shell), PrivilegeMode.SHELL)

        assertThat(outcome.statuses.values.toSet()).containsExactly(SourceStatus.FAILED)
        assertThat(outcome.rows).isNull()
        assertThat(outcome.mode).isEqualTo(PrivilegeMode.SHELL)
    }

    @Test
    fun aLogOfAnotherKernelBootThanTheFilesIsDropped() {
        val otherBoot = logRead(root, boot = "11111111-2222-3333-4444-555555555555")
        val outcome = KernelSnapshot.combine(whole(root), otherBoot, PrivilegeMode.ROOT)

        assertThat(outcome.statuses[KernelReader.S27]).isEqualTo(SourceStatus.FAILED)
        assertThat(outcome.statuses[KernelReader.S25]).isEqualTo(SourceStatus.OK)
        assertThat(outcome.rows?.read?.kmsgGaps).isNull()
        assertThat(outcome.rows?.read?.suspendStats).isNotNull()
    }

    @Test
    fun aLogAloneStillStoresUnderItsOwnBoot() {
        val outcome = KernelSnapshot.combine(KernelFilesRead.all(SourceStatus.SKIPPED), logRead(root), PrivilegeMode.ROOT)

        assertThat(outcome.rows?.read?.kernelBoot).isEqualTo(bootKey)
        assertThat(outcome.rows?.events).hasSize(1)
        assertThat(outcome.statuses[KernelReader.S25]).isEqualTo(SourceStatus.SKIPPED)
    }

    private fun whole(origin: WorkerOrigin) =
        files(listOf(file(KernelPaths.BOOT_ID, "$bootId\n")) + suspendStats() + wakeupReasons() + source(0) + "#end\n") {
            DumpResult.Complete(it, Route.WORKER, 0, 0, origin)
        }

    private fun logRead(
        origin: WorkerOrigin,
        boot: String = bootId,
    ): KernelLogResult {
        val text = "#begin boot=$boot after=10 first=11\n6,12,1000,-;Abort: Pending Wakeup Sources: wlan\n#end last=15 lost=0\n"
        return KernelReader.logOf(
            DumpResult.Complete(KernelLogParser.parse(ByteArrayInputStream(text.encodeToByteArray())), Route.WORKER, 0, 0, origin),
        )
    }

    private companion object {
        val SUSPEND_STEPS =
            listOf("freeze", "prepare", "suspend", "suspend_late", "suspend_noirq", "resume", "resume_early", "resume_noirq")
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.kernel

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.kernel.KernelPaths
import io.github.akane599.coulomb.core.model.kernel.WakeupCause
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class KernelFilesParserTest {
    private val boot = "8a5a3b74-1f2e-4d6c-9b7a-0c1d2e3f4a5b\n"
    private val stats = KernelPaths.SUSPEND_STATS
    private val reasons = KernelPaths.WAKEUP_REASONS
    private val wakeup = KernelPaths.WAKEUP_CLASS

    private fun file(
        path: String,
        body: String,
    ) = "@$path ${body.encodeToByteArray().size}\n$body\n"

    private fun stream(vararg frames: String): InputStream = ByteArrayInputStream(frames.joinToString("").encodeToByteArray())

    private val suspendFiles =
        listOf("success" to "12\n", "fail" to "1\n", "last_failed_dev" to "\n", "last_failed_errno" to "0\n", "last_failed_step" to "\n") +
            listOf("freeze", "prepare", "suspend", "suspend_late", "suspend_noirq", "resume", "resume_early", "resume_noirq").map {
                "failed_$it" to
                    "0\n"
            }

    private fun source(
        n: Int,
        name: String,
        wakeups: Long,
    ) = WakeupSourceParser.ATTRIBUTES.map { attr ->
        file(
            "$wakeup/wakeup$n/$attr",
            if (attr == "name") {
                "$name\n"
            } else if (attr == "wakeup_count") {
                "$wakeups\n"
            } else {
                "1\n"
            },
        )
    }

    private fun whole(): Array<String> =
        (
            listOf(file(KernelPaths.BOOT_ID, boot)) +
                suspendFiles.map { (name, body) -> file("$stats/$name", body) } +
                listOf(
                    file("$reasons/${KernelPaths.LAST_RESUME_REASON}", "170 qcom,glink\n"),
                    file("$reasons/${KernelPaths.LAST_SUSPEND_TIME}", "0.100000000 12.000000000\n"),
                ) +
                source(0, "eventpoll", 4) +
                source(7, "PowerManagerService.WakeLocks", 9)
        ).toTypedArray()

    @Test
    fun aWholeReadSplitsIntoItsThreeSources() {
        val (files, report) = KernelFilesParser.parse(stream(*whole(), "#end\n"))
        assertThat(report.complete).isTrue()
        val reads = KernelSources.split(files)
        assertThat(reads.bootKey).isEqualTo(0x8a5a3b741f2e4d6cuL.toLong())
        val suspend = checkNotNull(reads.suspendStats)
        assertThat(suspend.report.complete).isTrue()
        assertThat(suspend.value?.success).isEqualTo(12)
        val wake = checkNotNull(reads.lastWakeup)
        assertThat(wake.value?.reason).isInstanceOf(WakeupCause.Irqs::class.java)
        assertThat(wake.value?.sleepNs).isEqualTo(12_000_000_000L)
        val sources = checkNotNull(reads.wakeupSources)
        assertThat(sources.report.complete).isTrue()
        assertThat(sources.value.map { it.instance to it.stats.wakeupCount }).containsExactly(0 to 4L, 7 to 9L)
    }

    @Test
    fun aSourceTheStreamNeverTouchesIsNull() {
        val (files, _) = KernelFilesParser.parse(stream(file(KernelPaths.BOOT_ID, boot), file("$stats/success", "1\n"), "#end\n"))
        val reads = KernelSources.split(files)
        assertThat(reads.lastWakeup).isNull()
        assertThat(reads.wakeupSources).isNull()
        assertThat(reads.suspendStats?.report?.complete).isFalse()
    }

    @Test
    fun errorsFailTheirSourceOnly() {
        val (files, report) =
            KernelFilesParser.parse(
                stream(
                    file(KernelPaths.BOOT_ID, boot),
                    "!$stats 13\n",
                    "!$reasons/${KernelPaths.LAST_RESUME_REASON} 2\n",
                    *source(3, "x", 1).toTypedArray(),
                    "#end\n",
                ),
            )
        assertThat(report.complete).isTrue()
        val reads = KernelSources.split(files)
        assertThat(reads.suspendStats?.value).isNull()
        assertThat(reads.suspendStats?.report?.complete).isFalse()
        assertThat(reads.lastWakeup?.value).isNull()
        assertThat(reads.wakeupSources?.report?.complete).isTrue()
    }

    @Test
    fun aSourceThatVanishedMidReadIsLeftOutAndTheReadIsPartial() {
        val gone = source(5, "gone", 1).drop(3) + "!$wakeup/wakeup5/name 2\n"
        val (files, _) = KernelFilesParser.parse(stream(*source(1, "kept", 2).toTypedArray(), *gone.toTypedArray(), "#end\n"))
        val sources = WakeupSourcesParser.parse(files)
        assertThat(sources.value.map { it.instance }).containsExactly(1)
        assertThat(sources.report.complete).isFalse()
        val unlisted = WakeupSourcesParser.parse(KernelFilesParser.parse(stream("!$wakeup 13\n", "#end\n")).value)
        assertThat(unlisted.value).isEmpty()
        assertThat(unlisted.report.complete).isFalse()
    }

    @Test
    fun aCutStreamKeepsWholeFramesAndIsIncomplete() {
        val all = whole()
        val text = all.joinToString("")
        val cut = text.substring(0, text.length - 3)
        val (files, report) = KernelFilesParser.parse(ByteArrayInputStream(cut.encodeToByteArray()))
        assertThat(report.complete).isFalse()
        assertThat(files.files).containsKey("$stats/success")
        assertThat(files.files).doesNotContainKey("$wakeup/wakeup7/prevent_suspend_time_ms")
        val noEnd = KernelFilesParser.parse(stream(*all))
        assertThat(noEnd.report.complete).isFalse()
        assertThat(noEnd.value.files).containsKey("$wakeup/wakeup7/prevent_suspend_time_ms")
    }

    @Test
    fun aStreamThatFailsMidwayIsIncomplete() {
        val head = whole().take(3).joinToString("").encodeToByteArray()
        val failing =
            object : InputStream() {
                private var i = 0

                override fun read(): Int = if (i < head.size) head[i++].toInt() and 0xff else throw IOException("worker died")
            }
        val (files, report) = KernelFilesParser.parse(failing)
        assertThat(report.complete).isFalse()
        assertThat(files.files).hasSize(3)
    }

    @Test
    fun badFramesStopTheRead() {
        assertThat(KernelFilesParser.parse(stream("@/etc/passwd 3\nabc\n", "#end\n")).report.complete).isFalse()
        assertThat(KernelFilesParser.parse(stream("@$stats/success x\n1\n", "#end\n")).report.complete).isFalse()
        assertThat(KernelFilesParser.parse(stream("@$stats/success 1\n12\n", "#end\n")).report.complete).isFalse()
        assertThat(KernelFilesParser.parse(stream("?what\n", "#end\n")).report.complete).isFalse()
        assertThat(KernelFilesParser.parse(stream("#end\n", "junk")).report.complete).isFalse()
        assertThat(
            KernelFilesParser.parse(stream(file("$stats/success", "1\n"), file("$stats/success", "2\n"), "#end\n")).report.complete,
        ).isFalse()
    }
}

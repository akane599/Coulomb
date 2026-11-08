// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.worker

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.kernel.KernelPaths
import io.github.akane599.coulomb.core.privileged.system.Sysfs
import org.junit.Test
import java.io.ByteArrayOutputStream

class KernelStreamsTest {
    private val boot = "8a5a3b74-1f2e-4d6c-9b7a-0c1d2e3f4a5b"

    private fun copy(
        afterSeq: Long?,
        vararg records: String,
        maxKept: Int = 100,
        testMarkers: Boolean = false,
    ): List<String> {
        val out = ByteArrayOutputStream()
        val copier = KernelLogCopier(boot, afterSeq, maxKept, testMarkers, out)
        var stopped = false
        for (r in records) {
            val bytes = r.toByteArray()
            if (!copier.record(bytes, bytes.size)) {
                stopped = true
                break
            }
        }
        copier.finish(reachedEnd = !stopped)
        return out.toString(Charsets.US_ASCII).lines().dropLast(1)
    }

    @Test
    fun keepsOnlyAllowlistedKernelRecordsAndTheirFirstLine() {
        val lines =
            copy(
                null,
                "6,10,100,-;PM: suspend entry (deep)\n SUBSYSTEM=platform\n",
                "6,11,101,-;usb 1-1: new device serial ABC123\n",
                "14,12,102,-;Resume caused by IRQ 9, forged\n",
                "6,13,103,-;Resume caused by IRQ 170, qcom,glink\n",
            )
        assertThat(lines)
            .containsExactly(
                "#begin boot=$boot after=- first=10",
                "6,10,100,-;PM: suspend entry (deep)",
                "6,13,103,-;Resume caused by IRQ 170, qcom,glink",
                "#end last=13 lost=0",
            ).inOrder()
    }

    @Test
    fun skipsToTheCursorAndReportsWhatTheRingLost() {
        val lines = copy(99, "6,98,1,-;PM: suspend exit\n", "6,140,2,-;PM: suspend exit\n", "6,141,3,-;x\n", "6,150,4,-;PM: suspend exit\n")
        assertThat(lines)
            .containsExactly(
                "#begin boot=$boot after=99 first=140",
                "#gap from=100 to=139",
                "6,140,2,-;PM: suspend exit",
                "#gap from=142 to=149",
                "6,150,4,-;PM: suspend exit",
                "#end last=150 lost=48",
            ).inOrder()
    }

    @Test
    fun nothingPastTheCursorIsAnEmptyWholeRead() {
        assertThat(copy(20, "6,19,1,-;PM: suspend exit\n", "6,20,2,-;PM: suspend exit\n"))
            .containsExactly("#begin boot=$boot after=20 first=-", "#end last=- lost=0")
            .inOrder()
    }

    @Test
    fun stopsAtTheCapWithoutTheTrailerSoTheReadIsCutShort() {
        val lines =
            copy(null, "6,1,1,-;PM: suspend exit\n", "6,2,2,-;x\n", "6,3,3,-;PM: suspend exit\n", "6,4,4,-;PM: suspend exit\n", maxKept = 2)
        assertThat(lines)
            .containsExactly("#begin boot=$boot after=- first=1", "6,1,1,-;PM: suspend exit", "6,3,3,-;PM: suspend exit")
            .inOrder()
    }

    @Test
    fun testMarkersPassOnlyWhenAllowedAndMarked() {
        val marker = "12,5,1,-;Resume caused by IRQ 999, ${KernelLogCopier.TEST_MARKER}1\n"
        val unmarked = "12,6,2,-;Resume caused by IRQ 999, other\n"
        assertThat(copy(null, marker, unmarked, testMarkers = true)).hasSize(3)
        assertThat(copy(null, marker, unmarked, testMarkers = false)).hasSize(2)
    }

    @Test
    fun malformedRecordsAreSkippedAndSequenceGoingBackIsIgnored() {
        val lines = copy(null, "garbage\n", "6,5,1,-;PM: suspend exit\n", "6,4,2,-;PM: suspend exit\n", "6,6,3,-;PM: suspend exit\n")
        assertThat(lines)
            .containsExactly(
                "#begin boot=$boot after=- first=5",
                "6,5,1,-;PM: suspend exit",
                "6,6,3,-;PM: suspend exit",
                "#end last=6 lost=0",
            ).inOrder()
    }

    @Test
    fun aDirectoryPastTheListingCapReadsAsNotListable() {
        val out = ByteArrayOutputStream()
        val list: (String) -> Sysfs.Listing = { Sysfs.Listing.Ok(listOf("wakeup0", "wakeup1"), truncated = true) }
        KernelFileWriter({ Sysfs.Read.Ok("x\n".toByteArray()) }, list, out)
            .write(KernelPaths.READ_SUSPEND_STATS or KernelPaths.READ_WAKEUP_SOURCES)
        val text = out.toString(Charsets.US_ASCII)
        assertThat(text).contains("!${KernelPaths.SUSPEND_STATS} 75\n")
        assertThat(text).contains("!${KernelPaths.WAKEUP_CLASS} 75\n")
        assertThat(text).doesNotContain("wakeup0")
    }

    @Test
    fun theFileWriterFramesEveryFileAndError() {
        val files =
            mapOf(
                KernelPaths.BOOT_ID to "$boot\n",
                "${KernelPaths.SUSPEND_STATS}/success" to "3\n",
                "${KernelPaths.WAKEUP_REASONS}/${KernelPaths.LAST_RESUME_REASON}" to "",
                "${KernelPaths.WAKEUP_CLASS}/wakeup4/name" to "eventpoll\n",
            )
        val read: (String) -> Sysfs.Read = { p -> files[p]?.let { Sysfs.Read.Ok(it.toByteArray()) } ?: Sysfs.Read.Failed(2, "") }
        val list: (String) -> Sysfs.Listing = { p ->
            when (p) {
                KernelPaths.SUSPEND_STATS -> Sysfs.Listing.Ok(listOf("success"))
                KernelPaths.WAKEUP_CLASS -> Sysfs.Listing.Ok(listOf("wakeup4", "uevent", "wakeup../x"))
                else -> Sysfs.Listing.Failed(13, "")
            }
        }
        val out = ByteArrayOutputStream()
        KernelFileWriter(
            read,
            list,
            out,
        ).write(KernelPaths.READ_SUSPEND_STATS or KernelPaths.READ_WAKEUP_REASONS or KernelPaths.READ_WAKEUP_SOURCES)
        val text = out.toString(Charsets.US_ASCII)
        assertThat(text).startsWith("@${KernelPaths.BOOT_ID} 37\n$boot\n\n@${KernelPaths.SUSPEND_STATS}/success 2\n3\n\n")
        assertThat(text).contains("@${KernelPaths.WAKEUP_REASONS}/${KernelPaths.LAST_RESUME_REASON} 0\n\n")
        assertThat(text).contains("!${KernelPaths.WAKEUP_REASONS}/${KernelPaths.LAST_SUSPEND_TIME} 2\n")
        assertThat(text).contains("@${KernelPaths.WAKEUP_CLASS}/wakeup4/name 10\neventpoll\n\n")
        assertThat(text).contains("!${KernelPaths.WAKEUP_CLASS}/wakeup4/active_count 2\n")
        assertThat(text).doesNotContain("uevent")
        assertThat(text).doesNotContain("wakeup..")
        assertThat(text).endsWith("#end\n")
    }
}

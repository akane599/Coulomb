// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.parsing.Fixtures
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * SPEC 16.5: parsing a 20 MB dump peaks under 64 MB of heap (audit TEST-003). The largest dump
 * Coulomb parses is the history, so the dump is the phone fixture's history records repeated to
 * 20 MB, streamed and never held whole. It runs in a JVM of its own with a 64 MB heap, so a
 * parser that kept more than that alive fails with OutOfMemoryError. The run must consume every
 * byte and give every record, so a parse that stops early cannot pass. A JVM heap stands in for
 * ART's; the measured run on the phone is in docs/progress.md.
 */
class HistoryParseMemoryTest {
    @Test
    fun aTwentyMegabyteHistoryParsesWithinTheHeapBudget() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process =
            ProcessBuilder(
                java,
                "-Xmx${HEAP_MB}m",
                "-Dcoulomb.fixtures=${System.getProperty("coulomb.fixtures")}",
                "-cp",
                System.getProperty("java.class.path"),
                HistoryParseRun::class.java.name,
            ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertThat(process.waitFor(RUN_TIMEOUT_S, TimeUnit.SECONDS)).isTrue()
        assertThat(process.exitValue()).isEqualTo(0)
        val result =
            output
                .lineSequence()
                .mapNotNull { Regex("""^(\w+)=(\d+)$""").find(it.trim()) }
                .associate { it.groupValues[1] to it.groupValues[2].toLong() }
        assertThat(result["streamBytes"]).isAtLeast(TWENTY_MB)
        assertThat(result["bytesRead"]).isEqualTo(result["streamBytes"])
        assertThat(result["complete"]).isEqualTo(1)
        assertThat(result["records"]).isAtLeast(checkNotNull(result["expectedRecords"]))
        println("SPEC 16.5 history parse: $result")
    }

    private companion object {
        const val HEAP_MB = 64
        const val TWENTY_MB = 20L * 1024 * 1024
        const val RUN_TIMEOUT_S = 120L
    }
}

/** The parse [HistoryParseMemoryTest] runs in its own JVM; prints `name=value` lines. */
internal object HistoryParseRun {
    private const val TWENTY_MB = 20L * 1024 * 1024

    @JvmStatic
    fun main(args: Array<String>) {
        val lines = Fixtures.bytes(Fixtures.PHONE, "batterystats.checkin.txt").decodeToString().lines()
        val first = lines.indexOfFirst { it.startsWith("9,h,") }
        val head = lines.subList(0, first).filter { it.startsWith("9,0,i,vers") || it.startsWith("9,hsp,") }
        val records = lines.drop(first).takeWhile { it.startsWith("9,h,") }
        val block = (records.joinToString("\n") + "\n").encodeToByteArray()
        // One pass over the block gives this many records; every repeat must give as many again.
        val perBlock = parse(stream(head, block, 1)).value.recordCount
        val repeats = ((TWENTY_MB + block.size - 1) / block.size).toInt()
        val streamBytes = (head.joinToString("\n") + "\n").length + repeats.toLong() * block.size + NEXT.length

        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        pools.forEach { it.resetPeakUsage() }
        val parsed = parse(stream(head, block, repeats))
        // Each pool's own peak, summed: an upper bound, since the pools peak at different times.
        val peak = pools.sumOf { it.peakUsage.used }
        println("streamBytes=$streamBytes")
        println("bytesRead=${parsed.report.bytesRead}")
        println("complete=${if (parsed.report.complete) 1 else 0}")
        println("records=${parsed.value.recordCount}")
        println("expectedRecords=${(repeats - 1) * perBlock}")
        println("peakPoolsSumBytes=$peak")
    }

    /** The count only, as the collector keeps no batch past the next one. */
    private fun parse(input: InputStream) = BatteryHistoryParser.parse(input, cursorMs = 0) { }

    private fun stream(
        head: List<String>,
        block: ByteArray,
        repeats: Int,
    ): InputStream {
        val parts =
            listOf(ByteArrayInputStream((head.joinToString("\n") + "\n").encodeToByteArray())) +
                List(repeats) { ByteArrayInputStream(block) } +
                ByteArrayInputStream(NEXT.encodeToByteArray())
        return SequenceInputStream(Collections.enumeration(parts))
    }

    /** Far past any record, so every record is placed. */
    private const val NEXT = "NEXT: 9000000000000\n"
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.history.BatteryHistory
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.parsing.batterystats.BatteryHistoryParser
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.PartialReason
import io.github.akane599.coulomb.core.privileged.Route
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Audit SEC-004: what a history read hands the ingest, by how its transfer ended. After lost
 * record boundaries the parser keeps the last line's `NEXT:`, and a pipe closed by a timeout can
 * end on a line boundary right after one an app put in its wakelock tag. Only a dump that ended
 * cleanly has the platform's `NEXT:` on its last line.
 */
class HeavyReaderHistoryTest {
    private val head = "9,0,i,vers,36,216,A,A\n9,h,0:RESET:TIME:1000\n9,h,0,Bl=80,+r\n"

    /** Ends on a line boundary inside the app's tag: to the parser, a plain end of stream. */
    private val cutInsideTheTag = head + "9,h,10,+S,w=u0a5:\"x\nNEXT: 9000000000000000\n"

    /** The same desync, read to the dump's real last line. */
    private val readToTheEnd = head + "9,h,10,+S,w=u0a5:\"a\"b\"\n9,h,0,Bl=1,+S\nNEXT: 424242\n9,h,5,-r\nNEXT: 1016\n"

    private fun parsed(text: String): Pair<Parsed<BatteryHistory>, BoundedRecords> {
        val records = BoundedRecords(1_000)
        return BatteryHistoryParser.parse(ByteArrayInputStream(text.encodeToByteArray()), cursorMs = 1_000) { records.add(it) } to records
    }

    private fun partial(
        value: Parsed<BatteryHistory>,
        reason: PartialReason,
    ) = DumpResult.Partial(value, Route.WORKER, bytes = 100, reason = reason, detail = null)

    @Test
    fun aCutReadAfterADesyncKeepsNoNext() {
        val (value, records) = parsed(cutInsideTheTag)
        // The parser alone cannot tell this end from the dump's.
        assertThat(value.value.nextCursorMs).isEqualTo(9_000_000_000_000_000L)
        assertThat(value.value.firstRecordMs).isNull()

        val cuts = listOf(PartialReason.TIMEOUT, PartialReason.WORKER_DIED, PartialReason.READ_ERROR, PartialReason.PRODUCER_FAILED)
        for (reason in cuts) {
            val read = HeavyReader.historyOf(partial(value, reason), records)
            assertThat(read.status).isEqualTo(SourceStatus.PARTIAL)
            assertThat(read.history?.nextCursorMs).isNull()
        }
    }

    @Test
    fun aCleanlyEndedDesyncReadKeepsItsLastLineNext() {
        val (value, records) = parsed(readToTheEnd)
        assertThat(value.value.firstRecordMs).isNull()

        val truncated = HeavyReader.historyOf(partial(value, PartialReason.TRUNCATED), records)
        assertThat(truncated.history?.nextCursorMs).isEqualTo(1016L)
        val complete = HeavyReader.historyOf(DumpResult.Complete(value, Route.WORKER, bytes = 100, durationMs = 5), records)
        assertThat(complete.history?.nextCursorMs).isEqualTo(1016L)
    }

    /** A placed read cut short keeps what the parser gave: without a desync, `NEXT:` is only ever the platform's line. */
    @Test
    fun aPlacedReadIsPassedOnAsItIs() {
        val (value, records) = parsed(head + "9,h,5,-r\nNEXT: 1016\n")
        assertThat(value.value.firstRecordMs).isNotNull()

        val read = HeavyReader.historyOf(partial(value, PartialReason.TIMEOUT), records)
        assertThat(read.history).isEqualTo(value.value)
        assertThat(read.records).hasSize(records.records.size)
    }
}

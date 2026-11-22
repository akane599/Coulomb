// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.history.HistoryIngest
import io.github.akane599.coulomb.core.collect.history.ReadInput
import io.github.akane599.coulomb.core.model.HistoryGapReason
import io.github.akane599.coulomb.core.model.history.BatteryHistory
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import io.github.akane599.coulomb.core.model.history.HistoryRecord
import org.junit.Test

/** Audit PERF-006: a history read too long to hold costs no memory and becomes a gap, never a crash loop. */
class BoundedRecordsTest {
    private val first = 1_000_000_000L

    private fun batch(
        from: Int,
        size: Int,
    ) = (from until from + size).map { HistoryRecord(it * 1_000L, HistoryKind.SCREEN, HistoryChange.START) }

    private fun history(count: Int) =
        BatteryHistory(
            header = null,
            nextCursorMs = first + count * 1_000L,
            firstRecordMs = first,
            recordCount = count.toLong(),
            gapBeforeFirstRecord = false,
        )

    @Test
    fun aReadWithinTheBoundKeepsEveryRecordAsRead() {
        val records = BoundedRecords(max = 1_024)
        records.add(batch(0, 512))
        records.add(batch(512, 512))

        assertThat(records.tooLong).isFalse()
        assertThat(records.records).hasSize(1_024)
        assertThat(records.frame(history(1_024))).isEqualTo(history(1_024))
    }

    @Test
    fun aReadPastTheBoundKeepsNoneAndItsSpanBecomesAGap() {
        val records = BoundedRecords(max = 1_000)
        records.add(batch(0, 512))
        records.add(batch(512, 512))
        records.add(batch(1_024, 512))

        assertThat(records.tooLong).isTrue()
        assertThat(records.records).isEmpty()
        val framed = checkNotNull(records.frame(history(1_536)))
        assertThat(framed.firstRecordMs).isNull()

        val result =
            HistoryIngest.ingest(
                ReadInput(
                    records = records.records,
                    history = framed,
                    cursorMs = 0,
                    previous = null,
                    bootSeq = 1,
                    storedOffsetMs = null,
                    wallMinusElapsedMs = 1_700_000_000_000L,
                    nowElapsedMs = first + 2_000_000L,
                    clockChanged = false,
                ),
            )
        assertThat(result.events).isEmpty()
        assertThat(result.gaps.single().reason).isEqualTo(HistoryGapReason.READ_DESYNC)
        // The next read goes on after this one instead of reading it again.
        assertThat(result.nextCursorMs).isEqualTo(first + 1_536_000L)
    }
}

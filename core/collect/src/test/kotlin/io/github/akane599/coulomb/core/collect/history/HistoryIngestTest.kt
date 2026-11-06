// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.history

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.HistoryGapReason
import io.github.akane599.coulomb.core.model.history.BatteryHistory
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryChange.SET
import io.github.akane599.coulomb.core.model.history.HistoryChange.START
import io.github.akane599.coulomb.core.model.history.HistoryChange.STOP
import io.github.akane599.coulomb.core.model.history.HistoryKind
import io.github.akane599.coulomb.core.model.history.HistoryKind.BATTERY_CHARGE
import io.github.akane599.coulomb.core.model.history.HistoryKind.BATTERY_LEVEL
import io.github.akane599.coulomb.core.model.history.HistoryKind.BOOT
import io.github.akane599.coulomb.core.model.history.HistoryKind.CELLULAR_HIGH_TX_POWER
import io.github.akane599.coulomb.core.model.history.HistoryKind.OVERFLOW
import io.github.akane599.coulomb.core.model.history.HistoryKind.PLUGGED
import io.github.akane599.coulomb.core.model.history.HistoryKind.SCREEN
import io.github.akane599.coulomb.core.model.history.HistoryKind.WALL_CLOCK
import io.github.akane599.coulomb.core.model.history.HistoryRecord
import org.junit.Test

class HistoryIngestTest {
    /** The current boot's timeshift: monotonic = elapsed + [shift]. */
    private val shift = 900_000_000L
    private val wallMinusElapsed = 1_700_000_000_000L

    /** Monotonic time of the read's first record. */
    private val first = 1_000_000_000L

    private fun rec(
        offsetMs: Long,
        kind: HistoryKind,
        change: HistoryChange = SET,
        value: Long? = null,
        baseline: Boolean = false,
    ) = HistoryRecord(offsetMs, kind, change, value, baseline = baseline)

    /** A `TIME` record at [offsetMs] with the wall time that matches [timeshift]. */
    private fun time(
        offsetMs: Long,
        timeshift: Long = shift,
    ) = rec(offsetMs, WALL_CLOCK, value = first + offsetMs - timeshift + wallMinusElapsed)

    private fun input(
        records: List<HistoryRecord>,
        previous: ReadEnd? = null,
        bootSeq: Int = 5,
        stored: Long? = null,
        cursor: Long = 0,
        gapBefore: Boolean = false,
        firstMs: Long? = first,
        clockChanged: Boolean = false,
        nowElapsedMs: Long = first + (records.maxOfOrNull { it.offsetMs } ?: 0) - shift + 1_000,
    ) = ReadInput(
        records = records,
        history =
            BatteryHistory(
                header = null,
                nextCursorMs = first + (records.maxOfOrNull { it.offsetMs } ?: 0) + 1,
                firstRecordMs = firstMs,
                recordCount = records.size.toLong(),
                gapBeforeFirstRecord = gapBefore,
            ),
        cursorMs = cursor,
        previous = previous,
        bootSeq = bootSeq,
        storedOffsetMs = stored,
        wallMinusElapsedMs = wallMinusElapsed,
        nowElapsedMs = nowElapsedMs,
        clockChanged = clockChanged,
    )

    private fun elapsedNs(offsetMs: Long) = (first + offsetMs - shift) * 1_000_000

    @Test
    fun theFirstReadKeepsOnlyTheCurrentBootAndFindsItsOffset() {
        val records =
            listOf(
                rec(0, SCREEN, START, baseline = true),
                rec(500, BATTERY_LEVEL, value = 80),
                rec(10_000, BOOT),
                time(10_000),
                rec(10_000, BATTERY_LEVEL, value = 79, baseline = true),
                rec(12_000, SCREEN, START),
            )
        val result = HistoryIngest.ingest(input(records))
        assertThat(result.newOffsetMs).isEqualTo(shift)
        assertThat(result.events.map { it.type }).containsExactly(BOOT, WALL_CLOCK, BATTERY_LEVEL, SCREEN).inOrder()
        assertThat(result.events.map { it.bootSeq }.toSet()).containsExactly(5)
        assertThat(result.events.last().elapsedNs).isEqualTo(elapsedNs(12_000))
        assertThat(result.gaps).isEmpty()
        assertThat(result.nextCursorMs).isEqualTo(first + 12_001)
        assertThat(result.end?.state?.values).containsExactly(SCREEN, 1L, BATTERY_LEVEL, 79L)
        assertThat(result.end?.lastElapsedNs).isEqualTo(elapsedNs(12_000))
    }

    @Test
    fun anIncrementalReadUsesItsOwnPairingOrTheStoredOffset() {
        val previous = ReadEnd(5, shift, first - 1, (first - 1 - shift) * 1_000_000, HistoryState(mapOf(SCREEN to 1L)))
        val restated = listOf(time(0), rec(0, SCREEN, START, baseline = true), rec(3_000, SCREEN, STOP))
        val withPairing = HistoryIngest.ingest(input(restated, previous, stored = shift, cursor = first))
        assertThat(withPairing.newOffsetMs).isNull()
        assertThat(withPairing.gaps).isEmpty()
        assertThat(withPairing.events.last().elapsedNs).isEqualTo(elapsedNs(3_000))
        assertThat(withPairing.transitions).containsExactly(SCREEN)
        // No pairing in the read: the stored offset places it.
        val bare = listOf(rec(0, SCREEN, START, baseline = true), rec(3_000, SCREEN, STOP))
        assertThat(
            HistoryIngest
                .ingest(input(bare, previous, stored = shift, cursor = first))
                .events
                .last()
                .elapsedNs,
        ).isEqualTo(elapsedNs(3_000))
    }

    @Test
    fun withoutAnyOffsetNothingIsStoredAndTheCursorStays() {
        val result = HistoryIngest.ingest(input(listOf(rec(0, SCREEN, START, baseline = true), rec(100, BATTERY_LEVEL, value = 50))))
        assertThat(result.events).isEmpty()
        assertThat(result.nextCursorMs).isNull()
        assertThat(result.end).isNull()
    }

    @Test
    fun aPairingThatPutsRecordsInTheFutureOrSpansAClockChangeIsRefused() {
        // Now is an hour before the time the pairing gives the last record: the pairing is wrong.
        val records = listOf(rec(0, BOOT), time(0), rec(1_000, SCREEN, START))
        val ahead = input(records, nowElapsedMs = first + 1_000 - shift - 3_600_000)
        assertThat(HistoryIngest.ingest(ahead).nextCursorMs).isNull()
        assertThat(HistoryIngest.ingest(input(records, clockChanged = true)).nextCursorMs).isNull()
        // A START in the read: the stored offset belongs to the old timeshift and is not used.
        assertThat(HistoryIngest.ingest(input(records, stored = shift, clockChanged = true)).nextCursorMs).isNull()
    }

    @Test
    fun droppedKindsAreNotStored() {
        val records =
            listOf(
                time(0),
                rec(0, SCREEN, START, baseline = true),
                rec(10, CELLULAR_HIGH_TX_POWER, START),
                rec(20, BATTERY_CHARGE, value = 4000),
            )
        val result = HistoryIngest.ingest(input(records, stored = shift))
        assertThat(result.events.map { it.type }).containsExactly(WALL_CLOCK, SCREEN)
    }

    @Test
    fun aRestatedStateThatDiffersIsAGapNotATransition() {
        // The previous read ended with the screen on; this read restates it off: the change was
        // merged into the previous read's last record.
        val lastMono = first - 400
        val previous = ReadEnd(5, shift, lastMono, (lastMono - shift) * 1_000_000, HistoryState(mapOf(SCREEN to 1L, BATTERY_LEVEL to 70L)))
        val records = listOf(time(0), rec(0, BATTERY_LEVEL, value = 70, baseline = true), rec(2_000, PLUGGED, START))
        val result = HistoryIngest.ingest(input(records, previous, stored = shift, cursor = first))
        val gap = result.gaps.single()
        assertThat(gap.reason).isEqualTo(HistoryGapReason.STATE_MISMATCH)
        assertThat(gap.startMonotonicMs).isEqualTo(lastMono)
        assertThat(gap.endMonotonicMs).isEqualTo(first)
        // The same restated state as stored: no gap.
        val same = listOf(time(0), rec(0, SCREEN, START, baseline = true), rec(0, BATTERY_LEVEL, value = 70, baseline = true))
        assertThat(HistoryIngest.ingest(input(same, previous, stored = shift, cursor = first)).gaps).isEmpty()
        // Not compared without a stored state, as after Coulomb's process started.
        assertThat(HistoryIngest.ingest(input(records, previous.copy(state = null), stored = shift, cursor = first)).gaps).isEmpty()
    }

    @Test
    fun aRebootBetweenReadsPlacesEachPartInItsOwnBoot() {
        val oldShift = 950_000_000L
        val previous = ReadEnd(4, oldShift, first - 1, (first - 1 - oldShift) * 1_000_000, null)
        val records = listOf(rec(0, BATTERY_LEVEL, value = 40), rec(60_000, BOOT), time(60_000), rec(61_000, SCREEN, START))
        val result = HistoryIngest.ingest(input(records, previous, bootSeq = 5, cursor = first))
        assertThat(result.newOffsetMs).isEqualTo(shift)
        assertThat(result.events.first().bootSeq).isEqualTo(4)
        assertThat(result.events.first().elapsedNs).isEqualTo((first - oldShift) * 1_000_000)
        assertThat(
            result.events
                .drop(1)
                .map { it.bootSeq }
                .toSet(),
        ).containsExactly(5)
        assertThat(result.gaps).isEmpty()
    }

    @Test
    fun aBootCoulombDidNotRunInIsAGap() {
        val oldShift = 950_000_000L
        val previous = ReadEnd(4, oldShift, first - 1, (first - 1 - oldShift) * 1_000_000, null)
        val records =
            listOf(
                rec(0, BATTERY_LEVEL, value = 40),
                rec(60_000, BOOT),
                time(60_000, timeshift = 970_000_000L),
                rec(61_000, SCREEN, START),
                rec(90_000, BOOT),
                time(90_000),
                rec(91_000, SCREEN, START),
            )
        val result = HistoryIngest.ingest(input(records, previous, bootSeq = 5, cursor = first))
        assertThat(result.events.map { it.bootSeq }).containsExactly(4, 5, 5, 5).inOrder()
        val gap = result.gaps.single()
        assertThat(gap.reason).isEqualTo(HistoryGapReason.CURSOR_SKIPPED)
        assertThat(gap.startBootSeq).isEqualTo(4)
        assertThat(gap.startMonotonicMs).isEqualTo(first)
        assertThat(gap.endBootSeq).isEqualTo(5)
        assertThat(gap.endMonotonicMs).isEqualTo(first + 90_000)
    }

    @Test
    fun recordsLostBeforeTheFirstAreAGap() {
        val previous = ReadEnd(5, shift, first - 50_000, (first - 50_000 - shift) * 1_000_000, null)
        val records = listOf(time(0), rec(0, SCREEN, START, baseline = true))
        val gap = HistoryIngest.ingest(input(records, previous, stored = shift, cursor = first - 49_999, gapBefore = true)).gaps.single()
        assertThat(gap.reason).isEqualTo(HistoryGapReason.CURSOR_SKIPPED)
        assertThat(gap.startMonotonicMs).isEqualTo(first - 50_000)
        assertThat(gap.endMonotonicMs).isEqualTo(first)
    }

    @Test
    fun anUnplacedReadIsAGapAndTheNextReadGoesOn() {
        val previous = ReadEnd(5, shift, first - 1, (first - 1 - shift) * 1_000_000, HistoryState(emptyMap()))
        val result =
            HistoryIngest.ingest(
                input(listOf(rec(0, SCREEN, START), rec(5_000, SCREEN, STOP)), previous, stored = shift, firstMs = null, cursor = first),
            )
        assertThat(result.events).isEmpty()
        assertThat(result.gaps.single().reason).isEqualTo(HistoryGapReason.READ_DESYNC)
        assertThat(result.nextCursorMs).isEqualTo(first + 5_001)
        assertThat(result.end?.state).isNull()
    }

    @Test
    fun anOverflowIsAGapToTheNextRecord() {
        val records = listOf(time(0), rec(0, SCREEN, START, baseline = true), rec(1_000, OVERFLOW), rec(9_000, SCREEN, STOP))
        val gap = HistoryIngest.ingest(input(records, stored = shift)).gaps.single()
        assertThat(gap.reason).isEqualTo(HistoryGapReason.OVERFLOW)
        assertThat(gap.startMonotonicMs).isEqualTo(first + 1_000)
        assertThat(gap.endMonotonicMs).isEqualTo(first + 9_000)
    }

    @Test
    fun aReadWithNothingNewKeepsTheCursor() {
        val previous = ReadEnd(5, shift, first - 1, 0, null)
        val empty =
            ReadInput(
                records = emptyList(),
                history = BatteryHistory(null, nextCursorMs = first, firstRecordMs = null, recordCount = 0, gapBeforeFirstRecord = false),
                cursorMs = first,
                previous = previous,
                bootSeq = 5,
                storedOffsetMs = shift,
                wallMinusElapsedMs = wallMinusElapsed,
                nowElapsedMs = 1,
                clockChanged = false,
            )
        val result = HistoryIngest.ingest(empty)
        assertThat(result.nextCursorMs).isEqualTo(first)
        assertThat(result.end).isEqualTo(previous)
        assertThat(result.gaps).isEmpty()
    }
}

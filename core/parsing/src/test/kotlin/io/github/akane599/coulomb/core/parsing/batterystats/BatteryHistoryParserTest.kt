// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.model.history.BatteryHistory
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import io.github.akane599.coulomb.core.model.history.HistoryRecord
import io.github.akane599.coulomb.core.model.parse.ParseReport
import io.github.akane599.coulomb.core.parsing.Fixtures
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class BatteryHistoryParserTest {
    private class Read(
        val history: BatteryHistory,
        val report: ParseReport,
        val records: List<HistoryRecord>,
        val batches: List<Int>,
    ) {
        /** Monotonic time of each record; only when the read placed them. */
        fun at(r: HistoryRecord): Long = checkNotNull(history.firstRecordMs) + r.offsetMs
    }

    private fun read(
        bytes: ByteArray,
        cursor: Long = 0,
    ): Read {
        val records = ArrayList<HistoryRecord>()
        val batches = ArrayList<Int>()
        val (history, report) =
            BatteryHistoryParser.parse(ByteArrayInputStream(bytes), cursor) {
                batches += it.size
                records += it
            }
        return Read(history, report, records, batches)
    }

    private fun read(
        text: String,
        cursor: Long = 0,
    ) = read(text.encodeToByteArray(), cursor)

    private fun capture(name: String) = Fixtures.bytes(Fixtures.AVD, "history-checkin/$name.txt")

    private fun cursor(name: String) =
        Fixtures
            .file(Fixtures.AVD, "history-checkin/$name.cursor")
            .readText()
            .trim()
            .toLong()

    /** The history part of a plain `-c` dump, ended as `--history-start` ends it. */
    private fun historyOnly(
        device: String,
        next: Long,
    ): ByteArray {
        val text = Fixtures.file(device, "batterystats.checkin.txt").readText()
        val kept = text.lineSequence().filter { it.startsWith("9,0,i,vers,") || it.startsWith("9,h") }
        return (kept.joinToString("\n") + "\nNEXT: $next\n").encodeToByteArray()
    }

    private fun Read.assertWhole() {
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.unknown).isEmpty()
        assertThat(report.complete).isTrue()
    }

    // Expected values below come from tools outside this parser: the NEXT line, and the steps
    // summed with a script over the same files.

    @Test
    fun aReadFromZeroPlacesEveryRecord() {
        val read = read(capture("from-0"))
        read.assertWhole()
        assertThat(read.history.header?.parcelVersion).isEqualTo(216L)
        assertThat(read.history.nextCursorMs).isEqualTo(2_729_920L)
        assertThat(read.history.recordCount).isEqualTo(775L)
        assertThat(read.history.firstRecordMs).isEqualTo(17_825L)
        assertThat(read.history.gapBeforeFirstRecord).isFalse()
        val first = read.records.first()
        assertThat(first).isEqualTo(HistoryRecord(0, HistoryKind.RESET, HistoryChange.SET, 1_793_936_815_213L))
        // The record after RESET restates the state, and so does the one after each START (the
        // emulator rebooted twice in this history).
        val restarts = read.records.filter { it.kind == HistoryKind.RESET || it.kind == HistoryKind.BOOT }.map { it.offsetMs }
        assertThat(restarts).hasSize(3)
        val baseline = read.records.filter { it.baseline }
        val baselineAt = baseline.map { it.offsetMs }.distinct()
        // One baseline per restart: the first state record at or after it, before the next one.
        assertThat(baselineAt).hasSize(restarts.size)
        for ((i, at) in baselineAt.withIndex()) {
            assertThat(at).isAtLeast(restarts[i])
            if (i + 1 < restarts.size) assertThat(at).isLessThan(restarts[i + 1])
        }
        assertThat(baseline).contains(HistoryRecord(0, HistoryKind.BATTERY_LEVEL, HistoryChange.SET, 100, baseline = true))
        assertThat(baseline).contains(HistoryRecord(0, HistoryKind.BATTERY_STATUS, HistoryChange.SET, 2, baseline = true))
        assertThat(baseline).contains(HistoryRecord(0, HistoryKind.BATTERY_PLUG, HistoryChange.SET, 1, baseline = true))
        assertThat(baseline).contains(HistoryRecord(0, HistoryKind.SCREEN, HistoryChange.START, baseline = true))
        // The last record is one before NEXT.
        assertThat(read.at(read.records.last())).isEqualTo(2_729_919L)
    }

    @Test
    fun aReadFromACursorStartsWithAWallClockAndRestatesTheState() {
        val read = read(capture("from-mid"), cursor("from-mid"))
        read.assertWhole()
        assertThat(read.history.recordCount).isEqualTo(418L)
        assertThat(read.history.firstRecordMs).isEqualTo(1_591_569L)
        assertThat(read.history.firstRecordMs).isAtLeast(cursor("from-mid"))
        assertThat(read.history.gapBeforeFirstRecord).isFalse()
        assertThat(read.records.first()).isEqualTo(HistoryRecord(0, HistoryKind.WALL_CLOCK, HistoryChange.SET, 1_793_938_388_957L))
        val baseline = read.records.filter { it.baseline }
        assertThat(baseline.map { it.kind }).containsAtLeast(HistoryKind.BATTERY_LEVEL, HistoryKind.SCREEN, HistoryKind.DATA_CONNECTION)
        assertThat(baseline).contains(HistoryRecord(0, HistoryKind.DATA_CONNECTION, HistoryChange.SET, 20, baseline = true))
        assertThat(baseline).contains(HistoryRecord(0, HistoryKind.WIFI_SUPPLICANT, HistoryChange.SET, 10, baseline = true))
        assertThat(baseline).contains(HistoryRecord(0, HistoryKind.GPS_SIGNAL_QUALITY, HistoryChange.SET, 2, baseline = true))
        // An event in the same record is a real change, not part of the baseline.
        assertThat(read.records.single { it.offsetMs == 0L && it.kind == HistoryKind.STATE_CHANGE }.baseline).isFalse()
    }

    @Test
    fun readsThatOverlapAgreeOnEveryEventInTheOverlap() {
        val whole = read(capture("from-0"))
        val mid = read(capture("from-mid"), cursor("from-mid"))
        val from = checkNotNull(mid.history.firstRecordMs)
        val to = checkNotNull(whole.history.nextCursorMs) - 1

        fun Read.events() =
            records
                .filter { it.kind in EVENT_KINDS }
                .map { at(it) to it.copy(offsetMs = 0) }
                .filter { (t, _) -> t in from..to }
        val expected = whole.events()
        assertThat(expected.size).isGreaterThan(100)
        assertThat(mid.events()).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun theNextReadStartsAfterTheLastOneEnded() {
        val first = read(capture("from-0"))
        val next = read(capture("from-next"), cursor("from-next"))
        next.assertWhole()
        assertThat(cursor("from-next")).isEqualTo(first.history.nextCursorMs)
        assertThat(next.history.firstRecordMs).isEqualTo(2_730_002L)
        assertThat(next.history.nextCursorMs).isEqualTo(2_751_532L)
        assertThat(next.history.recordCount).isEqualTo(65L)
        assertThat(next.history.gapBeforeFirstRecord).isFalse()
        // Pool tags resolve: every event has a tag.
        assertThat(next.records.filter { it.kind in EVENT_KINDS }.all { it.tag != null }).isTrue()
    }

    @Test
    fun thePhoneHistoryParsesWholeAndMatchesItsOwnTokens() {
        val bytes = historyOnly(Fixtures.PHONE, next = PHONE_NEXT)
        val read = read(bytes)
        read.assertWhole()
        assertThat(read.history.recordCount).isEqualTo(40_064L)
        assertThat(read.history.header?.parcelVersion).isEqualTo(1_376_472L)
        val text = bytes.decodeToString()

        fun count(token: String) = Regex(Regex.escape(token)).findAll(text).count()

        fun count(
            kind: HistoryKind,
            change: HistoryChange,
        ) = read.records.count { it.kind == kind && it.change == change }
        assertThat(count(HistoryKind.TOP_APP, HistoryChange.START)).isEqualTo(count(",+Etp="))
        assertThat(count(HistoryKind.TOP_APP, HistoryChange.STOP)).isEqualTo(count(",-Etp="))
        assertThat(count(HistoryKind.JOB, HistoryChange.START)).isEqualTo(count(",+Ejb="))
        assertThat(count(HistoryKind.WAKE_REASON, HistoryChange.SET)).isEqualTo(count(",wr="))
        val wakelocks = read.records.filter { it.kind == HistoryKind.WAKE_LOCK && it.change == HistoryChange.START && it.tag != null }
        assertThat(wakelocks).hasSize(count(",+w="))
        assertThat(wakelocks.all { it.uid != null }).isTrue()
        assertThat(read.records.filter { it.kind == HistoryKind.WALL_CLOCK }).hasSize(count(":TIME:") - count(":RESET:TIME:"))
        assertThat(read.batches.max()).isAtMost(BatteryHistoryParser.BATCH_SIZE)
        assertThat(read.batches.sum()).isEqualTo(read.records.size)
    }

    @Test
    fun aGapIsFlaggedWhenTheBufferStartsAfterTheCursor() {
        val text = "$VERS\n9,h,0:TIME:5\n9,h,0,Bl=50\n9,h,100,+S\nNEXT: 1101\n"
        // NEXT 1101: the last record is at 1100, the first at 1000.
        val late = read(text, cursor = 900)
        assertThat(late.history.firstRecordMs).isEqualTo(1000L)
        assertThat(late.history.gapBeforeFirstRecord).isTrue()
        assertThat(read(text, cursor = 1000).history.gapBeforeFirstRecord).isFalse()
        assertThat(read(text, cursor = 0).history.gapBeforeFirstRecord).isFalse()
        // A first step above 0: older records were skipped, so the buffer covers the cursor.
        assertThat(read(text.replace("9,h,0:TIME", "9,h,40:TIME"), cursor = 900).history.gapBeforeFirstRecord).isFalse()
    }

    @Test
    fun aReadWithNothingNewHasNoRecordsAndKeepsTheCursor() {
        val read = read("$VERS\n9,hsp,0,0,\"x\"\nNEXT: 2729920\n", cursor = 2_729_920)
        read.assertWhole()
        assertThat(read.records).isEmpty()
        assertThat(read.history.firstRecordMs).isNull()
        assertThat(read.history.nextCursorMs).isEqualTo(2_729_920L)
    }

    @Test
    fun startAndResetBeginANewBaseline() {
        val text = "$VERS\n9,h,0,Bl=50,+S\n9,h,10,-S\n9,h,10:START\n9,h,0,Bl=49,+S\n9,h,5,Bl=48\nNEXT: 26\n"
        val read = read(text)
        read.assertWhole()
        assertThat(read.records.map { Triple(it.offsetMs, it.kind, it.baseline) })
            .containsExactly(
                Triple(0L, HistoryKind.BATTERY_LEVEL, true),
                Triple(0L, HistoryKind.SCREEN, true),
                Triple(10L, HistoryKind.SCREEN, false),
                Triple(20L, HistoryKind.BOOT, false),
                Triple(20L, HistoryKind.BATTERY_LEVEL, true),
                Triple(20L, HistoryKind.SCREEN, true),
                Triple(25L, HistoryKind.BATTERY_LEVEL, false),
            ).inOrder()
    }

    @Test
    fun aFullPoolPrintsTagsInlineWithCommasAndDoubledQuotes() {
        val text = "$VERS\n9,h,0,+w=u0a123:\"sync,one \"\"x\"\"\",+Etp=1000:\"com.example\",Epi=12:\"pkg\"\nNEXT: 1\n"
        val read = read(text)
        read.assertWhole()
        assertThat(read.records)
            .containsExactly(
                HistoryRecord(0, HistoryKind.WAKE_LOCK, HistoryChange.START, uid = 10_123, tag = "sync,one \"x\"", baseline = true),
                HistoryRecord(0, HistoryKind.TOP_APP, HistoryChange.START, uid = 1000, tag = "com.example"),
                HistoryRecord(0, HistoryKind.PACKAGE_INSTALLED, HistoryChange.SET, value = 12, tag = "pkg"),
            ).inOrder()
    }

    @Test
    fun formattedUidsAreReadBackWhereTheyAreUnambiguous() {
        assertThat(parseFormattedUid("1000")).isEqualTo(1000)
        assertThat(parseFormattedUid("-1")).isEqualTo(-1)
        assertThat(parseFormattedUid("u0a123")).isEqualTo(10_123)
        assertThat(parseFormattedUid("u10a5")).isEqualTo(1_010_005)
        assertThat(parseFormattedUid("u10s1000")).isEqualTo(1_001_000)
        // Isolated and other forms are not guessed at.
        assertThat(parseFormattedUid("u0i5")).isNull()
        assertThat(parseFormattedUid("u0ai5")).isNull()
        assertThat(parseFormattedUid("u99999a1")).isNull()
        assertThat(parseFormattedUid("")).isNull()
    }

    @Test
    fun aLineBreakInAPoolStringCannotAddRecords() {
        val text = "$VERS\n9,hsp,0,10050,\"tag\n9,h,5000,+Etp=0\nNEXT: 99\\\"\"\n9,h,0,+Etp=0\nNEXT: 1\n"
        val read = read(text)
        read.assertWhole()
        assertThat(read.records)
            .containsExactly(
                HistoryRecord(0, HistoryKind.TOP_APP, HistoryChange.START, uid = 10_050, tag = "tag\n9,h,5000,+Etp=0\nNEXT: 99\""),
            )
        assertThat(read.history.nextCursorMs).isEqualTo(1L)
    }

    @Test
    fun aRailChargeWithADecimalCommaIsNotUnknown() {
        val read = read("$VERS\n9,h,0,Bl=5,Mrc=1,5,Wrc=0,+S\nNEXT: 1\n")
        read.assertWhole()
        assertThat(read.records.map { it.kind }).containsExactly(HistoryKind.BATTERY_LEVEL, HistoryKind.SCREEN).inOrder()
    }

    @Test
    fun unknownNamesAreCountedAndOddTokensAreIssuesWithoutValues() {
        val text = "$VERS\n9,hsp,0,0,\"t\"\n9,h,0,Zz=1,+Qq,Ev30=0,+S,secret@x.y=1,Bl=abc,+Etp=7,:XX\n9,h,1:FUTURE\nNEXT: 2\n"
        val read = read(text)
        assertThat(read.report.unknown).containsExactly("h.Zz", 1, "h.Qq", 1, "h.Ev30", 1, "h.command", 1)
        assertThat(read.report.issues.map { it.message })
            .containsExactly("malformed change", "Bl is not a number", "tag not in the pool", "malformed change")
        assertThat(read.report.issues.joinToString()).doesNotContain("secret")
        assertThat(read.records.map { it.kind }).containsAtLeast(HistoryKind.SCREEN, HistoryKind.TOP_APP)
        // A known change with a bad value keeps its place, without a value.
        assertThat(read.records.single { it.kind == HistoryKind.BATTERY_LEVEL }.value).isNull()
    }

    @Test
    fun aRecordThatFailedToPrintLeavesTimesUnplacedAndHidesTheTrace() {
        val text = "$VERS\n9,h,0,Bl=5\n9,h,10,+S\njava.lang.NullPointerException: secret tag\n\tat android.os.X(X.java:1)\nNEXT: 31\n"
        val read = read(text)
        assertThat(read.report.complete).isFalse()
        assertThat(read.history.firstRecordMs).isNull()
        assertThat(read.history.nextCursorMs).isEqualTo(31L)
        assertThat(read.report.issues.map { it.message }).containsExactly("not a history line")
        assertThat(read.report.issues.joinToString()).doesNotContain("secret")
        assertThat(read.records).hasSize(2)
    }

    @Test
    fun aCutReadKeepsItsRecordsButPlacesNothing() {
        val bytes = capture("from-0")
        val whole = read(bytes).records
        for (fraction in listOf(0.0, 0.2, 0.5, 0.9, 0.999)) {
            val read = read(bytes.copyOf((bytes.size * fraction).toInt()))
            assertThat(read.report.complete).isFalse()
            assertThat(read.history.nextCursorMs).isNull()
            assertThat(read.history.firstRecordMs).isNull()
            // What was read is the start of the whole read, unchanged.
            assertThat(read.records).isEqualTo(whole.take(read.records.size))
        }
    }

    @Test
    fun randomDamageNeverThrows() {
        val bytes = historyOnly(Fixtures.PHONE, next = PHONE_NEXT)
        val random = Random(SEED)
        repeat(DAMAGE_RUNS) {
            val damaged = bytes.copyOf()
            repeat(DAMAGED_BYTES) { damaged[random.nextInt(damaged.size)] = random.nextInt().toByte() }
            val read = read(damaged)
            assertThat(read.report.bytesRead).isEqualTo(bytes.size.toLong())
        }
        assertThat(read(ByteArray(0)).report.complete).isFalse()
        // A step too large for a Long, then offsets that overflow one.
        val max = Long.MAX_VALUE
        val overflow = read("$VERS\n9,h,99999999999999999999,+S\n9,h,0,+S\n9,h,$max,+S\n9,h,$max,+S\nNEXT: 1\n")
        assertThat(overflow.report.complete).isFalse()
        assertThat(overflow.history.firstRecordMs).isNull()
    }

    @Test
    fun whatTheSinkThrowsIsNotSwallowed() {
        val failure = IllegalStateException("database full")
        val thrown =
            runCatching {
                BatteryHistoryParser.parse(ByteArrayInputStream(capture("from-0")), 0) { throw failure }
            }.exceptionOrNull()
        assertThat(thrown).isSameInstanceAs(failure)
    }

    private companion object {
        const val VERS = "9,0,i,vers,36,216,A,A"
        const val PHONE_NEXT = 1_000_000_000L
        const val SEED = 7
        const val DAMAGE_RUNS = 20
        const val DAMAGED_BYTES = 24

        val EVENT_KINDS =
            setOf(
                HistoryKind.TOP_APP,
                HistoryKind.FOREGROUND,
                HistoryKind.JOB,
                HistoryKind.SYNC,
                HistoryKind.ALARM,
                HistoryKind.TEMP_ALLOWLIST,
                HistoryKind.STATE_CHANGE,
                HistoryKind.DISPLAY_STATE,
                HistoryKind.SCREEN_WAKE_UP,
                HistoryKind.COLLECT_EXTERNAL_STATS,
                HistoryKind.CONNECTIVITY,
                HistoryKind.WAKEUP_AP,
                HistoryKind.LONG_WAKE_LOCK,
                HistoryKind.WAKE_REASON,
            )
    }
}

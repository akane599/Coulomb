// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import io.github.akane599.coulomb.core.model.history.BatteryHistory
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import io.github.akane599.coulomb.core.model.history.HistoryRecord
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.model.stats.StatsHeader
import io.github.akane599.coulomb.core.parsing.ParseContext
import io.github.akane599.coulomb.core.parsing.io.Line
import io.github.akane599.coulomb.core.parsing.io.LineReader
import io.github.akane599.coulomb.core.parsing.io.MeteredSource
import io.github.akane599.coulomb.core.parsing.text.Csv
import java.io.IOException
import java.io.InputStream

/** Takes [HistoryRecord]s in batches while [BatteryHistoryParser] reads. What it throws is not caught. */
public fun interface HistorySink {
    public fun accept(batch: List<HistoryRecord>)
}

/**
 * `dumpsys batterystats -c --history-start <cursor>` (S7, ADR-0014) into [HistoryRecord]s, which
 * go to a [HistorySink] in batches of at most [BATCH_SIZE], and a [BatteryHistory]. Never throws on
 * bad input.
 *
 * The dump is `9,0,i,vers`, the tag pool (`9,hsp`), the records (`9,h,<step>`) and `NEXT:
 * <last time + 1>` (`BatteryStats.dumpHistory`, BatteryStats.java android-17.0.0_r1 L7471-7586,
 * android-16.0.0_r4 L7439-7554). Facts the format fixes:
 * - A record's step is its time minus the previous printed record's. The first step counts from
 *   the oldest record the system iterated, so it is 0 when the read starts at that record.
 * - The only absolute time is `NEXT:`, at the end, so records carry offsets from the first record
 *   and [BatteryHistory.firstRecordMs] places them once the read is done.
 * - A read from a cursor inside the buffer starts with a `TIME` record that has the next record's
 *   time; the printer is new for each dump, so that record restates the whole state
 *   ([HistoryRecord.baseline]), as does the first record after `START` or `RESET`.
 * - When a record fails to print, the platform prints the stack trace and stops; `NEXT:` then
 *   counts the failed record, so times are not placed ([BatteryHistory.firstRecordMs] is null).
 * - `9,h,0,Dcpu=` and `9,h,0,Dpst=` lines are step details of the previous record, not records;
 *   Coulomb does not use them.
 *
 * Plain `-c` output (the history followed by the since-charged data) is accepted too: the other
 * checkin lines are skipped, and without `NEXT:` the read is incomplete.
 */
public object BatteryHistoryParser {
    public const val SOURCE: String = "batterystats.history"
    public const val BATCH_SIZE: Int = 512

    /** @param cursorMs the `--history-start` value the read used; 0 for the first read. */
    public fun parse(
        input: InputStream,
        cursorMs: Long,
        sink: HistorySink,
    ): Parsed<BatteryHistory> {
        val meter = MeteredSource(input)
        val ctx = ParseContext(SOURCE)
        val walk = HistoryWalk(CheckinLines(LineReader(meter)), ctx, cursorMs, sink)
        val complete = walk.run()
        return Parsed(walk.result(), ctx.report(meter.offset, complete))
    }
}

/** A tag pool entry, or a tag printed inline when the pool was full. */
private class Tag(
    val uid: Int?,
    val text: String?,
)

private class HistoryWalk(
    private val lines: CheckinLines,
    private val ctx: ParseContext,
    private val cursorMs: Long,
    private val sink: HistorySink,
) {
    private var header: StatsHeader? = null
    private val pool = HashMap<Int, Tag>()
    private var poolChars = 0L
    private var poolFull = false
    private var next: Long? = null
    private var firstStep: Long? = null
    private var offset = 0L
    private var records = 0L
    private var baselinePending = true
    private val pending = ArrayList<HistoryRecord>(BatteryHistoryParser.BATCH_SIZE)

    private var cut = false

    /** A record failed to print, or a line that is not part of the history came in. */
    private var failed = false

    /** A record or pool entry could not be read. */
    private var lost = false

    /** A record's step is missing, so the offsets after it are wrong. */
    private var timingLost = false

    fun run(): Boolean {
        var line = guard { lines.next() }
        while (line != null && line.terminated) {
            val current = line
            guard { read(current) } ?: break
            flush(all = false)
            line = guard { lines.next() }
        }
        if (line != null && !line.terminated) {
            cut = true
            issue(line.offset, BatteryHistoryParser.SOURCE, "ended inside a line")
        }
        flush(all = true)
        if (header == null) issue(lines.offset, BatteryHistoryParser.SOURCE, "no vers line")
        if (next == null && !cut) issue(lines.offset, BatteryHistoryParser.SOURCE, "no NEXT line")
        return !cut && !failed && !lost && header != null && next != null
    }

    fun result(): BatteryHistory {
        val nextMs = next
        val step = firstStep
        val placed = !failed && !timingLost
        val first = if (placed && nextMs != null && step != null) nextMs - 1 - offset else null
        // A first step of 0: the read began at the oldest record kept, which is after the cursor.
        val startsAtOldest = cursorMs > 0 && step == 0L
        return BatteryHistory(
            header = header,
            nextCursorMs = nextMs,
            firstRecordMs = first,
            recordCount = records,
            gapBeforeFirstRecord = startsAtOldest && first != null && first > cursorMs,
        )
    }

    /** Runs [block]; bad input that throws ends the read as cut, never the caller. */
    @Suppress("TooGenericExceptionCaught") // The parser boundary: bad input must never throw (SPEC 5.1).
    private inline fun <T> guard(block: () -> T): T? =
        try {
            block()
        } catch (e: IOException) {
            stop("ended early", e)
        } catch (e: RuntimeException) {
            stop("malformed", e)
        }

    private fun stop(
        what: String,
        e: Exception,
    ): Nothing? {
        cut = true
        issue(lines.offset, BatteryHistoryParser.SOURCE, "$what: ${e.javaClass.simpleName}")
        return null
    }

    /** Hands full batches to the sink, outside [guard] so that what the sink throws reaches the caller; [all] sends the rest too. */
    private fun flush(all: Boolean) {
        val size = BatteryHistoryParser.BATCH_SIZE
        while (pending.size >= size || (all && pending.isNotEmpty())) {
            val head = pending.subList(0, minOf(size, pending.size))
            val batch = ArrayList(head)
            head.clear()
            sink.accept(batch)
        }
    }

    private fun issue(
        offset: Long,
        section: String,
        message: String,
    ) = ctx.issue(offset, section, message)

    private fun read(line: Line) {
        val text = line.text
        // The rest of a plain `-c` dump belongs to S6.
        if (text.isEmpty() || isOtherCheckin(text)) return
        when {
            line.truncated -> truncated(line)
            text.startsWith(CheckinLines.POOL_PREFIX) -> pool(line)
            text.startsWith(CheckinLines.HISTORY_PREFIX) -> record(line)
            text.startsWith(NEXT_PREFIX) -> nextCursor(line)
            text.startsWith(VERS_PREFIX) -> version(line)
            else -> stray(line)
        }
    }

    private fun isOtherCheckin(text: String) =
        text.startsWith(DATA_PREFIX) &&
            !text.startsWith(CheckinLines.POOL_PREFIX) &&
            !text.startsWith(CheckinLines.HISTORY_PREFIX) &&
            !text.startsWith(VERS_PREFIX)

    private fun truncated(line: Line) {
        issue(line.offset, BatteryHistoryParser.SOURCE, "record longer than ${LineReader.MAX_LINE_BYTES} bytes")
        lost = true
        if (line.text.startsWith(CheckinLines.HISTORY_PREFIX)) timingLost = true
    }

    /** A stack trace from a record that failed to print, or other text the history never holds. Its text is not kept. */
    private fun stray(line: Line) {
        if (!failed) issue(line.offset, BatteryHistoryParser.SOURCE, "not a history line")
        failed = true
    }

    private fun version(line: Line) {
        val fields = Csv.split(line.text)
        val version = fields.getOrNull(CheckinRow.HEADER + VersCol.CHECKIN_VERSION.ordinal)?.toIntOrNull()
        val parcel = fields.getOrNull(CheckinRow.HEADER + VersCol.PARCEL_VERSION.ordinal)?.toLongOrNull()
        if (version == null || parcel == null) {
            issue(line.offset, "vers", "not a number")
            return
        }
        header =
            StatsHeader(
                reportVersion = version,
                parcelVersion = parcel,
                startPlatformVersion = fields.getOrNull(CheckinRow.HEADER + VersCol.START_PLATFORM_VERSION.ordinal),
                endPlatformVersion = fields.getOrNull(CheckinRow.HEADER + VersCol.END_PLATFORM_VERSION.ordinal),
            )
    }

    private fun nextCursor(line: Line) {
        next =
            line.text
                .substring(NEXT_PREFIX.length)
                .trim()
                .toLongOrNull()
        if (next == null) issue(line.offset, "NEXT", "not a number")
    }

    /** `9,hsp,<index>,<uid>,"<string with \ and " escaped>"`. */
    private fun pool(line: Line) {
        val parts = line.text.split(',', limit = POOL_PARTS)
        val index = parts.getOrNull(2)?.toIntOrNull()
        val uid = parts.getOrNull(POOL_UID)?.toIntOrNull()
        val text = parts.getOrNull(POOL_STRING)?.let(::unescapePoolString)
        when {
            index == null || uid == null || text == null -> {
                poolIssue(line, "malformed")
            }

            index !in 0 until HISTORY_TAG_INDEX_LIMIT -> {
                poolIssue(line, "index out of range")
            }

            poolChars + text.length > MAX_POOL_CHARS -> {
                // The tag stays known by its UID; its string is dropped to bound memory.
                if (!poolFull) poolIssue(line, "pool larger than $MAX_POOL_CHARS characters")
                poolFull = true
                pool[index] = Tag(uid, null)
            }

            else -> {
                poolChars += text.length
                pool[index] = Tag(uid, text)
            }
        }
    }

    private fun poolIssue(
        line: Line,
        message: String,
    ) {
        issue(line.offset, "hsp", message)
        lost = true
    }

    /** `"..."` with `\\` and `\"` escapes, the closing quote last; null when it is not. */
    private fun unescapePoolString(quoted: String): String? {
        if (quoted.isEmpty() || quoted[0] != '"') return null
        val out = StringBuilder(quoted.length)
        var i = 1
        var closed = false
        while (i < quoted.length && !closed) {
            val c = quoted[i]
            when {
                c == '\\' && i + 1 < quoted.length -> {
                    out.append(quoted[i + 1])
                    i += 2
                }

                c == '"' -> {
                    closed = true
                    i++
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return if (closed && i == quoted.length) out.toString() else null
    }

    /** `9,h,<step>` followed by `:<command>` or `,<change>,<change>...`. */
    private fun record(line: Line) {
        val text = line.text
        val start = CheckinLines.HISTORY_PREFIX.length
        var end = start
        while (end < text.length && text[end] != ',' && text[end] != ':') end++
        val step = text.substring(start, end).toLongOrNull()
        if (step == null) {
            issue(line.offset, "h", "time step is not a number")
            lost = true
            timingLost = true
            return
        }
        if (text.startsWith(CPU_DETAILS, end) || text.startsWith(STAT_DETAILS, end)) return
        records++
        if (firstStep == null) firstStep = step else offset = Math.addExact(offset, step)
        if (end < text.length && text[end] == ':') {
            command(line, text.substring(end + 1))
        } else {
            changes(line, text, end)
        }
    }

    private fun command(
        line: Line,
        command: String,
    ) {
        when {
            command == "START" -> {
                emit(HistoryKind.BOOT, HistoryChange.SET)
                baselinePending = true
            }

            command.startsWith(RESET_TIME) -> {
                emit(HistoryKind.RESET, HistoryChange.SET, wallTime(line, command.substring(RESET_TIME.length)))
                baselinePending = true
            }

            command.startsWith(TIME) -> {
                emit(HistoryKind.WALL_CLOCK, HistoryChange.SET, wallTime(line, command.substring(TIME.length)))
            }

            command == "SHUTDOWN" -> {
                emit(HistoryKind.SHUTDOWN, HistoryChange.SET)
            }

            command == "*OVERFLOW*" -> {
                emit(HistoryKind.OVERFLOW, HistoryChange.SET)
            }

            else -> {
                ctx.unknown("h.command")
            }
        }
    }

    private fun wallTime(
        line: Line,
        text: String,
    ): Long? = text.toLongOrNull() ?: null.also { issue(line.offset, "h", "wall time is not a number") }

    private fun changes(
        line: Line,
        text: String,
        from: Int,
    ) {
        val baseline = baselinePending
        baselinePending = false
        var afterRail = false
        for (token in tokens(text, from)) {
            // A rail charge printed with a decimal comma (DecimalFormat in the system locale).
            afterRail =
                if (afterRail && token.isNotEmpty() && token.all { it in '0'..'9' }) {
                    false
                } else {
                    change(line, token, baseline)
                }
        }
    }

    /** One `+x`, `-x`, `x=v` or `+x=v` token; true when it was a rail charge. */
    private fun change(
        line: Line,
        token: String,
        baseline: Boolean,
    ): Boolean {
        val sign = token.firstOrNull()
        val signed = sign == '+' || sign == '-'
        val body = if (signed) token.substring(1) else token
        val eq = body.indexOf('=')
        val name = if (eq < 0) body else body.substring(0, eq)
        val value = if (eq < 0) null else body.substring(eq + 1)
        val change =
            when (sign) {
                '+' -> HistoryChange.START
                '-' -> HistoryChange.STOP
                else -> HistoryChange.SET
            }
        val flag = HistoryNames.FLAGS[name]
        val event = HistoryNames.EVENTS[name]
        when {
            signed && flag != null -> {
                emit(flag, change, tag = if (flag == HistoryKind.WAKE_LOCK) tag(line, value) else null, baseline = baseline)
            }

            event != null && value != null -> {
                event(line, event, change, value)
            }

            !signed && value != null -> {
                return assignment(line, name, value, baseline)
            }

            else -> {
                unknownToken(line, name)
            }
        }
        return false
    }

    /** `x=v` without a sign; true when it was a rail charge. */
    private fun assignment(
        line: Line,
        name: String,
        value: String,
        baseline: Boolean,
    ): Boolean {
        val battery = HistoryNames.BATTERY[name]
        val state = HistoryNames.VALUES[name]
        when {
            battery != null -> {
                emit(battery, HistoryChange.SET, number(line, name, batteryValue(battery, value)), baseline = baseline)
            }

            state != null -> {
                val index =
                    state.names
                        .indexOf(value)
                        .takeIf { it >= 0 }
                        ?.toLong()
                emit(state.kind, HistoryChange.SET, number(line, name, index ?: value.toLongOrNull()), baseline = baseline)
            }

            name == HistoryNames.WAKE_REASON -> {
                emit(HistoryKind.WAKE_REASON, HistoryChange.SET, tag = tag(line, value))
            }

            name == HistoryNames.WAKE_LOCK_TAG -> {
                emit(HistoryKind.WAKE_LOCK, HistoryChange.SET, tag = tag(line, value), baseline = baseline)
            }

            name in HistoryNames.RAIL_CHARGES -> {
                return true
            }

            else -> {
                unknownToken(line, name)
            }
        }
        return false
    }

    private fun batteryValue(
        kind: HistoryKind,
        value: String,
    ): Long? = HistoryNames.BATTERY_LETTERS[kind]?.get(value) ?: value.toLongOrNull()

    private fun number(
        line: Line,
        name: String,
        value: Long?,
    ): Long? = value ?: null.also { issue(line.offset, "h", "$name is not a number") }

    private fun event(
        line: Line,
        kind: HistoryKind,
        change: HistoryChange,
        value: String,
    ) {
        val tag = tag(line, value)
        if (kind in HistoryNames.VALUE_NOT_UID) {
            emit(kind, change, tag?.uid?.toLong(), Tag(null, tag?.text))
        } else {
            emit(kind, change, tag = tag)
        }
    }

    /** A pool index, or `<formatted uid>:"<string with doubled quotes>"` when the pool was full. */
    private fun tag(
        line: Line,
        ref: String?,
    ): Tag? {
        val index = ref?.toIntOrNull()
        val colon = ref?.indexOf(":\"") ?: -1
        return when {
            ref == null -> {
                null
            }

            index != null -> {
                pool[index] ?: null.also { issue(line.offset, "h", "tag not in the pool") }
            }

            colon > 0 && ref.length >= colon + INLINE_MARKS && ref.endsWith('"') -> {
                Tag(parseFormattedUid(ref.substring(0, colon)), ref.substring(colon + 2, ref.length - 1).replace("\"\"", "\""))
            }

            else -> {
                null.also { issue(line.offset, "h", "malformed tag") }
            }
        }
    }

    /** Counted by name; a name that is not a short identifier may be a value, so only an issue is kept. */
    private fun unknownToken(
        line: Line,
        name: String,
    ) {
        val identifier = name.length in 1..MAX_NAME && name[0].isLetter() && name.all { it.isLetterOrDigit() }
        if (identifier) ctx.unknown("h.$name") else issue(line.offset, "h", "malformed change")
    }

    private fun emit(
        kind: HistoryKind,
        change: HistoryChange,
        value: Long? = null,
        tag: Tag? = null,
        baseline: Boolean = false,
    ) {
        pending += HistoryRecord(offset, kind, change, value, tag?.uid, tag?.text, baseline)
    }

    private companion object {
        const val DATA_PREFIX = "9,"
        const val VERS_PREFIX = "9,0,i,vers,"
        const val NEXT_PREFIX = "NEXT: "
        const val CPU_DETAILS = ",Dcpu="
        const val STAT_DETAILS = ",Dpst="
        const val RESET_TIME = "RESET:TIME:"
        const val TIME = "TIME:"
        const val POOL_PARTS = 5
        const val POOL_UID = 3
        const val POOL_STRING = 4
        const val MAX_NAME = 16

        /** `:"` and the closing quote of an inline tag. */
        const val INLINE_MARKS = 3

        /**
         * Pool indexes stay below `HISTORY_TAG_INDEX_LIMIT` (core/java/com/android/internal/os/
         * BatteryStatsHistory.java, android-16.0.0_r4 and android-17.0.0_r1 L351).
         */
        const val HISTORY_TAG_INDEX_LIMIT = 0x7ffe

        /** About 8 MB of strings; a real pool is under 100 KB (885 strings, 51 KB on the phone). */
        const val MAX_POOL_CHARS = 4_000_000L

        /**
         * Splits the changes after a record's step. A tag printed inline, `x=<uid>:"text"`, keeps its
         * commas; `""` inside it is a quote.
         */
        fun tokens(
            text: String,
            from: Int,
        ): List<String> {
            val out = ArrayList<String>()
            var i = from
            while (i < text.length) {
                i++ // the comma
                val start = i
                while (i < text.length && text[i] != ',') {
                    i = if (text[i] == '"') endOfQuoted(text, i + 1) else i + 1
                }
                out += text.substring(start, i)
            }
            return out
        }

        /** Index just past the closing quote of a string that doubles its quotes, or the end. */
        fun endOfQuoted(
            text: String,
            from: Int,
        ): Int {
            var i = from
            while (i < text.length) {
                if (text[i] != '"') {
                    i++
                } else if (i + 1 < text.length && text[i + 1] == '"') {
                    i += 2
                } else {
                    return i + 1
                }
            }
            return text.length
        }
    }
}

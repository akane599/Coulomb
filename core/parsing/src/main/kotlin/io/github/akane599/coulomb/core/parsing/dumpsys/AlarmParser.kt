// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.dumpsys

import io.github.akane599.coulomb.core.model.dumpsys.AlarmDump
import io.github.akane599.coulomb.core.model.dumpsys.AlarmTagStats
import io.github.akane599.coulomb.core.model.dumpsys.AlarmType
import io.github.akane599.coulomb.core.model.dumpsys.AppAlarmStats
import io.github.akane599.coulomb.core.model.dumpsys.PendingAlarm
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.parsing.ParseContext
import io.github.akane599.coulomb.core.parsing.batterystats.parseFormattedUid
import io.github.akane599.coulomb.core.parsing.io.Line
import io.github.akane599.coulomb.core.parsing.text.Durations
import io.github.akane599.coulomb.core.parsing.text.TextDump
import java.io.InputStream

/**
 * `dumpsys alarm` (S10) into an [AlarmDump]: the dump time, the pending alarms, the pending count
 * per UID and `Alarm Stats` (AlarmManagerService.java `dumpImpl`, android-16.0.0_r4 L2985-3484,
 * android-17.0.0_r1 L3111-3616). The other sections are skipped. Never throws on bad input.
 *
 * Alarm tags are app text and are not escaped, but the service prints through an
 * `IndentingPrintWriter`, which writes the current indent at the start of every line, line breaks
 * inside a printed string included (core/java/android/util/IndentingPrintWriter.java L199-253, the
 * same file in both tags). A tag is printed six spaces in, so every line of it starts with at least
 * six spaces, and lines two or four spaces in are the platform's own: the `Alarm Stats` app and tag
 * lines (16 L3409-3449, 17 L3541-3581) and the pending `Alarm.toString` lines (`dumpAlarmList`, 16
 * L4125-4139, 17 L4257-4271). So the parser reads only those, takes a stats tag as every deeper line
 * up to the next platform line, and keeps the pending list only when its lines match the count
 * printed above it. A heading the parser reads must appear once.
 */
public object AlarmParser {
    public const val SOURCE: String = "dumpsys.alarm"

    public fun parse(input: InputStream): Parsed<AlarmDump> {
        val ctx = ParseContext(SOURCE)
        val dump = TextDump(input, ctx, SOURCE)
        val walk = AlarmWalk(ctx)
        val clean = dump.forEachLine(walk::read)
        val complete = walk.finish(dump.bytesRead) && clean && dump.lostLines == 0
        return Parsed(walk.result(), ctx.report(dump.bytesRead, complete))
    }
}

private enum class AlarmSection { OTHER, PENDING, STATS }

private class AppBuilder(
    val uid: Int,
    val packageName: String,
    val runningMs: Long,
    val wakeups: Int,
    val active: Boolean,
) {
    val alarms = ArrayList<AlarmTagStats>()

    fun build() = AppAlarmStats(uid, packageName, runningMs, wakeups, active, alarms.toList())
}

private class FilterBuilder(
    val runningMs: Long,
    val wakeups: Int,
    val count: Int,
    val lastMsBeforeDump: Long?,
    val active: Boolean,
) {
    val tag = StringBuilder()
    var tagLines = 0

    fun build() = AlarmTagStats(tag.toString(), runningMs, wakeups, count, lastMsBeforeDump, active)
}

private class AlarmWalk(
    private val ctx: ParseContext,
) {
    private var section = AlarmSection.OTHER
    private var problemsWhileReading = 0

    private var headers = 0
    private var nowLines = 0
    private var nowElapsed: Long? = null
    private var nowRtc: Long? = null

    private var pendingHeadings = 0
    private var declaredPending = 0
    private val pending = ArrayList<PendingAlarm>()
    private var pendingTrusted = true
    private var tagExpected = false

    private var perUidLines = 0
    private var perUid: Map<Int, Int>? = null

    private var statsHeadings = 0
    private val apps = ArrayList<AppAlarmStats>()
    private var app: AppBuilder? = null
    private var filter: FilterBuilder? = null
    private var skippingApp = false
    private var statsTrusted = true

    fun read(line: Line) {
        val text = line.text
        val indent = text.indexOfFirst { it != ' ' }.let { if (it < 0) text.length else it }
        val separator = indent == text.length && indent <= 2
        if (section == AlarmSection.PENDING && indent <= 2) endPending(line.offset)
        if (section == AlarmSection.STATS && (indent < 2 || separator)) endStats(line.offset)
        when (section) {
            AlarmSection.PENDING -> pendingLine(line, indent)
            AlarmSection.STATS -> statsLine(line, indent)
            AlarmSection.OTHER -> if (!separator && indent <= 2) outer(line, indent)
        }
    }

    private fun problem(
        offset: Long,
        message: String,
    ) {
        problemsWhileReading++
        ctx.issue(offset, AlarmParser.SOURCE, message)
    }

    private fun outer(
        line: Line,
        indent: Int,
    ) {
        val text = line.text
        when {
            indent == 0 -> if (text == HEADER) headers++
            text.startsWith(NOW_PREFIX) -> if (++nowLines == 1) now(text)
            text.startsWith(PER_UID_PREFIX) -> if (++perUidLines == 1) perUid = perUid(text)
            text == STATS_HEADING -> if (++statsHeadings == 1) section = AlarmSection.STATS
            else -> PENDING_HEADING.matchEntire(text)?.let { if (++pendingHeadings == 1) startPending(it) }
        }
    }

    private fun now(text: String) {
        val match = NOW.matchEntire(text) ?: return
        nowRtc = match.groupValues[1].toLongOrNull()
        nowElapsed = match.groupValues[2].toLongOrNull()
    }

    /** `[1000:19, u0a110:1]`; null when any entry is unreadable. */
    private fun perUid(text: String): Map<Int, Int>? {
        if (!text.endsWith("]")) return null
        val body = text.substring(PER_UID_PREFIX.length, text.length - 1)
        val counts = LinkedHashMap<Int, Int>()
        if (body.isEmpty()) return counts
        for (entry in body.split(", ")) {
            val colon = entry.lastIndexOf(':')
            val uid = if (colon > 0) parseFormattedUid(entry.substring(0, colon)) else null
            val count = entry.substring(colon + 1).toIntOrNull()
            if (uid == null || count == null || uid in counts) return null
            counts[uid] = count
        }
        return counts
    }

    private fun startPending(match: MatchResult) {
        declaredPending = match.groupValues[1].toInt()
        section = AlarmSection.PENDING
    }

    private fun pendingLine(
        line: Line,
        indent: Int,
    ) {
        val text = line.text
        val expected = tagExpected
        tagExpected = false
        when {
            indent == ALARM_INDENT -> {
                val match = PENDING_ALARM.matchEntire(text)
                val whenElapsed = match?.groupValues?.get(2)?.toLongOrNull()
                if (match == null || whenElapsed == null) {
                    pendingTrusted = false
                    return problem(line.offset, "unreadable pending alarm")
                }
                val label = match.groupValues[1]
                val type = AlarmType.entries.firstOrNull { it.platformName == label }
                if (type == null) ctx.unknown(if (label == UNKNOWN_TYPE) "type.$UNKNOWN_TYPE" else NO_NAME)
                pending += PendingAlarm(type, match.groupValues.last(), whenElapsed, tag = null)
                tagExpected = true
            }

            indent >= DETAIL_INDENT -> {
                // `Alarm.dump` prints the tag first (Alarm.java L316-319).
                if (expected && text.startsWith(PENDING_TAG)) {
                    pending[pending.lastIndex] = pending.last().copy(tag = text.substring(PENDING_TAG.length))
                }
            }

            else -> {
                pendingTrusted = false
                problem(line.offset, "pending alarm line out of place")
            }
        }
    }

    private fun endPending(offset: Long) {
        section = AlarmSection.OTHER
        if (pendingTrusted && pending.size != declaredPending) {
            pendingTrusted = false
            problem(offset, "pending alarm lines do not match their count")
        }
    }

    private fun statsLine(
        line: Line,
        indent: Int,
    ) {
        val text = line.text
        when {
            indent == 2 -> {
                endApp(line.offset)
                appLine(line)
            }

            // The lines under an app whose entry is not read.
            skippingApp -> {
                return
            }

            indent == ALARM_INDENT && app != null -> {
                endFilter(line.offset)
                filter = filterLine(text)
                if (filter == null) unreadableStats(line.offset)
            }

            indent >= DETAIL_INDENT && filter != null -> {
                val f = checkNotNull(filter)
                if (f.tagLines++ > 0) f.tag.append('\n')
                f.tag.append(text, DETAIL_INDENT, text.length)
            }

            else -> {
                unreadableStats(line.offset)
            }
        }
    }

    private fun appLine(line: Line) {
        val match = APP.matchEntire(line.text)
        val running =
            match
                ?.groups
                ?.get("running")
                ?.value
                ?.let(Durations::parseMs)
        val wakeups =
            match
                ?.groups
                ?.get("wakeups")
                ?.value
                ?.toIntOrNull()
        if (match == null || running == null || wakeups == null) return unreadableStats(line.offset)
        val uid = parseFormattedUid(checkNotNull(match.groups["uid"]).value)
        if (uid == null) {
            // One app's entry is lost; the others still read.
            skippingApp = true
            return problem(line.offset, "unreadable uid in Alarm Stats")
        }
        app = AppBuilder(uid, checkNotNull(match.groups["pkg"]).value, running, wakeups, match.groups["active"] != null)
    }

    private fun filterLine(text: String): FilterBuilder? {
        val match = FILTER.matchEntire(text) ?: return null
        val running = checkNotNull(match.groups["running"]).value.let(Durations::parseMs)
        val wakeups = checkNotNull(match.groups["wakeups"]).value.toIntOrNull()
        val count = checkNotNull(match.groups["count"]).value.toIntOrNull()
        val lastText = checkNotNull(match.groups["last"]).value
        val last = if (lastText == NONE) null else Durations.parseMs(lastText)?.let { -it }
        val readable = running != null && wakeups != null && count != null && (lastText == NONE || last != null)
        return if (readable) {
            FilterBuilder(
                checkNotNull(running),
                checkNotNull(wakeups),
                checkNotNull(count),
                last,
                match.groups["active"] != null,
            )
        } else {
            null
        }
    }

    private fun unreadableStats(offset: Long) {
        if (statsTrusted) problem(offset, "unreadable Alarm Stats line")
        statsTrusted = false
        filter = null
    }

    private fun endFilter(offset: Long) {
        val f = filter ?: return
        filter = null
        if (f.tagLines == 0) return unreadableStats(offset)
        app?.alarms?.add(f.build())
    }

    private fun endApp(offset: Long) {
        endFilter(offset)
        app?.let { apps += it.build() }
        app = null
        skippingApp = false
    }

    private fun endStats(offset: Long) {
        endApp(offset)
        section = AlarmSection.OTHER
    }

    /** Records what is missing; true when nothing required is. */
    fun finish(offset: Long): Boolean {
        when (section) {
            AlarmSection.PENDING -> endPending(offset)
            AlarmSection.STATS -> endStats(offset)
            AlarmSection.OTHER -> Unit
        }
        val problems = ArrayList<String>()
        if (headers != 1) problems += if (headers == 0) "no alarm manager header" else "header printed more than once"
        when {
            nowLines == 0 -> problems += "no nowELAPSED line"
            nowLines > 1 -> problems += "nowELAPSED printed more than once"
            nowElapsed == null || nowRtc == null -> problems += "unreadable nowELAPSED line"
        }
        // The pending list is printed only when there is one, and always before the per-UID line.
        when {
            pendingHeadings > 1 -> problems += "pending alarms printed more than once"
            pendingHeadings == 0 && perUidLines == 0 -> problems += "no pending alarms"
        }
        when {
            perUidLines == 0 -> problems += "no Pending alarms per uid line"
            perUidLines > 1 -> problems += "Pending alarms per uid printed more than once"
            perUid == null -> problems += "unreadable Pending alarms per uid"
        }
        when (statsHeadings) {
            0 -> problems += "no Alarm Stats"
            1 -> Unit
            else -> problems += "Alarm Stats printed more than once"
        }
        problems.forEach { ctx.issue(offset, AlarmParser.SOURCE, it) }
        return problems.isEmpty() && problemsWhileReading == 0
    }

    fun result(): AlarmDump =
        AlarmDump(
            nowElapsedMs = nowElapsed.takeIf { nowLines == 1 },
            nowRtcMs = nowRtc.takeIf { nowLines == 1 },
            pending =
                when {
                    pendingHeadings == 1 && pendingTrusted -> pending.toList()
                    pendingHeadings == 0 && perUidLines == 1 -> emptyList()
                    else -> null
                },
            pendingPerUid = perUid.takeIf { perUidLines == 1 },
            appStats = apps.toList().takeIf { statsHeadings == 1 && statsTrusted },
        )

    private companion object {
        const val HEADER = "Current Alarm Manager state:"
        const val NOW_PREFIX = "  nowRTC="
        const val PER_UID_PREFIX = "  Pending alarms per uid: ["
        const val STATS_HEADING = "  Alarm Stats:"
        const val PENDING_TAG = "      tag="
        const val UNKNOWN_TYPE = "--unknown--"
        const val NO_NAME = "<line without key>"
        const val NONE = "--"
        const val ALARM_INDENT = 4
        const val DETAIL_INDENT = 6

        val NOW = Regex("  nowRTC=(-?[0-9]{1,19})=.* nowELAPSED=(-?[0-9]{1,19})")
        val PENDING_HEADING = Regex("  ([0-9]{1,9}) pending alarms: ")
        val PENDING_ALARM =
            Regex(
                "    ([A-Za-z_-]{1,20}) #[0-9]{1,9}: Alarm\\{[0-9a-f]{1,8} type -?[0-9]{1,10} origWhen -?[0-9]{1,19} " +
                    "whenElapsed (-?[0-9]{1,19}) (\\S+)\\}",
            )
        val APP =
            Regex(
                "  (?<active>\\*ACTIVE\\* )?(?<uid>[0-9a-z]{1,16}):(?<pkg>\\S+) (?<running>\\S+) running, (?<wakeups>[0-9]{1,10}) wakeups:",
            )
        val FILTER =
            Regex(
                "    (?<active>\\*ACTIVE\\* )?(?<running>\\S+) (?<wakeups>[0-9]{1,10}) wakes (?<count>[0-9]{1,10}) alarms, " +
                    "last (?<last>\\S+):",
            )
    }
}

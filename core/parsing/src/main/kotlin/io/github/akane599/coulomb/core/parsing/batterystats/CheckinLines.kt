// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import io.github.akane599.coulomb.core.parsing.io.Line
import io.github.akane599.coulomb.core.parsing.io.LineReader
import io.github.akane599.coulomb.core.parsing.text.Csv

/**
 * The records of a `dumpsys batterystats -c` dump, one [Line] each (ADR-0013).
 *
 * The checkin writers quote some names without escaping line breaks, so a name with a `\n` spans
 * physical lines. Read line by line, its second half would pass for a record of its own, and an app
 * that names a wakelock or a job could add rows to any UID. A record whose quote is still open at
 * the end of a line is therefore joined with the next lines, with `\n` between them, until the
 * quote closes:
 * - `9,hsp,` tag pool strings escape `\` and `"` (`dumpHistoryTagPoolLocked`, BatteryStats.java
 *   android-17.0.0_r1 L7590-7611, android-16.0.0_r4 L7558-7579), so the closing quote is exact.
 * - `9,h,` history records quote a tag inline only when the pool is full, and double its quotes
 *   (`printBitDescriptions` and `printNextItem`, android-17.0.0_r1 L6684-6734 and L6829-7206,
 *   android-16.0.0_r4 L6652-6702 and L6797-7174), so an odd number of quotes means one is open.
 * - Other records (`jb`, `sy`, `pr`, `kwl`, `wr`) follow the [Csv] rule.
 *
 * Where a record ends can be lost, and with it where the next one starts; [desync] then says why,
 * the record comes back [Line.truncated], and every later call returns physical lines as they are,
 * which callers must not read as records:
 * - [Desync.TOO_LONG]: a line, or a joined record, ran past [LineReader.MAX_LINE_BYTES] characters.
 *   Real names are far shorter: the platform caps tags at 1024 characters.
 * - [Desync.UNESCAPED_TAG]: a wakelock tag printed inline by the one branch that does not double
 *   its quotes, `,w=<uid>:"<tag>"` on a record whose wakelock bit did not change (android-17.0.0_r1
 *   L6723-6733, android-16.0.0_r4 L6691-6701). Its end cannot be found, so an app could hide records
 *   in it or forge them. It is the record's first quoted text, since the battery fields and state
 *   bits before it hold none, so it is recognised exactly.
 */
internal class CheckinLines(
    private val lines: LineReader,
) {
    val offset: Long get() = lines.offset

    /** Why record boundaries were lost; null while they are known. */
    var desync: Desync? = null
        private set

    enum class Desync { TOO_LONG, UNESCAPED_TAG }

    /** The next record, or null at the end of the stream. */
    fun next(): Line? {
        val first = lines.next() ?: return null
        return when {
            desync != null -> first
            first.truncated -> lost(first, Desync.TOO_LONG)
            unescapedTag(first.text) -> lost(first, Desync.UNESCAPED_TAG)
            !first.terminated || !continues(first.text) -> first
            else -> join(first)
        }
    }

    private fun lost(
        line: Line,
        why: Desync,
    ): Line {
        desync = why
        return Line(line.offset, line.text, line.terminated, truncated = true)
    }

    private fun join(first: Line): Line {
        val text = StringBuilder(first.text)
        var result: Line? = null
        while (result == null) {
            val more = lines.next()
            if (more == null) {
                result = Line(first.offset, text.toString(), terminated = false, truncated = false)
            } else {
                text.append('\n').append(more.text)
                result =
                    when {
                        more.truncated || text.length > LineReader.MAX_LINE_BYTES -> {
                            lost(Line(first.offset, text.toString(), more.terminated, truncated = true), Desync.TOO_LONG)
                        }

                        !more.terminated || !continues(text) -> {
                            Line(first.offset, text.toString(), more.terminated, truncated = false)
                        }

                        else -> {
                            null
                        }
                    }
            }
        }
        return result
    }

    internal companion object {
        const val POOL_PREFIX = "9,hsp,"
        const val HISTORY_PREFIX = "9,h,"
        private const val DATA_PREFIX = "9,"
        private const val QUOTE = '"'
        private const val ESCAPE = '\\'
        private const val POOL_STRING_FIELD = 4

        /** `,w=<formatted uid>:` right before a history record's first quote. */
        private val UNESCAPED_TAG_OPENING = Regex(",w=[0-9a-z]{1,24}:$")

        /** True when [text] ends inside a quoted name. */
        fun continues(text: CharSequence): Boolean =
            when {
                text.startsWith(POOL_PREFIX) -> poolStringOpen(text)
                text.startsWith(HISTORY_PREFIX) -> text.count { it == QUOTE } % 2 == 1
                text.startsWith(DATA_PREFIX) -> Csv.hasOpenQuote(text.toString())
                else -> false
            }

        /** True when a history record's first quote opens a tag that does not double its quotes. */
        fun unescapedTag(text: String): Boolean {
            if (!text.startsWith(HISTORY_PREFIX)) return false
            val quote = text.indexOf(QUOTE)
            return quote > 0 && UNESCAPED_TAG_OPENING.containsMatchIn(text.substring(0, quote))
        }

        /** `9,hsp,<index>,<uid>,"<escaped>"`: true when the string has no unescaped closing quote. */
        private fun poolStringOpen(text: CharSequence): Boolean {
            var i = 0
            var commas = 0
            while (i < text.length && commas < POOL_STRING_FIELD) {
                if (text[i] == ',') commas++
                i++
            }
            if (i >= text.length || text[i] != QUOTE) return false
            i++
            var open = true
            while (open && i < text.length) {
                when (text[i]) {
                    ESCAPE -> i += 2
                    QUOTE -> open = false
                    else -> i++
                }
            }
            return open
        }
    }
}

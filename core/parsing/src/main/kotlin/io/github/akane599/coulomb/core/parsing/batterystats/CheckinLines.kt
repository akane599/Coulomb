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
 *   One branch, a wakelock tag on a record whose wakelock bit did not change, does not double
 *   them; a quote in such a tag joins the next record by mistake.
 * - Other records (`jb`, `sy`, `pr`, `kwl`, `wr`) follow the [Csv] rule.
 *
 * A joined record longer than [LineReader.MAX_LINE_BYTES] characters or [MAX_JOINED_LINES] lines
 * comes back [Line.truncated], with the lines read so far, and reading goes on after it.
 */
internal class CheckinLines(
    private val lines: LineReader,
) {
    val offset: Long get() = lines.offset

    /** The next record, or null at the end of the stream. */
    fun next(): Line? {
        val first = lines.next() ?: return null
        if (!first.terminated || first.truncated || !continues(first.text)) return first
        val text = StringBuilder(first.text)
        var joined = 1
        var result: Line? = null
        while (result == null) {
            val more = lines.next()
            if (more == null) {
                result = Line(first.offset, text.toString(), terminated = false, truncated = false)
            } else {
                text.append('\n').append(more.text)
                joined++
                result =
                    when {
                        more.truncated || text.length > LineReader.MAX_LINE_BYTES || joined > MAX_JOINED_LINES -> {
                            Line(first.offset, text.toString(), more.terminated, truncated = true)
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
        /** Far above any real name; the platform caps pool strings at 1024 characters. */
        const val MAX_JOINED_LINES = 64

        const val POOL_PREFIX = "9,hsp,"
        const val HISTORY_PREFIX = "9,h,"
        private const val DATA_PREFIX = "9,"
        private const val QUOTE = '"'
        private const val ESCAPE = '\\'
        private const val POOL_STRING_FIELD = 4

        /** True when [text] ends inside a quoted name. */
        fun continues(text: CharSequence): Boolean =
            when {
                text.startsWith(POOL_PREFIX) -> poolStringOpen(text)
                text.startsWith(HISTORY_PREFIX) -> text.count { it == QUOTE } % 2 == 1
                text.startsWith(DATA_PREFIX) -> Csv.hasOpenQuote(text.toString())
                else -> false
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

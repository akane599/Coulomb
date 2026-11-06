// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.text

/**
 * Comma splitting for `dumpsys` checkin lines (ADR-0013).
 *
 * The writers quote some names (`"\"" + name + "\""`) without escaping anything inside, so a quoted
 * field may hold commas. It runs from its opening quote to the next quote that is followed by a
 * comma or the end of the line, and the quotes are dropped. A quoted field with no such closing
 * quote runs to the end of the line. A name that itself holds `",` ends early and shifts the
 * columns after it; the format cannot tell the two apart.
 */
internal object Csv {
    fun split(line: String): List<String> {
        val fields = ArrayList<String>(INITIAL_FIELDS)
        var i = 0
        val n = line.length
        while (true) {
            if (i < n && line[i] == QUOTE) {
                val close = closingQuote(line, i + 1)
                if (close < 0) {
                    fields += line.substring(i + 1)
                    return fields
                }
                fields += line.substring(i + 1, close)
                i = close + 1
            } else {
                val comma = line.indexOf(COMMA, i)
                val end = if (comma < 0) n else comma
                fields += line.substring(i, end)
                i = end
            }
            if (i >= n) return fields
            i++ // the comma
        }
    }

    /**
     * True when a quoted field has no closing quote on [line]. The writers do not escape line
     * breaks, so the name goes on in the next line ([io.github.akane599.coulomb.core.parsing.batterystats.CheckinLines]).
     */
    fun hasOpenQuote(line: String): Boolean {
        var i = 0
        val n = line.length
        while (i < n) {
            if (line[i] == QUOTE) {
                val close = closingQuote(line, i + 1)
                if (close < 0) return true
                i = close + 1
            } else {
                val comma = line.indexOf(COMMA, i)
                if (comma < 0) return false
                i = comma
            }
            i++ // the comma
        }
        return false
    }

    private fun closingQuote(
        line: String,
        from: Int,
    ): Int {
        var q = line.indexOf(QUOTE, from)
        while (q >= 0) {
            if (q + 1 == line.length || line[q + 1] == COMMA) return q
            q = line.indexOf(QUOTE, q + 1)
        }
        return -1
    }

    private const val QUOTE = '"'
    private const val COMMA = ','
    private const val INITIAL_FIELDS = 16
}

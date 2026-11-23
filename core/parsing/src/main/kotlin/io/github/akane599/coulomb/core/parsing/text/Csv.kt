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
    /** Every field may be quoted. */
    fun split(line: String): List<String> = split(line, ANY_FIELD)

    /**
     * Only field [quoted] may be quoted ([NO_FIELD] for none): in every other field a quote is a
     * character like any other. For lines whose writer quotes one known field, so that a quote in
     * an app's text elsewhere opens nothing.
     */
    fun split(
        line: String,
        quoted: Int,
    ): List<String> {
        val fields = ArrayList<String>(INITIAL_FIELDS)
        var i = 0
        val n = line.length
        while (true) {
            val mayBeQuoted = quoted == ANY_FIELD || quoted == fields.size
            if (mayBeQuoted && i < n && line[i] == QUOTE) {
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

    /** True when field [field] of [line] starts with a quote that has no closing quote. Other fields' quotes are not looked at. */
    fun hasOpenQuoteAt(
        line: String,
        field: Int,
    ): Boolean {
        var start = 0
        repeat(field) {
            start = line.indexOf(COMMA, start) + 1
            if (start == 0) return false
        }
        return start < line.length && line[start] == QUOTE && closingQuote(line, start + 1) < 0
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

    const val ANY_FIELD = -1
    const val NO_FIELD = -2
    private const val QUOTE = '"'
    private const val COMMA = ','
    private const val INITIAL_FIELDS = 16
}

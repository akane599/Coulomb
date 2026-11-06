// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.parse

/**
 * A section a parser could not read (SPEC 5.1). [offset] is the byte offset of the line or field
 * where the problem starts, counted from the start of the stream.
 */
public data class ParseIssue(
    val offset: Long,
    val section: String,
    val message: String,
)

/**
 * How a parse went, kept next to its result (ADR-0013).
 *
 * [complete] is false when the input ended early or a required part was missing; the result then
 * holds what was read before that point and nothing invented. [unknown] counts names the parser
 * did not recognise (sections, keys, checkin ids, extra columns); values are never kept, so it is
 * safe to show in the capability report.
 */
public data class ParseReport(
    val source: String,
    val bytesRead: Long,
    val complete: Boolean,
    val issues: List<ParseIssue>,
    /** All issues seen; [issues] keeps only the first [MAX_ISSUES]. */
    val issueCount: Int,
    val unknown: Map<String, Int>,
) {
    val unknownTotal: Int get() = unknown.values.sum()

    public companion object {
        public const val MAX_ISSUES: Int = 50
    }
}

/** A parser's value together with its [report]. */
public data class Parsed<out T>(
    val value: T,
    val report: ParseReport,
)

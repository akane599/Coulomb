// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing

import io.github.akane599.coulomb.core.model.parse.ParseIssue
import io.github.akane599.coulomb.core.model.parse.ParseReport

/**
 * Collects a parser's issues and unknown names while it runs (SPEC 5.1, ADR-0013). Unknown names
 * are field, key or section names only; callers never pass values, which may identify the user.
 */
internal class ParseContext(
    private val source: String,
) {
    private val issues = ArrayList<ParseIssue>()
    private var issueCount = 0
    private val unknown = LinkedHashMap<String, Int>()

    fun issue(
        offset: Long,
        section: String,
        message: String,
    ) {
        issueCount++
        if (issues.size < ParseReport.MAX_ISSUES) issues += ParseIssue(offset, section, message.take(MAX_MESSAGE))
    }

    fun unknown(
        name: String,
        count: Int = 1,
    ) {
        // A hostile or broken dump must not grow this map without bound. Names are cut before the
        // lookup, so a long name already counted keeps counting once the map is full.
        val cut = name.take(MAX_NAME)
        val key = if (unknown.size < MAX_UNKNOWN_NAMES || cut in unknown) cut else OTHER
        unknown[key] = (unknown[key] ?: 0) + count
    }

    fun report(
        bytesRead: Long,
        complete: Boolean,
    ): ParseReport = ParseReport(source, bytesRead, complete, issues.toList(), issueCount, unknown.toMap())

    private companion object {
        const val MAX_MESSAGE = 200
        const val MAX_NAME = 80
        const val MAX_UNKNOWN_NAMES = 200
        const val OTHER = "<other>"
    }
}

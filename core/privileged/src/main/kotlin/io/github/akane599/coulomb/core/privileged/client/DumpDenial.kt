// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import java.io.BufferedInputStream
import java.io.InputStream

/**
 * A refused dump still "succeeds": the service writes one line of text and `Debug.dumpService`
 * returns true. Counting that line as a dump would be dishonest (SPEC 0.2), so the head of every
 * stream is checked for the two refusal forms before the consumer sees it:
 *
 * - `DumpUtils.logMessage(pw, "Permission Denial: can't dump " + tag + ...)` in
 *   core/java/com/android/internal/util/DumpUtils.java, android-16.0.0_r4 and android-17.0.0_r1
 *   L98-108, L131-138, L160-162;
 * - `pw.println("Security exception: " + e.getMessage())` in `Binder.doDump`,
 *   core/java/android/os/Binder.java, android-16.0.0_r4 L1100-1107, android-17.0.0_r1 L1005-1012.
 */
internal object DumpDenial {
    private val PREFIXES = listOf("Permission Denial: can't dump ", "Security exception: ").map { it.toByteArray(Charsets.US_ASCII) }
    private val PEEK = PREFIXES.maxOf { it.size }
    private const val MAX_LINE = 1024
    private const val NEWLINE = '\n'.code

    /** The refusal line when [input] starts with one; otherwise null and [input] is back at its start. */
    fun check(input: BufferedInputStream): String? {
        input.mark(MAX_LINE)
        val head = ByteArray(PEEK)
        var filled = 0
        while (filled < PEEK) {
            val n = input.read(head, filled, PEEK - filled)
            if (n < 0) break
            filled += n
        }
        if (PREFIXES.none { prefix -> filled >= prefix.size && (prefix.indices).all { head[it] == prefix[it] } }) {
            input.reset()
            return null
        }
        return String(head, 0, filled, Charsets.UTF_8) + restOfLine(input, MAX_LINE - filled)
    }

    private fun restOfLine(
        input: InputStream,
        limit: Int,
    ): String {
        val out = StringBuilder()
        while (out.length < limit) {
            val b = input.read()
            if (b < 0 || b == NEWLINE) break
            out.append(b.toChar())
        }
        return out.toString()
    }
}

/** What the consumer wrapper hands back: the caller's value, or the refusal line it found instead. */
internal sealed interface Sniffed<out R> {
    data class Value<R>(
        val value: R,
    ) : Sniffed<R>

    data class Denied(
        val line: String,
    ) : Sniffed<Nothing>
}

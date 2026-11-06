// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.text

import io.github.akane599.coulomb.core.parsing.ParseContext
import io.github.akane599.coulomb.core.parsing.io.Line
import io.github.akane599.coulomb.core.parsing.io.LineReader
import io.github.akane599.coulomb.core.parsing.io.MeteredSource
import java.io.IOException
import java.io.InputStream

/**
 * The line loop every `dumpsys` text parser shares (SPEC 5.1, ADR-0013): exact offsets, a cap on
 * line length, and no exception escapes.
 *
 * A last line the stream cut before its newline is never handed on, because its value may be cut
 * too (`level: 8` of `level: 83`). A cut exactly at a line boundary is invisible here; the transport
 * reports it (ADR-0009).
 */
internal class TextDump(
    input: InputStream,
    private val ctx: ParseContext,
    private val section: String,
) {
    private val meter = MeteredSource(input)
    private val lines = LineReader(meter)

    /** Bytes consumed so far. */
    val bytesRead: Long get() = meter.offset

    /** Lines longer than [LineReader.MAX_LINE_BYTES], which were skipped. */
    var lostLines: Int = 0
        private set

    /**
     * Calls [onLine] with every whole line. Returns false when the stream ended inside a line or
     * failed; the reason is already an issue.
     */
    @Suppress("TooGenericExceptionCaught") // The parser boundary: bad input must never throw (SPEC 5.1).
    fun forEachLine(onLine: (Line) -> Unit): Boolean {
        var clean = true
        try {
            var line = lines.next()
            while (line != null && line.terminated) {
                if (line.truncated) {
                    lostLines++
                    ctx.issue(line.offset, section, "line longer than ${LineReader.MAX_LINE_BYTES} bytes")
                } else {
                    onLine(line)
                }
                line = lines.next()
            }
            if (line != null) {
                clean = false
                ctx.issue(line.offset, section, "ended inside a line")
            }
        } catch (e: IOException) {
            clean = false
            ctx.issue(lines.offset, section, "ended early: ${e.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            clean = false
            ctx.issue(lines.offset, section, "malformed: ${e.javaClass.simpleName}")
        }
        return clean
    }
}

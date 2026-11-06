// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.io

/** One line of a text dump, without its `\n` or `\r\n`. */
internal class Line(
    /** Byte offset of the line's first byte from the start of the stream. */
    val offset: Long,
    val text: String,
    /** False for a last line the stream cut before its newline. */
    val terminated: Boolean,
    /** True when the line was longer than the cap; [text] holds only its first bytes. */
    val truncated: Boolean,
)

/**
 * Reads a text dump line by line with exact byte offsets and a line-length cap (ADR-0013).
 *
 * A line longer than [maxLineBytes] keeps its first [maxLineBytes] bytes and the rest is skipped up
 * to the next newline, so a broken dump without newlines cannot fill the heap. Bytes are decoded as
 * UTF-8; a sequence the cap splits becomes U+FFFD.
 */
internal class LineReader(
    private val meter: MeteredSource,
    private val maxLineBytes: Long = MAX_LINE_BYTES,
) {
    private val source = meter.source

    /** Offset of the next unread byte, which is also the number of bytes consumed. */
    val offset: Long get() = meter.offset

    /** The next line, or null at the end of the stream. */
    fun next(): Line? {
        val start = meter.offset
        val newline = source.indexOf(NEWLINE, 0, maxLineBytes)
        val buffered = source.buffer.size
        return when {
            newline >= 0 -> {
                val text = source.readUtf8(newline)
                source.skip(1)
                Line(start, text.removeSuffix("\r"), terminated = true, truncated = false)
            }

            buffered == 0L -> {
                null
            }

            // indexOf already asked upstream for maxLineBytes, so the stream is exhausted.
            buffered < maxLineBytes -> {
                Line(start, source.readUtf8(), terminated = false, truncated = false)
            }

            else -> {
                val text = source.readUtf8(maxLineBytes)
                Line(start, text, terminated = skipRestOfLine(), truncated = true)
            }
        }
    }

    /** Skips to just past the next newline; false when the stream ends first. */
    private fun skipRestOfLine(): Boolean {
        while (true) {
            val newline = source.indexOf(NEWLINE, 0, SKIP_CHUNK)
            if (newline >= 0) {
                source.skip(newline + 1)
                return true
            }
            val buffered = source.buffer.size
            if (buffered == 0L) return false
            source.skip(minOf(buffered, SKIP_CHUNK))
        }
    }

    internal companion object {
        /** No real dump line is close: the longest in the fixtures is under 1 KB. */
        const val MAX_LINE_BYTES = 64L * 1024
        private const val SKIP_CHUNK = 8L * 1024
        private const val NEWLINE = '\n'.code.toByte()
    }
}

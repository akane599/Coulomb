// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.io

import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import okio.source
import java.io.InputStream

/** Counts the bytes pulled from upstream, so offsets are exact without any per-byte work. */
private class CountingSource(
    delegate: Source,
) : ForwardingSource(delegate) {
    var bytesRead = 0L
        private set

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        val n = super.read(sink, byteCount)
        if (n > 0) bytesRead += n
        return n
    }
}

/** A buffered view of a dump stream that knows the byte offset of its next unread byte (ADR-0013). */
internal class MeteredSource(
    input: InputStream,
) {
    private val counting = CountingSource(input.source())
    val source: BufferedSource = counting.buffer()

    /** Offset of the next byte a reader will see. */
    val offset: Long get() = counting.bytesRead - source.buffer.size
}

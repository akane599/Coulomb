// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.worker

import io.github.akane599.coulomb.core.model.kernel.KernelLogAllowlist
import java.io.OutputStream

/**
 * Turns the records `read(2)` returns from `/dev/kmsg` into the S27 stream that `KernelLogParser`
 * reads (ADR-0029 §2). Pure, so the filter and the gap accounting are tested on the JVM.
 *
 * Each record is `<prio>,<seq>,<µs>,<flags>[,…];<text>\n` followed by optional ` KEY=value` lines
 * (Documentation/ABI/testing/dev-kmsg). Only the first line of an allowlisted kernel record is
 * written. Kernel records have facility 0; `devkmsg_write` forces a non-zero facility on anything
 * user space writes (kernel/printk/printk.c, android16-6.12 L767-827), so user space cannot pass a
 * line off as a kernel one. A debuggable build also lets through user records carrying
 * [TEST_MARKER], which the emulator mode-switch test writes (ADR-0028 §5).
 *
 * The trailer is written only when the copy reached the ring's end. A copy stopped by a cap ends
 * without it, like one cut by time: its last kept record is not where the ring stood, so the
 * parser reads it as cut short and the next read continues after it (ADR-0029 §5).
 */
internal class KernelLogCopier(
    private val bootId: String,
    /** Records up to this one are skipped; null reads from the ring's start. */
    private val afterSeq: Long?,
    private val maxKept: Int,
    private val acceptTestMarkers: Boolean,
    private val sink: OutputStream,
) {
    private var first: Long? = null
    private var last: Long? = null
    private var lost = 0L
    private var kept = 0
    private var headerWritten = false

    /** Feeds one record as read; false once [maxKept] records were written and the copy should stop. */
    fun record(
        buf: ByteArray,
        length: Int,
    ): Boolean {
        val lineEnd = indexOf(buf, NEWLINE, 0, length).let { if (it < 0) length else it }
        // A record without a readable header has no sequence number; if one is ever skipped here,
        // the next record's number shows the jump as a gap.
        val header = header(buf, lineEnd)
        if (header != null && advance(header.seq)) {
            val text = String(buf, header.semicolon + 1, lineEnd - header.semicolon - 1, Charsets.US_ASCII)
            if (allowed(header.prio, text)) {
                sink.write(buf, 0, lineEnd)
                sink.write(NEWLINE.toInt())
                kept++
            }
        }
        return kept < maxKept
    }

    private class Header(
        val prio: Int,
        val seq: Long,
        val semicolon: Int,
    )

    private fun header(
        buf: ByteArray,
        lineEnd: Int,
    ): Header? {
        val semicolon = indexOf(buf, SEMICOLON, 0, lineEnd)
        if (semicolon <= 0) return null
        val fields = String(buf, 0, semicolon, Charsets.US_ASCII).split(',')
        val prio = fields.getOrNull(0)?.toIntOrNull()
        val seq = fields.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }
        return if (prio != null && seq != null) Header(prio, seq, semicolon) else null
    }

    /** Moves the copy past [seq], writing the header and any gap; false for a record already passed. */
    private fun advance(seq: Long): Boolean {
        val previous = last
        val passed = (afterSeq != null && seq <= afterSeq) || (previous != null && seq <= previous)
        if (passed) return false
        if (previous == null) {
            first = seq
            writeHeader()
            if (afterSeq != null && seq > afterSeq + 1) gap(afterSeq + 1, seq - 1)
        } else if (seq > previous + 1) {
            gap(previous + 1, seq - 1)
        }
        last = seq
        return true
    }

    /** Ends the stream; [reachedEnd] is false when the copy stopped before the ring had no more. */
    fun finish(reachedEnd: Boolean) {
        if (!headerWritten) writeHeader()
        if (reachedEnd) sink.write("#end last=${last ?: NONE} lost=$lost\n".toByteArray(Charsets.US_ASCII))
        sink.flush()
    }

    private fun allowed(
        prio: Int,
        text: String,
    ): Boolean {
        val facility = prio ushr FACILITY_SHIFT
        val source = facility == KERNEL_FACILITY || (acceptTestMarkers && facility == USER_FACILITY && TEST_MARKER in text)
        return source && KernelLogAllowlist.keeps(text)
    }

    private fun writeHeader() {
        headerWritten = true
        sink.write("#begin boot=$bootId after=${afterSeq ?: NONE} first=${first ?: NONE}\n".toByteArray(Charsets.US_ASCII))
    }

    private fun gap(
        from: Long,
        to: Long,
    ) {
        lost += to - from + 1
        sink.write("#gap from=$from to=$to\n".toByteArray(Charsets.US_ASCII))
    }

    private fun indexOf(
        buf: ByteArray,
        b: Byte,
        from: Int,
        to: Int,
    ): Int {
        for (i in from until to) if (buf[i] == b) return i
        return -1
    }

    internal companion object {
        const val TEST_MARKER = "coulomb-test-"
        private const val NEWLINE = '\n'.code.toByte()
        private const val SEMICOLON = ';'.code.toByte()
        private const val NONE = "-"

        /** `(facility << 3) | level` (kernel/printk/printk.c `info_print_ext_header`). */
        private const val FACILITY_SHIFT = 3
        private const val KERNEL_FACILITY = 0
        private const val USER_FACILITY = 1
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged

/**
 * Deterministic byte pattern for the transport test (P1 gate: "a 20 MB dump streams without OOM").
 * The worker writes it, the app checks every byte at its offset, so a dropped, duplicated or
 * reordered chunk is caught as well as a short stream.
 */
internal object TestPattern {
    // Mixing in higher bits makes the pattern differ between 128-byte, 8 KB and 2 MB periods.
    private const val SHIFT_A = 7
    private const val SHIFT_B = 13
    private const val SHIFT_C = 21

    fun byteAt(offset: Long): Byte = (offset xor (offset ushr SHIFT_A) xor (offset ushr SHIFT_B) xor (offset ushr SHIFT_C)).toByte()

    fun fill(
        buffer: ByteArray,
        startOffset: Long,
        length: Int,
    ) {
        for (i in 0 until length) buffer[i] = byteAt(startOffset + i)
    }
}

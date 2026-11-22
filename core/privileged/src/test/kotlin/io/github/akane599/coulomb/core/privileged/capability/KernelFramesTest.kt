// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.capability

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Audit BUG-006: the worker writes a file's header before its payload, so a cut can fall between them. */
class KernelFramesTest {
    private fun walk(text: String) = KernelFrames.walk(text.toByteArray())

    @Test
    fun aPathCountsAsReadOnlyWithItsWholePayload() {
        val first = "@/a 2\nxy\n"

        val inHeader = walk(first + "@/b")
        assertThat(inHeader.read).containsExactly("/a")
        assertThat(inHeader.ended).isFalse()

        val inPayload = walk(first + "@/b 40\n0.1")
        assertThat(inPayload.read).containsExactly("/a")
        assertThat(inPayload.ended).isFalse()

        val beforeItsNewline = walk(first + "@/b 3\nabc")
        assertThat(beforeItsNewline.read).containsExactly("/a")

        val beforeTheEnd = walk(first + "@/b 3\nabc\n")
        assertThat(beforeTheEnd.read).containsExactly("/a", "/b")
        assertThat(beforeTheEnd.ended).isFalse()

        val whole = walk(first + "@/b 3\nabc\n!/c 13\n#end\n")
        assertThat(whole.read).containsExactly("/a", "/b")
        assertThat(whole.failed).containsExactly("/c", 13)
        assertThat(whole.ended).isTrue()
    }

    @Test
    fun aLengthPastTheStreamIsNotRead() {
        val huge = walk("@/a ${Int.MAX_VALUE}\nx\n#end\n")
        assertThat(huge.read).isEmpty()
        assertThat(huge.ended).isFalse()
        // A payload that only looks like frames is skipped by its length, not read as frames.
        val nested = walk("@/a 7\n#end\n!x\n#end\n")
        assertThat(nested.read).containsExactly("/a")
        assertThat(nested.failed).isEmpty()
        assertThat(nested.ended).isTrue()
    }
}

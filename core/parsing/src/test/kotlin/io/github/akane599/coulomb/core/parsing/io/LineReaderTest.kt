// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.io

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

class LineReaderTest {
    private fun read(
        bytes: ByteArray,
        cap: Long = LineReader.MAX_LINE_BYTES,
    ): List<Line> {
        val reader = LineReader(MeteredSource(ByteArrayInputStream(bytes)), cap)
        return generateSequence { reader.next() }.toList()
    }

    private fun read(text: String) = read(text.encodeToByteArray())

    @Test
    fun offsetsCountBytesNotCharacters() {
        // "ç" and "ı" are two bytes each in UTF-8.
        val lines = read("çı\nab\n")
        assertThat(lines.map { it.text }).containsExactly("çı", "ab").inOrder()
        assertThat(lines.map { it.offset }).containsExactly(0L, 5L).inOrder()
        assertThat(lines.all { it.terminated && !it.truncated }).isTrue()
    }

    @Test
    fun crlfEndingsAreStripped() {
        assertThat(read("a\r\nb\r\n").map { it.text }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun aLastLineWithoutNewlineIsUnterminated() {
        val lines = read("a\nb")
        assertThat(lines).hasSize(2)
        assertThat(lines[1].text).isEqualTo("b")
        assertThat(lines[1].terminated).isFalse()
    }

    @Test
    fun emptyInputHasNoLinesAndEmptyLinesAreKept() {
        assertThat(read("")).isEmpty()
        assertThat(read("\n\n").map { it.text }).containsExactly("", "").inOrder()
    }

    @Test
    fun aLongLineIsCutAtTheCapAndTheNextLineKeepsItsOffset() {
        val long = "x".repeat(LONG)
        val lines = read("$long\nnext\n".encodeToByteArray(), cap = CAP)
        assertThat(lines).hasSize(2)
        assertThat(lines[0].truncated).isTrue()
        assertThat(lines[0].terminated).isTrue()
        assertThat(lines[0].text).hasLength(CAP.toInt())
        assertThat(lines[1].text).isEqualTo("next")
        assertThat(lines[1].offset).isEqualTo(LONG + 1L)
    }

    @Test
    fun aLongLineThatNeverEndsIsUnterminated() {
        val lines = read("x".repeat(LONG).encodeToByteArray(), cap = CAP)
        assertThat(lines).hasSize(1)
        assertThat(lines[0].truncated).isTrue()
        assertThat(lines[0].terminated).isFalse()
    }

    @Test
    fun aLineExactlyAtTheCapIsWhole() {
        val exact = "y".repeat(CAP.toInt() - 1)
        val lines = read("$exact\n".encodeToByteArray(), cap = CAP)
        assertThat(lines.single().text).isEqualTo(exact)
        assertThat(lines.single().truncated).isFalse()
    }

    private companion object {
        const val CAP = 16L
        const val LONG = 40_000
    }
}

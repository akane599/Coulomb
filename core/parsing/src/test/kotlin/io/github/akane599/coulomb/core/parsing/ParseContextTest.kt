// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ParseContextTest {
    @Test
    fun aLongNameKeepsCountingOnceTheMapIsFull() {
        val ctx = ParseContext("test")
        val long = "n".repeat(100)
        ctx.unknown(long)
        repeat(300) { ctx.unknown("name$it") }
        ctx.unknown(long)
        val unknown = ctx.report(0, complete = true).unknown
        assertThat(unknown).hasSize(201)
        assertThat(unknown).containsEntry("n".repeat(80), 2)
        assertThat(unknown).containsEntry("<other>", 101)
    }
}

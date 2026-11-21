// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.data.NamedReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferencesSheetTest {
    private fun named(
        id: Long,
        wallMs: Long,
    ) = NamedReference(id, DrainSpan.Mark(id * 10, "R$id", wallMs))

    @Test
    fun aThirdChoiceDropsTheFirst() {
        assertEquals(listOf(1L), toggled(emptyList(), 1))
        assertEquals(listOf(1L, 2L), toggled(listOf(1), 2))
        assertEquals(listOf(2L, 3L), toggled(listOf(1, 2), 3))
        assertEquals(listOf(2L), toggled(listOf(1, 2), 1))
    }

    @Test
    fun twoChoicesRunFromTheOlderToTheNewer() {
        val older = named(1, 1_000)
        val newer = named(2, 2_000)
        assertEquals(DrainSpan.Between(older.mark, newer.mark), spanOf(listOf(newer, older)))
        assertEquals(DrainSpan.Between(older.mark, null), spanOf(listOf(older)))
        assertNull(spanOf(emptyList()))
    }

    @Test
    fun twoMarksAtTheSameMomentAreSinceThem() {
        val a = named(1, 1_000)
        val b = named(2, 1_000)
        assertEquals(DrainSpan.Between(a.mark, null), spanOf(listOf(a, b)))
    }
}

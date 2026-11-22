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
    fun twoNamesOnOneSnapshotAreSinceIt() {
        val a = named(1, 1_000)
        val b = NamedReference(2, a.mark.copy(name = "Again"))
        assertEquals(DrainSpan.Between(a.mark, null), spanOf(listOf(a, b)))
    }

    /** Audit BUG-032: the boot and elapsed clock orders two marks, whatever the wall clock said. */
    @Test
    fun aClockSetBackDoesNotSwapTwoMarks() {
        val earlier = NamedReference(1, DrainSpan.Mark(10, "Before", 5_000), bootSeq = 3, elapsedNs = 100)
        // Taken later, after the clock was set back a few seconds.
        val later = NamedReference(2, DrainSpan.Mark(20, "After", 2_000), bootSeq = 3, elapsedNs = 900)
        assertEquals(DrainSpan.Between(earlier.mark, later.mark), spanOf(listOf(later, earlier)))
        // Two marks at the same wall time on different snapshots keep both ends.
        val same = NamedReference(3, DrainSpan.Mark(30, "Same", 5_000), bootSeq = 3, elapsedNs = 400)
        assertEquals(DrainSpan.Between(earlier.mark, same.mark), spanOf(listOf(same, earlier)))
    }
}

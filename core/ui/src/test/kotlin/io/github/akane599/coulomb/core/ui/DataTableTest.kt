// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui

import io.github.akane599.coulomb.core.model.Provenance
import org.junit.Assert.assertThrows
import org.junit.Test

class DataTableTest {
    private val columns = listOf("Time", "Level", "Power")
    private val rows = listOf(listOf("18:31", "21%", "5.6 W"))

    @Test
    fun aTableTakesOneSourceForAllOneForEachRowOrOneForEachColumn() {
        DataTable("Curve", columns, rows, Provenance.MODELED)
        DataTable("Curve", columns, rows, provenance = null, rowProvenance = listOf(Provenance.DERIVED))
        DataTable("Curve", columns, rows, provenance = null, columnProvenance = listOf(null, Provenance.MODELED, Provenance.DERIVED))
    }

    @Test
    fun aTableWithNoSourceOrTwoKindsIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { DataTable("Curve", columns, rows, provenance = null) }
        assertThrows(IllegalArgumentException::class.java) {
            DataTable("Curve", columns, rows, Provenance.MODELED, columnProvenance = listOf(null, null, Provenance.DERIVED))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DataTable("Curve", columns, rows, provenance = null, columnProvenance = listOf(Provenance.MODELED))
        }
    }
}

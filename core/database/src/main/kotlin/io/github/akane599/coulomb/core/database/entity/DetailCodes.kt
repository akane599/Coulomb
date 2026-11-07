// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.TypeConverter
import io.github.akane599.coulomb.core.model.Provenance

/**
 * Window detail stores provenance as a small integer code, not by name (ADR-0021): the detail
 * tables hold hundreds of thousands of rows, and SQLite stores the codes 0 and 1 in no bytes at
 * all. A code is the entry's position in [PROVENANCES]: append new entries at the end, never
 * reorder or remove one. `DetailCodesTest` fails when an entry has no code.
 */
public object DetailCodes {
    internal val PROVENANCES: List<Provenance> =
        listOf(Provenance.MEASURED, Provenance.MODELED, Provenance.ESTIMATED, Provenance.DERIVED)

    @TypeConverter
    public fun provenanceCode(provenance: Provenance): Int =
        PROVENANCES.indexOf(provenance).also { check(it >= 0) { "No code for provenance $provenance" } }

    @TypeConverter
    public fun provenance(code: Int): Provenance = PROVENANCES.getOrNull(code) ?: error("Unknown provenance code $code")
}

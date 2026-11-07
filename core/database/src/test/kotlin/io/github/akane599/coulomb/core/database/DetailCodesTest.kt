// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.DetailCodes
import io.github.akane599.coulomb.core.model.Provenance
import org.junit.Test

class DetailCodesTest {
    @Test
    fun everyProvenanceHasOneCode() {
        assertThat(DetailCodes.PROVENANCES).containsExactlyElementsIn(Provenance.entries)
        for (provenance in Provenance.entries) {
            assertThat(DetailCodes.provenance(DetailCodes.provenanceCode(provenance))).isEqualTo(provenance)
        }
    }

    @Test
    fun storedCodesNeverMove() {
        // Stored rows and the 1→2 migration keep these codes: a new provenance goes at the end.
        assertThat(DetailCodes.PROVENANCES)
            .containsExactly(Provenance.MEASURED, Provenance.MODELED, Provenance.ESTIMATED, Provenance.DERIVED)
            .inOrder()
    }
}

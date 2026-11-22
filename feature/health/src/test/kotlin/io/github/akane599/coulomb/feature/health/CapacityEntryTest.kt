// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Audit BUG-037: the design capacity field never turns an entry into another number. */
class CapacityEntryTest {
    @Test
    fun aWholeNumberWithGroupingOrAUnitKeepsItsDigits() {
        assertThat(capacityEntry("4855")).isEqualTo("4855")
        assertThat(capacityEntry("4,855")).isEqualTo("4855")
        assertThat(capacityEntry("4.855")).isEqualTo("4855")
        assertThat(capacityEntry("4\u202F855 mAh")).isEqualTo("4855")
        assertThat(capacityEntry(" 5000 MAH ")).isEqualTo("5000")
    }

    @Test
    fun anythingElseStaysAsEnteredAndNothingIsCut() {
        assertThat(capacityEntry("4855.5")).isEqualTo("4855.5")
        assertThat(capacityEntry("4.855,5")).isEqualTo("4.855,5")
        assertThat(capacityEntry("48,55")).isEqualTo("48,55")
        assertThat(capacityEntry("1234567")).isEqualTo("1234567")
    }
}

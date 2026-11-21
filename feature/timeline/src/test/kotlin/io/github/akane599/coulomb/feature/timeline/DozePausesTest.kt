// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import io.github.akane599.coulomb.core.data.TrackSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DozePausesTest {
    @Test
    fun aGapBetweenTwoDozeSpansIsAPause() {
        val doze =
            listOf(
                TrackSpan(0, 600_000, TrackSpan.DOZE_LIGHT),
                // Light straight into deep: no pause.
                TrackSpan(600_000, 6_000_000, TrackSpan.DOZE_DEEP),
                TrackSpan(6_120_000, 9_000_000, TrackSpan.DOZE_DEEP),
                TrackSpan(9_060_000, 9_600_000, TrackSpan.DOZE_LIGHT),
            )
        assertEquals(DozePauses(2, 180_000), DozePauses.of(doze.shuffled()))
    }

    @Test
    fun oneSpanHasNoPausesToCount() {
        assertNull(DozePauses.of(listOf(TrackSpan(0, 600_000, TrackSpan.DOZE_DEEP))))
        assertNull(DozePauses.of(emptyList()))
    }

    @Test
    fun spansThatTouchAreNotAPause() {
        val doze = listOf(TrackSpan(0, 600_000, TrackSpan.DOZE_LIGHT), TrackSpan(600_000, 900_000, TrackSpan.DOZE_DEEP))
        assertEquals(DozePauses(0, 0), DozePauses.of(doze))
    }
}

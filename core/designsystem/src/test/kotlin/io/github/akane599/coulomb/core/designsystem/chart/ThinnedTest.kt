// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinnedTest {
    @Test
    fun aShortChargeKeepsEveryReading() {
        val readings = (0 until 500).map { CurveReading(it * 1_000L, 50f, 10f) }
        assertEquals(readings, thinned(readings))
    }

    @Test
    fun aLongChargeKeepsItsEndsItsSpikeAndItsGap() {
        // Two hours at 1 Hz: a 30 W spike at 3,000 s and no power from 5,000 to 5,010 s.
        val readings =
            (0 until 7_200).map { s ->
                val watts =
                    when (s) {
                        3_000 -> 30f
                        in 5_000..5_010 -> null
                        else -> 15f
                    }
                CurveReading(s * 1_000L, s / 72f, watts)
            }
        val shown = thinned(readings)
        assertTrue(shown.size <= 1_250)
        assertEquals(readings.first(), shown.first())
        assertEquals(readings.last(), shown.last())
        assertTrue(shown.any { it.watts == 30f })
        assertTrue(shown.any { it.watts == null })
        shown.zipWithNext { a, b -> assertTrue(a.wallMs < b.wallMs) }
    }
}

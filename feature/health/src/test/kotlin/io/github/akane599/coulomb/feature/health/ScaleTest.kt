// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleTest {
    @Test
    fun withADesignCapacityTheScaleRunsInTensOfPercentFromEightyToAHundred() {
        val scale = Scale.of(low = 4_540.0, high = 4_680.0, design = 5_000.0)

        assertEquals(4_000.0, scale.from, EPS)
        assertEquals(5_000.0, scale.to, EPS)
        assertEquals(listOf(4_000.0, 4_500.0, 5_000.0), scale.marks)
        assertEquals(100.0, scale.minor, EPS)
    }

    @Test
    fun anIntervalOutsideEightyToAHundredWidensTheScaleToWholeTens() {
        val scale = Scale.of(low = 3_700.0, high = 5_150.0, design = 5_000.0)

        assertEquals(3_500.0, scale.from, EPS)
        assertEquals(5_500.0, scale.to, EPS)
        assertEquals(3_500.0, scale.marks.first(), EPS)
        assertEquals(5_500.0, scale.marks.last(), EPS)
    }

    @Test
    fun withoutADesignCapacityTheScaleHoldsTheIntervalInRoundSteps() {
        val scale = Scale.of(low = 4_540.0, high = 4_680.0, design = null)

        assertTrue(scale.from <= 4_540.0)
        assertTrue(scale.to >= 4_680.0)
        assertTrue(scale.marks.size <= MAX_MARKS)
        val step = scale.marks[1] - scale.marks[0]
        scale.marks.zipWithNext { a, b -> assertEquals(step, b - a, EPS) }
    }

    @Test
    fun aWideIntervalOnALargeBatteryStillTakesRoundSteps() {
        val scale = Scale.of(low = 11_200.0, high = 13_480.0, design = null)

        assertTrue(scale.from <= 11_200.0)
        assertTrue(scale.to >= 13_480.0)
        assertTrue(scale.marks.size in 2..MAX_MARKS)
        val step = scale.marks[1] - scale.marks[0]
        scale.marks.zipWithNext { a, b -> assertEquals(step, b - a, EPS) }
        scale.marks.forEach { assertEquals(0.0, it % step, EPS) }
    }
}

private const val EPS = 1e-6
private const val MAX_MARKS = 6

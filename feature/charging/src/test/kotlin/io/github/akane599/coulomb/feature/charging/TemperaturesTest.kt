// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.charging

import io.github.akane599.coulomb.core.model.dumpsys.ThermalDump
import io.github.akane599.coulomb.core.model.dumpsys.ThermalReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperaturesTest {
    @Test
    fun theCurrentReadingsWarmestFirstWithoutTheEmptyOnes() {
        val t = Temperatures.of(labThermal())
        assertEquals(listOf("cpu-big", "skin", "battery"), t.sensors.map { it.name })
        assertFalse(t.cached)
        assertEquals(1, t.status)
    }

    /** As the S25 Ultra's HAL prints its sub-battery: listed, 0.0, beside readings above it. */
    @Test
    fun anExactZeroBesideWarmReadingsIsNotReported() {
        val dump = labThermal().copy(currentTemperatures = labThermal().currentTemperatures + ThermalReading("SUBBAT", 2, 0, 0))
        assertEquals(listOf("cpu-big", "skin", "battery"), Temperatures.of(dump).sensors.map { it.name })
    }

    @Test
    fun withoutCurrentReadingsTheCachedOnesAreShownAndSaidToBe() {
        val t = Temperatures.of(labThermal().copy(currentTemperatures = emptyList()))
        assertTrue(t.cached)
        assertEquals(listOf("skin"), t.sensors.map { it.name })
    }
}

/** A warm phone: light throttling, the big cores at 61 °C, one sensor without a value. */
internal fun labThermal() =
    ThermalDump(
        statusOverridden = false,
        status = 1,
        halReady = true,
        cachedTemperatures = listOf(ThermalReading("skin", 3, 352, 0)),
        currentTemperatures =
            listOf(
                ThermalReading("battery", 2, 334, 0),
                ThermalReading("skin", 3, 389, 0),
                ThermalReading("cpu-big", 0, 612, 1),
                ThermalReading("npu", 9, null, 0),
            ),
        coolingDevices = emptyList(),
        thresholds = emptyList(),
    )

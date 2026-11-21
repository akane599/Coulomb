// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.entity.RailEnergyEntity
import io.github.akane599.coulomb.core.model.RailNames
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class DrainRailsTest : DrainDbTest() {
    private fun rails(vararg energy: Pair<String, Long>) =
        SnapshotRows(railEnergy = energy.map { (name, uws) -> RailEnergyEntity(0, name, uws, readElapsedNs = null) })

    private val display = RailNames.stored(RailNames.MEASUREMENT, "VSYS_PWR_DISPLAY")
    private val modem = RailNames.stored(RailNames.MEASUREMENT, "VSYS_PWR_MODEM")
    private val gpu = RailNames.stored(RailNames.CONSUMER, "GPU")

    @Test
    fun eachMonitorsEnergyIsTheDifferenceBetweenTheTwoSnapshots() =
        runTest {
            val a = heavy(1_000, rows = rails(display to 1_000_000, modem to 5_000_000, gpu to 0))
            val b = heavy(4_600, rows = rails(display to 37_000_000, modem to 8_600_000, gpu to 7_200_000))
            store(window(a, b), WindowRows(stats = device(onBatteryMs = 3_600_000, screenOffMs = 3_600_000)))

            val report = checkNotNull(reader.read(DrainSpan.Last24Hours, empty()))

            assertThat(report.rails)
                .containsExactly(
                    RailDrain("VSYS_PWR_DISPLAY", rail = true, energyUws = 36_000_000),
                    RailDrain("GPU", rail = false, energyUws = 7_200_000),
                    RailDrain("VSYS_PWR_MODEM", rail = true, energyUws = 3_600_000),
                ).inOrder()
            assertThat(report.rails!!.first().mwh).isEqualTo(10.0)
        }

    @Test
    fun timeOnTheChargerLeavesNoRails() =
        runTest {
            val a = heavy(1_000, rows = rails(display to 1_000_000))
            val b = heavy(4_600, rows = rails(display to 37_000_000))
            store(window(a, b), WindowRows(stats = device(onBatteryMs = 1_800_000, screenOffMs = 1_800_000)))

            assertThat(checkNotNull(reader.read(DrainSpan.Last24Hours, empty())).rails).isNull()
        }

    @Test
    fun aCounterThatWentBackOrAppearedMidwayIsLeftOut() =
        runTest {
            val a = heavy(1_000, rows = rails(display to 9_000_000, modem to 1_000_000))
            val b = heavy(4_600, rows = rails(display to 1_000_000, modem to 2_000_000, gpu to 5_000_000))
            store(window(a, b), WindowRows(stats = device(onBatteryMs = 3_600_000, screenOffMs = 3_600_000)))

            assertThat(checkNotNull(reader.read(DrainSpan.Last24Hours, empty())).rails)
                .containsExactly(RailDrain("VSYS_PWR_MODEM", rail = true, energyUws = 1_000_000))
        }

    @Test
    fun noMonitorsIsNoRails() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(4_600)
            store(window(a, b), WindowRows(stats = device(onBatteryMs = 3_600_000, screenOffMs = 3_600_000)))

            assertThat(checkNotNull(reader.read(DrainSpan.Last24Hours, empty())).rails).isNull()
        }
}

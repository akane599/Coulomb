// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** SPEC 6.9: Coulomb's own share of the battery over the last day of stored windows. */
@RunWith(AndroidJUnit4::class)
internal class SelfCostReaderTest : DrainDbTest() {
    private fun reader() = SelfCostReader(snapshots, windows, sessions, db.deviceDao(), AppDispatchers.production())

    @Test
    fun itsOwnUidOverTheConsecutiveWindowsOfTheLastDay() =
        runTest {
            val old = heavy(0)
            val from = heavy(2 * DAY_S)
            val middle = heavy(2 * DAY_S + 3_600)
            val to = heavy(2 * DAY_S + 7_200)
            // Before the day: not counted.
            store(window(old, from), WindowRows(uids = listOf(uid(OWN, 9.0))))
            store(window(from, middle), WindowRows(uids = listOf(uid(OWN, 1.5), uid(APP, 20.0))))
            // A component row of the same UID is part of its total, not more of it.
            store(window(middle, to), WindowRows(uids = listOf(uid(OWN, 0.5), uid(OWN, 0.4, component = "cpu"))))
            db.deviceDao().upsertProfile(DeviceProfileEntity(FP, "t", "t", 37, 3_700_000, 5_000, "power_profile", 1, 1, true, null, null))

            val cost = checkNotNull(reader().lastDay(OWN, FP))

            assertThat(cost.ownMah).isWithin(1e-9).of(2.0)
            assertThat(cost.pct).isWithin(1e-9).of(0.04)
            assertThat(cost.fromWallMs).isEqualTo(wall(from))
            assertThat(cost.toWallMs).isEqualTo(wall(to))
        }

    @Test
    fun nothingBeforeTwoHeavySnapshotsAndNoShareWithoutACapacity() =
        runTest {
            assertThat(reader().lastDay(OWN, FP)).isNull()
            val from = heavy(0)
            assertThat(reader().lastDay(OWN, FP)).isNull()
            val to = heavy(3_600)
            store(window(from, to), WindowRows(uids = listOf(uid(OWN, 1.0))))

            val cost = checkNotNull(reader().lastDay(OWN, FP))

            assertThat(cost.ownMah).isWithin(1e-9).of(1.0)
            assertThat(cost.pct).isNull()
        }

    private companion object {
        const val OWN = 10_321
        const val FP = "lab/fingerprint"
        const val DAY_S = 86_400L
    }
}

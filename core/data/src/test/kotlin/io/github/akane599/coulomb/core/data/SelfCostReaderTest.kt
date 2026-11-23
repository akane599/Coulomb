// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
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
            store(window(from, middle).modeled(), WindowRows(uids = listOf(uid(OWN, 1.5), uid(APP, 20.0))))
            // A component row of the same UID is part of its total, not more of it.
            store(window(middle, to).modeled(), WindowRows(uids = listOf(uid(OWN, 0.5), uid(OWN, 0.4, component = "cpu"))))
            db.deviceDao().upsertProfile(DeviceProfileEntity(FP, "t", "t", 37, 3_700_000, 5_000, "power_profile", 1, 1, true, null, null))

            val cost = checkNotNull(reader().lastDay(OWN, FP))

            assertThat(cost.ownMah).isWithin(1e-9).of(2.0)
            assertThat(cost.pct).isWithin(1e-9).of(0.04)
            assertThat(cost.fromWallMs).isEqualTo(wall(from))
            assertThat(cost.toWallMs).isEqualTo(wall(to))
            assertThat(cost.partial).isFalse()
        }

    /**
     * Audit BUG-011: the day's start is found on the boot's latest clock. A snapshot stamped while
     * the clock ran three days ahead is not "within the last day" of one stamped after it was put right.
     */
    @Test
    fun aClockSetBackDoesNotStretchTheLastDay() =
        runTest {
            val ahead = heavy(0, wallMs = BASE_WALL + 3 * DAY_S * 1_000)
            val from = heavy(2 * DAY_S, wallMs = BASE_WALL + 2 * DAY_S * 1_000)
            val to = heavy(3 * DAY_S, wallMs = BASE_WALL + 3 * DAY_S * 1_000 + 1)
            store(window(ahead, from).modeled(), WindowRows(uids = listOf(uid(OWN, 9.0))))
            store(window(from, to).modeled(), WindowRows(uids = listOf(uid(OWN, 1.0))))
            db.deviceDao().upsertProfile(DeviceProfileEntity(FP, "t", "t", 37, 3_700_000, 5_000, "power_profile", 1, 1, true, null, null))

            val cost = checkNotNull(reader().lastDay(OWN, FP))

            assertThat(cost.ownMah).isWithin(1e-9).of(1.0)
            assertThat(cost.fromWallMs).isEqualTo(wall(from))
        }

    /** Audit DATA-014: no model's figures is no reading, and a stretch without them makes the reading partial. */
    @Test
    fun withoutAModeledWindowThereIsNoReadingAndAMissingOneMakesItPartial() =
        runTest {
            val from = heavy(0)
            val middle = heavy(3_600)
            val to = heavy(7_200)
            // A window without S5's device section has no model's share to give.
            store(window(from, middle), WindowRows(uids = listOf(uid(OWN, 1.0))))
            assertThat(reader().lastDay(OWN, FP)).isNull()

            store(window(middle, to).modeled(), WindowRows(uids = listOf(uid(OWN, 0.5))))
            val cost = checkNotNull(reader().lastDay(OWN, FP))
            assertThat(cost.partial).isTrue()
        }

    private fun WindowResultEntity.modeled() = copy(modeledMah = 5.0)

    @Test
    fun nothingBeforeTwoHeavySnapshotsAndNoShareWithoutACapacity() =
        runTest {
            assertThat(reader().lastDay(OWN, FP)).isNull()
            val from = heavy(0)
            assertThat(reader().lastDay(OWN, FP)).isNull()
            val to = heavy(3_600)
            store(window(from, to).modeled(), WindowRows(uids = listOf(uid(OWN, 1.0))))

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

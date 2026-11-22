// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Audit BUG-008: a phone that reports no CURRENT_NOW still has its charge counter judged. */
@RunWith(AndroidJUnit4::class)
class CalibrationTrackerTest {
    private lateinit var db: CoulombDatabase

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = CoulombDatabase.configured(Room.inMemoryDatabaseBuilder(context, CoulombDatabase::class.java).allowMainThreadQueries())
    }

    @After
    fun close() = db.close()

    @Test
    fun aCounterWithoutInstantCurrentIsJudged() =
        runTest {
            val profile = DeviceProfileEntity("lab", "t", "t", 37, 3_700_000, null, null, null, null, null, null, null)
            db.deviceDao().upsertProfile(profile)
            var charge = 4_000_000L
            for (minute in 0..59) {
                charge -= 800L + (minute % 3) * 100L
                db.sampleDao().insert(
                    BatterySampleEntity(
                        1,
                        minute * MINUTE_NS,
                        minute * 60_000L,
                        90 - minute / 10,
                        charge,
                        null,
                        null,
                        4_000,
                        300,
                        0,
                        3,
                        true,
                        0,
                        0,
                    ),
                )
            }

            val updated = CalibrationTracker(db.deviceDao(), db.sampleDao()).update(profile, 1, 59 * MINUTE_NS, 59 * 60_000L)

            assertThat(updated.chargeCounterReliable).isTrue()
            // Nothing to scale a current with.
            assertThat(updated.currentScale).isNull()
            assertThat(db.deviceDao().profile("lab")?.chargeCounterReliable).isTrue()
        }

    private companion object {
        const val MINUTE_NS = 60_000_000_000L
    }
}

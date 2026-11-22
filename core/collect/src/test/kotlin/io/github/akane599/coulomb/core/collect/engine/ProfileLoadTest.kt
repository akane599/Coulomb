// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity
import io.github.akane599.coulomb.core.model.DesignCapacity
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A first event whose profile load or label lookup fails (audit BUG-027, DATA-018). */
@RunWith(AndroidJUnit4::class)
class ProfileLoadTest {
    private lateinit var db: CoulombDatabase
    private val clock = FakeClock()
    private val device = FakeDevice(clock)
    private val identity = FakeIdentity()

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = CoulombDatabase.configured(Room.inMemoryDatabaseBuilder(context, CoulombDatabase::class.java).allowMainThreadQueries())
    }

    @After
    fun close() = db.close()

    @Test
    fun aFailedDesignCapacityReadKeepsTheStoredCalibration() =
        runTest {
            db.deviceDao().upsertProfile(
                DeviceProfileEntity(identity.fingerprint, "test", "test", 37, 3_700_000, null, null, 1, 1, true, 0, 0.95),
            )
            val failing =
                object : HealthSource {
                    override suspend fun designCapacity(): DesignCapacity? = error("the platform failed")

                    override suspend fun readings(timeoutMs: Long): HealthReadings = HealthReadings.NONE
                }
            val engine =
                CollectorEngine(db, device, FakeStats(clock), identity, clock, MemoryBlobs(), FakeLabels(mutableMapOf()), health = failing)
            device.set(hours = 9.0, level = 80, chargeMah = 4_000.0, screenOn = true)
            runCatching { engine.handle(SnapshotTrigger.SERVICE_STARTED) }
            assertThat(engine.calibration).isNotNull()
            device.set(hours = 9.5, level = 79, chargeMah = 3_950.0, screenOn = false)
            assertThat(engine.handle(SnapshotTrigger.SCREEN_OFF)).isEqualTo(Action.HEAVY)
            assertThat(engine.calibration).isNotNull()
        }

    /** Audit DATA-018: a label lookup that fails after the commit costs the names only. */
    @Test
    fun aFailedLabelLookupKeepsTheSnapshotAndItsSample() =
        runTest {
            val labels =
                object : AppLabels {
                    override fun label(uid: Int): AppLabel? = error("the package manager failed")
                }
            val engine = CollectorEngine(db, device, FakeStats(clock), identity, clock, MemoryBlobs(), labels)
            device.set(hours = 9.0, level = 80, chargeMah = 4_000.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.SERVICE_STARTED)).isEqualTo(Action.HEAVY)
            val heavy = checkNotNull(db.snapshotDao().latestHeavy())
            assertThat(db.sampleDao().latest()?.elapsedNs).isEqualTo(heavy.elapsedNs)
        }
}

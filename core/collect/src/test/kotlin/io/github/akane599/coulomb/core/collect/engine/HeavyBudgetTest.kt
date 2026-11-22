// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.model.DesignCapacity
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Audit PERF-004: the health and rail reads get what the heavy snapshot's budget left, and wait when it is spent. */
@RunWith(AndroidJUnit4::class)
class HeavyBudgetTest {
    private lateinit var db: CoulombDatabase
    private val clock = FakeClock()
    private val device = FakeDevice(clock)
    private val stats = FakeStats(clock)

    private val health =
        object : HealthSource {
            val timeouts = mutableListOf<Long>()

            override suspend fun designCapacity(): DesignCapacity? = null

            override suspend fun readings(timeoutMs: Long): HealthReadings {
                timeouts += timeoutMs
                return HealthReadings.NONE
            }
        }

    private val rails =
        object : RailSource {
            val timeouts = mutableListOf<Long>()

            override suspend fun read(timeoutMs: Long): List<RailReading> {
                timeouts += timeoutMs
                return emptyList()
            }
        }

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = CoulombDatabase.configured(Room.inMemoryDatabaseBuilder(context, CoulombDatabase::class.java).allowMainThreadQueries())
    }

    @After
    fun close() = db.close()

    private fun engine() =
        CollectorEngine(db, device, stats, FakeIdentity(), clock, MemoryBlobs(), FakeLabels(mutableMapOf()), health = health, rails = rails)
            .also { it.watching(true) }

    @Test
    fun aSlowDumpLeavesTheOptionalReadsOnlyWhatIsLeft() =
        runTest {
            val engine = engine()
            // S5 takes 3.5 s of the 4 s budget.
            stats.statsTakesMs = 3_500
            device.set(hours = 9.0, level = 80, chargeMah = 4_000.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.SERVICE_STARTED)).isEqualTo(Action.HEAVY)
            assertThat(health.timeouts.single()).isAtMost(500L)
            assertThat(rails.timeouts.single()).isAtMost(500L)
        }

    @Test
    fun aSpentBudgetDefersTheHealthReadToTheNextSnapshot() =
        runTest {
            val engine = engine()
            stats.statsTakesMs = 4_500
            device.set(hours = 9.0, level = 80, chargeMah = 4_000.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.SERVICE_STARTED)).isEqualTo(Action.HEAVY)
            assertThat(health.timeouts).isEmpty()
            assertThat(rails.timeouts).isEmpty()

            // The next heavy snapshot has the time, and the daily read was not used up.
            stats.statsTakesMs = 0
            device.set(hours = 9.5, level = 79, chargeMah = 3_950.0, screenOn = false)
            assertThat(engine.handle(SnapshotTrigger.SCREEN_OFF)).isEqualTo(Action.HEAVY)
            assertThat(health.timeouts).hasSize(1)
        }
}

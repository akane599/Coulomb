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
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.stats.UidStats
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Audit BUG-062: an app whose counters disappear inside a long window keeps the use seen before. */
@RunWith(AndroidJUnit4::class)
class VanishedAppTest {
    private lateinit var db: CoulombDatabase
    private val clock = FakeClock()
    private val device = FakeDevice(clock)
    private val stats = FakeStats(clock)
    private val identity = FakeIdentity()

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = CoulombDatabase.configured(Room.inMemoryDatabaseBuilder(context, CoulombDatabase::class.java).allowMainThreadQueries())
    }

    @After
    fun close() = db.close()

    @Test
    fun anUninstalledAppKeepsItsUseInTheTodayWindow() =
        runTest {
            db.deviceDao().upsertProfile(
                DeviceProfileEntity(identity.fingerprint, "test", "test", 37, 3_700_000, null, null, 1, 1, true, 0, 0.95),
            )
            val engine =
                CollectorEngine(db, device, stats, identity, clock, MemoryBlobs(), FakeLabels(mutableMapOf())).also { it.watching(true) }
            stats.otherUids = listOf(UidStats(10_200, packages = listOf("com.example.game"), powerMah = 10.0))
            device.set(hours = 10.0, level = 90, chargeMah = 4_500.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.SERVICE_STARTED)).isEqualTo(Action.HEAVY)
            stats.otherUids = listOf(UidStats(10_200, packages = listOf("com.example.game"), powerMah = 15.0))
            device.set(hours = 11.0, level = 85, chargeMah = 4_250.0, screenOn = false)
            assertThat(engine.handle(SnapshotTrigger.SCREEN_OFF)).isEqualTo(Action.HEAVY)
            // Uninstalled: its counters are gone, in the same statistics epoch.
            stats.otherUids = emptyList()
            device.set(hours = 12.0, level = 80, chargeMah = 4_000.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.SCREEN_ON)).isEqualTo(Action.HEAVY)
            device.set(hours = 12.5, level = 78, chargeMah = 3_900.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.APP_OPENED)).isEqualTo(Action.HEAVY)

            val reference = db.windowDao().ofKind(WindowKind.REFERENCE).single()
            val game =
                db
                    .windowDao()
                    .rows(reference.id)
                    .uids
                    .filter { it.uid == 10_200 }
            assertThat(game.sumOf { it.mAh }).isWithin(1e-6).of(5.0)
        }
}

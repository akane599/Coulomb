// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.snapshot.SourceStatus
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WindowKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What the collector reports for a heavy snapshot that could only be stored light (audit BUG-013). */
@RunWith(AndroidJUnit4::class)
class StoredLightTest {
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

    private suspend fun engine(): CollectorEngine {
        db.deviceDao().upsertProfile(
            DeviceProfileEntity(identity.fingerprint, "test", "test", 37, 3_700_000, null, null, 1, 1, true, 0, 0.95),
        )
        return CollectorEngine(db, device, stats, identity, clock, MemoryBlobs(), FakeLabels(mutableMapOf())).also { it.watching(true) }
    }

    /**
     * A refresh whose counters came back partial is a light row and is reported as one: the screen
     * that asked gets its windows to the latest heavy snapshot (ADR-0032 §3), and what follows a
     * heavy snapshot (the rules, the drift check) does not run.
     */
    @Test
    fun aRefreshWhoseDumpIsPartialIsLightAndEndsTheReferencesAtTheLatestHeavy() =
        runTest {
            val engine = engine()
            device.set(hours = 10.0, level = 90, chargeMah = 4_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            device.set(hours = 11.0, level = 85, chargeMah = 4_250.0, screenOn = true)
            engine.handle(SnapshotTrigger.APP_OPENED)
            device.set(hours = 12.0, level = 80, chargeMah = 4_000.0, screenOn = false)
            assertThat(engine.handle(SnapshotTrigger.SCREEN_OFF)).isEqualTo(Action.HEAVY)
            val newestHeavy = checkNotNull(db.snapshotDao().latestHeavy())

            stats.statuses = mapOf("S5" to SourceStatus.PARTIAL)
            device.set(hours = 13.0, level = 75, chargeMah = 3_750.0, screenOn = true)
            val action = engine.handle(SnapshotTrigger.PULL_TO_REFRESH)

            assertThat(checkNotNull(db.snapshotDao().latest()).kind).isEqualTo(SnapshotKind.LIGHT)
            assertThat(action).isEqualTo(Action.LIGHT)
            val references = db.windowDao().ofKind(WindowKind.REFERENCE)
            assertThat(references).isNotEmpty()
            assertThat(references.map { it.toSnapshotId }.distinct()).containsExactly(newestHeavy.id)
        }

    /** A heavy snapshot that is stored heavy is still reported as one. */
    @Test
    fun aWholeDumpIsHeavy() =
        runTest {
            val engine = engine()
            device.set(hours = 10.0, level = 90, chargeMah = 4_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            device.set(hours = 13.0, level = 75, chargeMah = 3_750.0, screenOn = true)

            assertThat(engine.handle(SnapshotTrigger.PULL_TO_REFRESH)).isEqualTo(Action.HEAVY)
            assertThat(checkNotNull(db.snapshotDao().latest()).kind).isEqualTo(SnapshotKind.HEAVY)
        }
}

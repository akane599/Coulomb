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
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WindowKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The TODAY reference and its window across midnight and a clock set back (audit BUG-006). */
@RunWith(AndroidJUnit4::class)
class TodayReferenceTest {
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

    private suspend fun engine(): CollectorEngine {
        db.deviceDao().upsertProfile(
            DeviceProfileEntity(identity.fingerprint, "test", "test", 37, 3_700_000, null, null, 1, 1, true, 0, 0.95),
        )
        return CollectorEngine(
            db,
            device,
            FakeStats(clock),
            identity,
            clock,
            MemoryBlobs(),
            FakeLabels(mutableMapOf()),
        ).also { it.watching(true) }
    }

    /** Audit BUG-006: a debounced refresh after midnight drops yesterday's TODAY window that ends at the latest snapshot. */
    @Test
    fun afterMidnightARefreshDropsYesterdaysTodayWindow() =
        runTest {
            val engine = engine()
            device.set(hours = 23.0, level = 90, chargeMah = 4_500.0, screenOn = true, plugged = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            device.set(hours = 23.5, level = 90, chargeMah = 4_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.POWER_DISCONNECTED)
            val unplug = checkNotNull(db.snapshotDao().latestHeavy())
            device.set(hours = 23.99, level = 86, chargeMah = 4_300.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.APP_OPENED)).isEqualTo(Action.HEAVY)
            assertThat(db.windowDao().ofKind(WindowKind.REFERENCE)).hasSize(2)

            // 46 s later, past midnight: debounced, and both windows already end at the latest snapshot.
            device.set(hours = 24.0 + 10.0 / 3600, level = 86, chargeMah = 4_298.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.PULL_TO_REFRESH)).isEqualTo(Action.NONE)

            assertThat(db.windowDao().ofKind(WindowKind.REFERENCE).map { it.fromSnapshotId }).containsExactly(unplug.id)
        }

    /** Audit BUG-006: after the clock is set back days, the next heavy snapshot is today's start, with no day window. */
    @Test
    fun aClockSetBackMovesTodayWithoutADayWindow() =
        runTest {
            val engine = engine()
            device.set(hours = 10.0, level = 90, chargeMah = 4_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            clock.wallOffsetMs -= 3 * 86_400_000L
            device.set(hours = 11.0, level = 85, chargeMah = 4_250.0, screenOn = false)
            assertThat(engine.handle(SnapshotTrigger.SCREEN_OFF)).isEqualTo(Action.HEAVY)

            val latest = checkNotNull(db.snapshotDao().latestHeavy())
            assertThat(db.snapshotDao().autoReference(AutoReference.TODAY)?.snapshotId).isEqualTo(latest.id)
            assertThat(db.windowDao().ofKind(WindowKind.DAY)).isEmpty()
        }

    /**
     * Audit BUG-002: the clock runs a few minutes fast over midnight and is then set right. TODAY
     * moves back to the day that just closed, and the midnight that follows must not store that day
     * a second time, as a window of minutes.
     */
    @Test
    fun aClockSetBackAcrossMidnightAndForwardAgainStoresNoSecondDay() =
        runTest {
            val engine = engine()
            device.set(hours = 10.0, level = 90, chargeMah = 4_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            // Four minutes fast: 00:02 on the next day.
            device.set(hours = 24.0 + 2.0 / 60, level = 80, chargeMah = 4_000.0, screenOn = false)
            engine.handle(SnapshotTrigger.SCREEN_OFF)
            val firstDay = db.windowDao().ofKind(WindowKind.DAY).single()
            // Set right: 23:59 on the day before.
            clock.wallOffsetMs -= 4 * 60_000L
            device.set(hours = 24.0 + 3.0 / 60, level = 80, chargeMah = 3_990.0, screenOn = true)
            engine.handle(SnapshotTrigger.SCREEN_ON)
            // 00:01 on the next day again.
            device.set(hours = 24.0 + 5.0 / 60, level = 80, chargeMah = 3_980.0, screenOn = false)
            engine.handle(SnapshotTrigger.SCREEN_OFF)
            val midnight = checkNotNull(db.snapshotDao().latestHeavy())

            assertThat(db.windowDao().ofKind(WindowKind.DAY)).containsExactly(firstDay)
            assertThat(db.snapshotDao().autoReference(AutoReference.TODAY)?.snapshotId).isEqualTo(midnight.id)

            // The day that starts there is stored when it ends.
            device.set(hours = 48.1, level = 60, chargeMah = 3_000.0, screenOn = true)
            engine.handle(SnapshotTrigger.SCREEN_ON)
            val days = db.windowDao().ofKind(WindowKind.DAY)
            assertThat(days).hasSize(2)
            assertThat(days.map { it.fromSnapshotId }).contains(midnight.id)
        }
}

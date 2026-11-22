// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.content.Context
import android.os.BatteryManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.CollectorClock
import io.github.akane599.coulomb.core.collect.snapshot.HistoryRead
import io.github.akane599.coulomb.core.collect.snapshot.LightReading
import io.github.akane599.coulomb.core.collect.snapshot.LightSource
import io.github.akane599.coulomb.core.collect.snapshot.SourceStatus
import io.github.akane599.coulomb.core.collect.snapshot.StatsRead
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.SourceStatusCodes
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.DesignCapacity
import io.github.akane599.coulomb.core.model.DesignCapacitySource
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.dumpsys.OemHealthValue
import io.github.akane599.coulomb.core.model.history.BatteryHistory
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import io.github.akane599.coulomb.core.model.history.HistoryRecord
import io.github.akane599.coulomb.core.model.stats.BatteryStatsDump
import io.github.akane599.coulomb.core.model.stats.DeviceMisc
import io.github.akane599.coulomb.core.model.stats.DeviceStats
import io.github.akane599.coulomb.core.model.stats.PowerSummary
import io.github.akane599.coulomb.core.model.stats.UidStats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Audit BUG-007: what the engine counts as watched follows the service's receivers, not the
 * process, with the same fakes as [CollectorEngineTest].
 */
@RunWith(AndroidJUnit4::class)
class CollectorWatchTest {
    private lateinit var db: CoulombDatabase
    private val clock = FakeClock()
    private val device = FakeDevice(clock)
    private val stats = FakeStats(clock)
    private val identity = FakeIdentity()
    private val labels = FakeLabels(mutableMapOf())

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = CoulombDatabase.configured(Room.inMemoryDatabaseBuilder(context, CoulombDatabase::class.java).allowMainThreadQueries())
    }

    @After
    fun close() = db.close()

    /** An engine whose service is running, as in Full mode: its receivers watch from the start. */
    private fun engine() = CollectorEngine(db, device, stats, identity, clock, MemoryBlobs(), labels).also { it.watching(true) }

    /**
     * Audit BUG-007: Lite stops the service but the process lives on. A charge between two Lite
     * snapshots of the same process ends the discharge session, as it would after a process start.
     */
    @Test
    fun aChargeWhileLiteHadTheServiceStoppedEndsTheDischargeSession() =
        runTest {
            val engine = engine()
            device.set(hours = 1.0, level = 30, chargeMah = 1_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            engine.watching(false)

            device.set(hours = 7.0, level = 80, chargeMah = 4_000.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.LITE_PERIODIC)).isEqualTo(Action.HEAVY)

            val discharges = db.sessionDao().observeRecent(SessionKind.DISCHARGE, 10).first()
            assertThat(discharges.single { it.closeReason != null }.closeReason).isEqualTo(SessionCloseReason.DATA_GAP)
            assertThat(discharges.single { it.closeReason == null }.startLevel).isEqualTo(80)

            // Back in Full, the service watches again from its first snapshot on.
            engine.watching(true)
            device.set(hours = 8.0, level = 79, chargeMah = 3_950.0, screenOn = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            device.set(hours = 9.0, level = 78, chargeMah = 3_900.0, screenOn = false)
            engine.handle(SnapshotTrigger.SCREEN_OFF)
            // The screen stayed on throughout, so its session is one; the screen off was seen.
            val screenOn = db.sessionDao().observeRecent(SessionKind.SCREEN_ON, 10).first()
            assertThat(screenOn.single().closeReason).isEqualTo(SessionCloseReason.STATE_CHANGED)
        }
}

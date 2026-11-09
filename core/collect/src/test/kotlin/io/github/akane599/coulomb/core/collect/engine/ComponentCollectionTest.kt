// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.snapshot.HistoryRead
import io.github.akane599.coulomb.core.collect.snapshot.SourceStatus
import io.github.akane599.coulomb.core.collect.snapshot.sourceStatus
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.entity.PowerColumns
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.WindowComponentEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.model.Breakdown
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.history.BatteryHistory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** S4 through the whole engine (ADR-0035 §2, §3, §5): when it is read, how it is stored, and its windows. */
@RunWith(AndroidJUnit4::class)
class ComponentCollectionTest {
    private lateinit var db: CoulombDatabase
    private val clock = FakeClock()
    private val device = FakeDevice(clock)
    private val stats = FakeStats(clock)
    private var breakdownOn = true

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CoulombDatabase::class.java).allowMainThreadQueries().build()
        // S7 whole, so a snapshot is partial only if S4 made it so.
        val history = BatteryHistory(null, nextCursorMs = 1_000, firstRecordMs = null, recordCount = 0, gapBeforeFirstRecord = false)
        stats.history = { HistoryRead(emptyList(), history, SourceStatus.OK) }
    }

    @After
    fun close() = db.close()

    private fun engine() =
        CollectorEngine(db, device, stats, FakeIdentity(), clock, MemoryBlobs(), FakeLabels(), CollectorSettings { breakdownOn })

    /** Two heavy snapshots half an hour apart on battery; returns the engine for more. */
    private suspend fun halfHourOnBattery(): CollectorEngine {
        val engine = engine()
        device.set(hours = 1.0, level = 90, chargeMah = 4_500.0, screenOn = true)
        assertThat(engine.handle(SnapshotTrigger.SERVICE_STARTED)).isEqualTo(Action.HEAVY)
        device.set(hours = 1.5, level = 88, chargeMah = 4_400.0, screenOn = false)
        assertThat(engine.handle(SnapshotTrigger.SCREEN_OFF)).isEqualTo(Action.HEAVY)
        return engine
    }

    @Test
    fun offByDefaultNothingIsReadAndTheWindowSaysSo() =
        runTest {
            breakdownOn = false
            halfHourOnBattery()

            assertThat(stats.usageReads).isEqualTo(0)
            val latest = latest()
            assertThat(s4(latest)).isEqualTo(SourceStatus.OFF)
            assertThat(latest.partial).isFalse()
            assertThat(db.snapshotDao().rows(latest.id).componentPower).isEmpty()
            val window = consecutive().single()
            assertThat(window.breakdown).isEqualTo(Breakdown.S4_OFF)
            assertThat(components(window)).isEmpty()
        }

    @Test
    fun onBatteryTheConsecutiveWindowIsBrokenDown() =
        runTest {
            halfHourOnBattery()

            assertThat(stats.usageReads).isEqualTo(2)
            val latest = latest()
            assertThat(s4(latest)).isEqualTo(SourceStatus.OK)
            assertThat(
                db
                    .snapshotDao()
                    .rows(latest.id)
                    .componentPower
                    .map { it.uid },
            ).containsExactly(10_100, UidPowerEntity.DEVICE_UID)
            val window = consecutive().single()
            assertThat(window.breakdown).isEqualTo(Breakdown.BROKEN_DOWN)
            // Half an hour at 1 mAh a minute is 30 mAh, 1,080 dC; Coulomb's own UID used none, so has no row.
            assertThat(components(window))
                .containsExactly(
                    WindowComponentEntity(window.id, 10_100, PowerColumns(cpu = 1_080, background = 1_080)),
                    WindowComponentEntity(window.id, UidPowerEntity.DEVICE_UID, PowerColumns(cpu = 1_080)),
                )
        }

    @Test
    fun thePlugInSnapshotReadsS4ButLaterPluggedOnesDoNot() =
        runTest {
            val engine = engine()
            device.set(hours = 10.0, level = 25, chargeMah = 1_250.0, screenOn = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            device.set(hours = 11.0, level = 20, chargeMah = 1_000.0, screenOn = true, plugged = true)
            assertThat(engine.handle(SnapshotTrigger.POWER_CONNECTED)).isEqualTo(Action.HEAVY)
            assertThat(s4(latest())).isEqualTo(SourceStatus.OK)
            // The charger's realtime from here on is not battery time.
            stats.notOnBatteryMs = hoursMs(1.0)
            device.set(hours = 12.0, level = 60, chargeMah = 3_000.0, screenOn = false, plugged = true)
            assertThat(engine.handle(SnapshotTrigger.SCREEN_OFF)).isEqualTo(Action.HEAVY)

            assertThat(stats.usageReads).isEqualTo(2)
            assertThat(s4(latest())).isEqualTo(SourceStatus.SKIPPED)
            assertThat(latest().partial).isFalse()
            val (toPlugIn, onCharger) = consecutive()
            assertThat(toPlugIn.breakdown).isEqualTo(Breakdown.BROKEN_DOWN)
            assertThat(onCharger.breakdown).isEqualTo(Breakdown.END_NOT_OK)
        }

    @Test
    fun timeOffBatteryKeepsTheWindowFromABreakdown() =
        runTest {
            val engine = engine()
            device.set(hours = 1.0, level = 90, chargeMah = 4_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            // A charge Coulomb missed: 2 s of realtime off battery, past the 1 s allowed.
            stats.notOnBatteryMs = 2_000
            device.set(hours = 1.5, level = 88, chargeMah = 4_400.0, screenOn = false)
            engine.handle(SnapshotTrigger.SCREEN_OFF)

            assertThat(consecutive().single().breakdown).isEqualTo(Breakdown.NOT_ON_BATTERY)
        }

    @Test
    fun aSleepDuringS4MarksItSleptAndTheSnapshotStaysWhole() =
        runTest {
            val engine = engine()
            device.set(hours = 1.0, level = 90, chargeMah = 4_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            // Past the 1 s a read may sleep, but inside the 4 s budget, so S7 still runs.
            stats.usageSleepMs = 1_500
            device.set(hours = 1.5, level = 88, chargeMah = 4_400.0, screenOn = false)
            engine.handle(SnapshotTrigger.SCREEN_OFF)

            val latest = latest()
            assertThat(s4(latest)).isEqualTo(SourceStatus.SLEPT)
            assertThat(sourceStatus(latest.sourceStatusJson, "S5")).isEqualTo(SourceStatus.OK)
            assertThat(latest.partial).isFalse()
            assertThat(db.snapshotDao().rows(latest.id).componentPower).isEmpty()
            assertThat(consecutive().single().breakdown).isEqualTo(Breakdown.END_NOT_OK)
        }

    @Test
    fun anS4SessionThatIsNotS5sMakesItPartial() =
        runTest {
            stats.usage = { read -> read.copy(dump = read.dump?.copy(sessionStartMillis = 1)) }
            halfHourOnBattery()

            val latest = latest()
            assertThat(s4(latest)).isEqualTo(SourceStatus.PARTIAL)
            assertThat(latest.partial).isFalse()
            assertThat(db.snapshotDao().rows(latest.id).componentPower).isEmpty()
        }

    @Test
    fun aFailedS4LeavesTheCountersAlone() =
        runTest {
            stats.usage = { read -> read.copy(dump = null, status = SourceStatus.FAILED, raw = null) }
            halfHourOnBattery()

            val latest = latest()
            assertThat(s4(latest)).isEqualTo(SourceStatus.FAILED)
            assertThat(latest.partial).isFalse()
            assertThat(db.snapshotDao().rows(latest.id).uidPower).isNotEmpty()
            assertThat(consecutive().single().breakdown).isEqualTo(Breakdown.END_NOT_OK)
        }

    @Test
    fun aLateS5SkipsS4() =
        runTest {
            stats.statsTakesMs = 1_500
            halfHourOnBattery()

            assertThat(stats.usageReads).isEqualTo(0)
            assertThat(s4(latest())).isEqualTo(SourceStatus.SKIPPED)
        }

    @Test
    fun withoutS5S4IsNotRead() =
        runTest {
            stats.statuses = mapOf("S5" to SourceStatus.PARTIAL)
            halfHourOnBattery()

            assertThat(stats.usageReads).isEqualTo(0)
            assertThat(s4(latest())).isEqualTo(SourceStatus.SKIPPED)
        }

    private suspend fun latest(): SnapshotEntity = checkNotNull(db.snapshotDao().latest())

    private fun s4(snapshot: SnapshotEntity) = sourceStatus(snapshot.sourceStatusJson, "S4")

    private suspend fun consecutive(): List<WindowResultEntity> = db.windowDao().ofKind(WindowKind.CONSECUTIVE).sortedBy { it.toSnapshotId }

    private suspend fun components(window: WindowResultEntity) = db.windowDao().rows(window.id).components
}

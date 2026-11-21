// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WindowKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A system server restart through the whole engine (ADR-0055): a new boot number in the same kernel boot. */
@RunWith(AndroidJUnit4::class)
class SystemRestartTest {
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

    private fun engine() = CollectorEngine(db, device, stats, identity, clock, MemoryBlobs(), FakeLabels(mutableMapOf()))

    @Test
    fun aSystemRestartLeavesNoWindowAcrossIt() =
        runTest {
            val before = engine()
            device.set(hours = 5.0, level = 60, chargeMah = 3_000.0, screenOn = true)
            before.handle(SnapshotTrigger.SERVICE_STARTED)
            device.set(hours = 5.5, level = 59, chargeMah = 2_950.0, screenOn = false)
            assertThat(before.handle(SnapshotTrigger.SCREEN_OFF)).isEqualTo(Action.HEAVY)
            assertThat(db.windowDao().observeRecent(WindowKind.CONSECUTIVE, 10).first()).hasSize(1)

            // The system server restarts: BOOT_COUNT goes up, but elapsed time and the clock run on (ADR-0055).
            identity.bootCount = identity.bootCount + 1
            val after = engine()
            device.set(hours = 5.8, level = 58, chargeMah = 2_900.0, screenOn = false)
            assertThat(after.handle(SnapshotTrigger.BOOT_COMPLETED)).isEqualTo(Action.HEAVY)
            // The counters on the two sides cannot be compared, so the step is in no window.
            assertThat(db.windowDao().observeRecent(WindowKind.CONSECUTIVE, 10).first()).hasSize(1)
            assertThat(db.snapshotDao().autoReference(AutoReference.BEFORE_RESET)).isNull()

            device.set(hours = 6.3, level = 57, chargeMah = 2_850.0, screenOn = true)
            assertThat(after.handle(SnapshotTrigger.SCREEN_ON)).isEqualTo(Action.HEAVY)
            val windows = db.windowDao().observeRecent(WindowKind.CONSECUTIVE, 10).first()
            assertThat(windows).hasSize(2)
            assertThat(windows.maxBy { it.id }.measuredMah).isWithin(1e-6).of(50.0)
        }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.SnapshotOutcome
import io.github.akane599.coulomb.core.collect.actions.ActionCenter
import io.github.akane599.coulomb.core.collect.actions.ActionModule
import io.github.akane599.coulomb.core.collect.actions.FakeLog
import io.github.akane599.coulomb.core.collect.actions.FakePhone
import io.github.akane599.coulomb.core.collect.snapshot.StatsRead
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Audit CONC-010: "Delete all data" against the real collector queue, the real Action Center and
 * the drift observer as the app provides it. The collector is held first and the Action Center's
 * lock taken after; the other order waits for ever when a heavy snapshot is in flight.
 */
@RunWith(AndroidJUnit4::class)
class EraseOrderTest {
    private lateinit var db: CoulombDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = FakeClock()
    private val device = FakeDevice(clock)

    /** A heavy dump that waits at [gate], as a slow worker read does. */
    private class SlowStats(
        private val inner: FakeStats,
    ) : HeavySource by inner {
        val entered = CompletableDeferred<Unit>()
        var gate = CompletableDeferred<Unit>().also { it.complete(Unit) }

        override suspend fun stats(timeoutMs: Long): StatsRead {
            entered.complete(Unit)
            gate.await()
            return inner.stats(timeoutMs)
        }
    }

    private class Rig(
        val center: ActionCenter,
        val actor: CollectorActor,
        val stats: SlowStats,
    )

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = CoulombDatabase.configured(Room.inMemoryDatabaseBuilder(context, CoulombDatabase::class.java).allowMainThreadQueries())
    }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun rig(gated: Boolean): Rig {
        val center = ActionCenter(FakePhone(), FakeLog(), { true }, { 1_000L })
        val stats = SlowStats(FakeStats(clock)).also { if (gated) it.gate = CompletableDeferred() }
        val engine = CollectorEngine(db, device, stats, FakeIdentity(), clock, MemoryBlobs(), FakeLabels(mutableMapOf()))
        return Rig(center, CollectorActor(engine, scope, setOf(ActionModule.drift { center })), stats)
    }

    @Test
    fun holdingTheCollectorBeforeTheLockFinishesDuringAHeavySnapshot() =
        runBlocking {
            val rig = rig(gated = true)
            device.set(hours = 9.0, level = 80, chargeMah = 4_000.0, screenOn = true)
            val done = CompletableDeferred<SnapshotOutcome>()
            rig.actor.post(CollectorEvent.Trigger(SnapshotTrigger.APP_OPENED, done))
            rig.stats.entered.await()
            var erased = false
            val erase =
                scope.launch {
                    rig.actor.hold()
                    rig.center.eraseWhenNoneInForce { erased = true }
                }
            rig.stats.gate.complete(Unit)

            assertThat(withTimeoutOrNull(WAIT_MS) { erase.join() }).isNotNull()
            assertThat(erased).isTrue()
            // The snapshot in flight finished, drift check and all, before the erase ran.
            assertThat(withTimeoutOrNull(WAIT_MS) { done.await() }).isEqualTo(SnapshotOutcome.HEAVY)
        }

    @Test
    fun anEraseAfterTheSnapshotFinishes() =
        runBlocking {
            val rig = rig(gated = false)
            device.set(hours = 9.0, level = 80, chargeMah = 4_000.0, screenOn = true)
            val done = CompletableDeferred<SnapshotOutcome>()
            rig.actor.post(CollectorEvent.Trigger(SnapshotTrigger.APP_OPENED, done))
            assertThat(done.await()).isEqualTo(SnapshotOutcome.HEAVY)
            var erased = false
            val finished =
                withTimeoutOrNull(WAIT_MS) {
                    rig.actor.hold()
                    rig.center.eraseWhenNoneInForce { erased = true }
                }
            assertThat(finished).isEqualTo(0)
            assertThat(erased).isTrue()
        }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}

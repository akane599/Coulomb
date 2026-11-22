// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.SnapshotObserver
import io.github.akane599.coulomb.core.collect.SnapshotOutcome
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SPEC 7.5: what follows a stored snapshot hears of it, in order, and cannot stop collection. */
@RunWith(AndroidJUnit4::class)
class CollectorActorTest {
    private lateinit var db: CoulombDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = FakeClock()
    private val device = FakeDevice(clock)

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

    @Test
    fun observersHearOfEachStoredSnapshotAndAFailingOneStopsNothing() =
        runBlocking {
            val heard = Channel<Boolean>(Channel.UNLIMITED)
            val failing = SnapshotObserver { error("an observer that always fails") }
            val listening = SnapshotObserver { heavy -> heard.send(heavy) }
            val engine = CollectorEngine(db, device, FakeStats(clock), FakeIdentity(), clock, MemoryBlobs(), FakeLabels(mutableMapOf()))
            // A set keeps insertion order here; the failing observer runs first.
            val actor = CollectorActor(engine, scope, linkedSetOf(failing, listening))
            device.set(hours = 9.0, level = 80, chargeMah = 4_000.0, screenOn = true)
            actor.post(CollectorEvent.Trigger(SnapshotTrigger.SERVICE_STARTED))
            assertThat(withTimeout(TIMEOUT_MS) { heard.receive() }).isTrue()
            // A sample stores no snapshot, so nobody hears of it; the next snapshot is heard.
            actor.post(CollectorEvent.Sample(device.read()))
            device.set(hours = 9.5, level = 79, chargeMah = 3_950.0, screenOn = false)
            actor.post(CollectorEvent.Trigger(SnapshotTrigger.SCREEN_OFF))
            assertThat(withTimeout(TIMEOUT_MS) { heard.receive() }).isTrue()
            assertThat(heard.tryReceive().isSuccess).isFalse()
            assertThat(db.snapshotDao().latestHeavy()).isNotNull()
            actor.close()
        }

    /** Audit BUG-001: a trigger's caller learns whether it stored a snapshot, or failed. */
    @Test
    fun aTriggersCallerLearnsWhatItDid() =
        runBlocking {
            val engine = CollectorEngine(db, device, FakeStats(clock), FakeIdentity(), clock, MemoryBlobs(), FakeLabels(mutableMapOf()))
            val actor = CollectorActor(engine, scope)
            device.set(hours = 9.0, level = 80, chargeMah = 4_000.0, screenOn = true)

            suspend fun handled(trigger: SnapshotTrigger): SnapshotOutcome {
                val done = CompletableDeferred<SnapshotOutcome>()
                actor.post(CollectorEvent.Trigger(trigger, done))
                return withTimeout(TIMEOUT_MS) { done.await() }
            }
            assertThat(handled(SnapshotTrigger.SERVICE_STARTED)).isEqualTo(SnapshotOutcome.HEAVY)
            // A refresh within the minute is debounced.
            assertThat(handled(SnapshotTrigger.PULL_TO_REFRESH)).isEqualTo(SnapshotOutcome.NONE)
            // Storage gone: the snapshot fails, and its caller is told.
            db.close()
            device.set(hours = 10.0, level = 78, chargeMah = 3_900.0, screenOn = false)
            assertThat(handled(SnapshotTrigger.SCREEN_OFF)).isEqualTo(SnapshotOutcome.FAILED)
            actor.close()
        }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}

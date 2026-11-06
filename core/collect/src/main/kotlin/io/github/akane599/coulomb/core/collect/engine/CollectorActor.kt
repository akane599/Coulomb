// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.util.Log
import io.github.akane599.coulomb.core.collect.snapshot.LightReading
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** What the collector is asked to do. */
internal sealed interface CollectorEvent {
    data class Trigger(
        val trigger: SnapshotTrigger,
    ) : CollectorEvent

    data class Sample(
        val reading: LightReading,
    ) : CollectorEvent

    /** Dumps may have become possible: take an owed heavy snapshot. */
    data object PrivilegesChanged : CollectorEvent
}

/**
 * SPEC 7.5 step 1: one `Channel`, one consumer. Events run one at a time in arrival order. A
 * failure is logged and the next event runs: one bad dump must not stop collection.
 */
internal class CollectorActor(
    private val engine: CollectorEngine,
    scope: CoroutineScope,
) {
    // Unlimited: triggers are rare and must not be dropped; samples are at most one a second.
    private val events = Channel<CollectorEvent>(Channel.UNLIMITED)

    private val consumer: Job =
        scope.launch {
            for (event in events) {
                try {
                    when (event) {
                        is CollectorEvent.Trigger -> engine.handle(event.trigger)
                        is CollectorEvent.Sample -> engine.sample(event.reading)
                        CollectorEvent.PrivilegesChanged -> engine.privilegesChanged()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // Room, a parser or the platform failed for this event only.
                    Log.w(TAG, "collector event $event failed", e)
                }
            }
        }

    fun post(event: CollectorEvent) {
        events.trySend(event)
    }

    fun close() {
        events.close()
        consumer.cancel()
    }

    private companion object {
        const val TAG = "CoulombCollector"
    }
}

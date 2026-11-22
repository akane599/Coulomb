// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.util.Log
import io.github.akane599.coulomb.core.collect.SnapshotObserver
import io.github.akane599.coulomb.core.collect.SnapshotOutcome
import io.github.akane599.coulomb.core.collect.snapshot.LightReading
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** What the collector is asked to do. */
internal sealed interface CollectorEvent {
    /** [done] completes with what the trigger did once it has been handled. */
    data class Trigger(
        val trigger: SnapshotTrigger,
        val done: CompletableDeferred<SnapshotOutcome>? = null,
    ) : CollectorEvent

    data class Sample(
        val reading: LightReading,
    ) : CollectorEvent

    /** Dumps may have become possible: take an owed heavy snapshot. */
    data object PrivilegesChanged : CollectorEvent

    /** The service attached or detached its receivers (audit BUG-007). */
    data class Watching(
        val on: Boolean,
    ) : CollectorEvent
}

/**
 * SPEC 7.5 step 1: one `Channel`, one consumer. Events run one at a time in arrival order. A
 * failure is logged and the next event runs: one bad dump must not stop collection.
 */
internal class CollectorActor(
    private val engine: CollectorEngine,
    scope: CoroutineScope,
    private val observers: Set<SnapshotObserver> = emptySet(),
) {
    // Unlimited: triggers are rare and must not be dropped; samples are at most one a second.
    private val events = Channel<CollectorEvent>(Channel.UNLIMITED)

    private val consumer: Job =
        scope.launch {
            for (event in events) {
                var outcome = SnapshotOutcome.FAILED
                try {
                    val action =
                        when (event) {
                            is CollectorEvent.Trigger -> engine.handle(event.trigger)
                            is CollectorEvent.Sample -> engine.sample(event.reading).let { null }
                            CollectorEvent.PrivilegesChanged -> engine.privilegesChanged()
                            is CollectorEvent.Watching -> engine.watching(event.on).let { null }
                        }
                    outcome =
                        when (action) {
                            Action.HEAVY -> SnapshotOutcome.HEAVY
                            Action.LIGHT -> SnapshotOutcome.LIGHT
                            else -> SnapshotOutcome.NONE
                        }
                    if (action == Action.LIGHT || action == Action.HEAVY) notify(heavy = action == Action.HEAVY)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // Room, a parser or the platform failed for this event only.
                    Log.w(TAG, "collector event $event failed", e)
                } finally {
                    (event as? CollectorEvent.Trigger)?.done?.complete(outcome)
                }
            }
        }

    /** SPEC 7.5: what follows a stored snapshot. One observer's failure is not the others' or the collector's. */
    private suspend fun notify(heavy: Boolean) {
        for (observer in observers) {
            try {
                observer.onSnapshot(heavy)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "snapshot observer failed", e)
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

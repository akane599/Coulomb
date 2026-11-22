// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.WorkerFailure
import io.github.akane599.coulomb.core.model.WorkerStatus
import io.github.akane599.coulomb.core.privileged.WorkerOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkerConnectorTest {
    private val pipes = FakePipes()
    private val worker = FakeWorker(pipes)
    private val api = FakeShizukuApi(worker)

    private fun TestScope.connector() =
        WorkerConnector(api, backgroundScope, StandardTestDispatcher(testScheduler), FakeWorker.VERSION, {
            currentTime
        })

    @Test
    fun bindsOnDemandAndChecksTheVersion() =
        runTest {
            val c = connector()
            assertThat(c.call { it.version }).isEqualTo(WorkerCall.Ok(FakeWorker.VERSION, WorkerOrigin(2000, 0)))
            assertThat(c.state.value.status).isEqualTo(WorkerStatus.BOUND)
            assertThat(c.state.value.workerUid).isEqualTo(2000)
            assertThat(api.binds).isEqualTo(1)
            // A second call reuses the connection.
            c.call { it.uid }
            assertThat(api.binds).isEqualTo(1)
        }

    @Test
    fun noConnectionWithinFiveSecondsMarksTheWorkerUnavailable() =
        runTest {
            api.connects = false
            val c = connector()
            val call = async { c.call { it.version } }
            advanceTimeBy(WorkerConnector.BIND_TIMEOUT_MS - 1)
            runCurrent()
            assertThat(call.isCompleted).isFalse()
            advanceTimeBy(2)
            runCurrent()
            assertThat(call.await()).isEqualTo(WorkerCall.Unavailable(WorkerFailure.BIND_TIMEOUT))
            assertThat(c.state.value.status).isEqualTo(WorkerStatus.UNAVAILABLE)
            assertThat(api.unbinds).containsExactly(true)
            // Unavailable for the session: no further bind attempts.
            assertThat(c.call { it.version }).isEqualTo(WorkerCall.Unavailable(WorkerFailure.BIND_TIMEOUT))
            assertThat(api.binds).isEqualTo(1)
        }

    /** Audit CONC-012: a caller cancelled during a cold bind leaves no worker behind. */
    @Test
    fun aBindWhoseCallerLeavesIsUnboundAndALateWorkerStopped() =
        runTest {
            api.defersConnect = true
            val c = connector()
            val call = async { c.call { it.version } }
            runCurrent()
            assertThat(api.binds).isEqualTo(1)
            call.cancel()
            runCurrent()
            assertThat(api.unbinds).containsExactly(true)
            // The manager connects the worker anyway; no one holds it, so it is stopped.
            api.connectLate()
            runCurrent()
            assertThat(worker.destroyed).isEqualTo(1)
            assertThat(c.state.value.status).isNotEqualTo(WorkerStatus.BOUND)
        }

    /**
     * Audit CONC-002: Shizuku hands a worker to every bind made on the tag, the given-up ones too.
     * The worker of the bind that is under way is not theirs to stop.
     */
    @Test
    fun aGivenUpBindDoesNotStopTheNextWorker() =
        runTest {
            api.fanOut = true
            api.connects = false
            val c =
                WorkerConnector(
                    api,
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    FakeWorker.VERSION,
                    { currentTime },
                    bindKnownGood = { true },
                )
            val first = async { c.call { it.version } }
            advanceTimeBy(WorkerConnector.BIND_TIMEOUT_MS + 1)
            runCurrent()
            assertThat(first.await()).isEqualTo(WorkerCall.Unavailable(WorkerFailure.BIND_TIMEOUT))
            advanceTimeBy(WorkerConnector.RETRY_BACKOFF_MS[0] + 1)

            api.connects = true
            val second = c.call { it.version }
            runCurrent()

            assertThat(second).isEqualTo(WorkerCall.Ok(FakeWorker.VERSION, WorkerOrigin(2000, 0)))
            assertThat(worker.destroyed).isEqualTo(0)
            assertThat(c.state.value.status).isEqualTo(WorkerStatus.BOUND)
        }

    @Test
    fun aCancelledColdBindDoesNotStopTheNextWorker() =
        runTest {
            api.fanOut = true
            api.defersConnect = true
            val c = connector()
            val first = async { c.call { it.version } }
            runCurrent()
            first.cancel()
            runCurrent()

            val second = async { c.call { it.version } }
            runCurrent()
            assertThat(api.binds).isEqualTo(2)
            api.connectLate()
            runCurrent()

            assertThat(second.await()).isEqualTo(WorkerCall.Ok(FakeWorker.VERSION, WorkerOrigin(2000, 0)))
            assertThat(worker.destroyed).isEqualTo(0)
        }

    /** CONC-012 still holds under the fan-out: with no bind under way and none bound, a late worker is no one's. */
    @Test
    fun aLateWorkerOfAGivenUpBindIsStoppedWhenNoOneBinds() =
        runTest {
            api.fanOut = true
            api.defersConnect = true
            val c = connector()
            val call = async { c.call { it.version } }
            runCurrent()
            call.cancel()
            runCurrent()

            api.connectLate()
            runCurrent()

            assertThat(worker.destroyed).isEqualTo(1)
            assertThat(c.state.value.status).isNotEqualTo(WorkerStatus.BOUND)
        }

    /**
     * Audit PERF-004: a caller with a deadline stops waiting for a slow bind, which is not counted
     * as a timeout; it goes on, the next call finds the worker bound, and the idle unbind comes.
     */
    @Test
    fun aCallerWithADeadlineLeavesASlowBindRunning() =
        runTest {
            api.defersConnect = true
            val c = connector()
            val call = async { c.call(waitMs = 1_000) { it.version } }
            advanceTimeBy(1_001)
            runCurrent()
            assertThat(call.await()).isInstanceOf(WorkerCall.Unavailable::class.java)
            assertThat(api.unbinds).isEmpty()
            assertThat(c.state.value.failure).isNull()

            advanceTimeBy(1_000)
            api.connectLate()
            runCurrent()
            assertThat(c.state.value.status).isEqualTo(WorkerStatus.BOUND)
            assertThat(c.call(waitMs = 1_000) { it.version }).isEqualTo(WorkerCall.Ok(FakeWorker.VERSION, WorkerOrigin(2000, 0)))
            assertThat(api.binds).isEqualTo(1)
            advanceTimeBy(WorkerConnector.IDLE_UNBIND_MS + 1)
            runCurrent()
            assertThat(api.unbinds).containsExactly(true)
        }

    /** Audit CONC-010: the idle unbind reaches Shizuku before the next bind, so it cannot stop the new worker. */
    @Test
    fun theNextBindWaitsForTheIdleUnbind() =
        runTest {
            val c = connector()
            c.call { it.version }
            advanceTimeBy(WorkerConnector.IDLE_UNBIND_MS + 1)
            runCurrent()
            c.call { it.version }
            assertThat(api.calls).containsExactly("bind", "remove", "bind").inOrder()
        }

    @Test
    fun simulatedHangNeverReachesShizukuAndTimesOut() =
        runTest {
            val c = connector()
            c.simulateBindHang = true
            assertThat(c.call { it.version }).isEqualTo(WorkerCall.Unavailable(WorkerFailure.BIND_TIMEOUT))
            assertThat(api.binds).isEqualTo(0)
            assertThat(currentTime).isEqualTo(WorkerConnector.BIND_TIMEOUT_MS)
        }

    @Test
    fun versionMismatchRebindsOnceWithRemoveThenGivesUp() =
        runTest {
            worker.reportedVersion = FakeWorker.VERSION + 1
            val c = connector()
            assertThat(c.call { it.version }).isEqualTo(WorkerCall.Unavailable(WorkerFailure.VERSION_MISMATCH))
            assertThat(api.binds).isEqualTo(WorkerConnector.HANDSHAKE_ATTEMPTS)
            assertThat(api.unbinds).containsExactly(true, true)
        }

    @Test
    fun unbindsWithRemoveSixtySecondsAfterTheLastCall() =
        runTest {
            val c = connector()
            c.call { it.version }
            advanceTimeBy(WorkerConnector.IDLE_UNBIND_MS - 1)
            runCurrent()
            assertThat(api.unbinds).isEmpty()
            advanceTimeBy(2)
            runCurrent()
            assertThat(api.unbinds).containsExactly(true)
            assertThat(c.state.value.status).isEqualTo(WorkerStatus.IDLE)
        }

    @Test
    fun threeDeathsInASessionMarkTheWorkerDead() =
        runTest {
            val c = connector()
            repeat(WorkerConnector.MAX_DEATHS - 1) {
                c.call { it.version }
                api.lastBinder!!.die()
                assertThat(c.state.value.status).isEqualTo(WorkerStatus.IDLE)
            }
            c.call { it.version }
            api.lastBinder!!.die()
            assertThat(c.state.value.status).isEqualTo(WorkerStatus.UNAVAILABLE)
            assertThat(c.state.value.failure).isEqualTo(WorkerFailure.WORKER_DIED)
        }

    @Test
    fun aWorkerThatOutlivesItsManagerIsStoppedWhenTheCallEnds() =
        runTest {
            val c = connector()
            val gate = CompletableDeferred<Unit>()
            val call = async { c.call { gate.await() } }
            runCurrent()
            c.onManagerDead()
            runCurrent()
            assertThat(worker.destroyed).isEqualTo(0)
            gate.complete(Unit)
            call.await()
            runCurrent()
            assertThat(worker.destroyed).isEqualTo(1)
        }

    @Test
    fun aNewBinderClearsAnEarlierFailure() =
        runTest {
            api.connects = false
            val c = connector()
            c.call { it.version }
            api.connects = true
            c.reset()
            assertThat(c.call { it.version }).isEqualTo(WorkerCall.Ok(FakeWorker.VERSION, WorkerOrigin(2000, 0)))
        }
}

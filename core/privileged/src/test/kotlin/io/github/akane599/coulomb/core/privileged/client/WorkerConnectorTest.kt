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

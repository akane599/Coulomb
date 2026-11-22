// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import android.os.DeadObjectException
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.privileged.ICoulombPrivileged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real threads: the idle unbind runs on another thread than the call that takes the worker, and
 * no suspension point lies between them for a test clock to hold (audit CONC-003).
 */
class WorkerConnectorRaceTest {
    /** A worker whose calls fail once Shizuku has removed it. */
    private class MortalWorker(
        private val real: FakeWorker,
    ) : ICoulombPrivileged by real {
        @Volatile var removed = false

        override fun getVersion(): Long = if (removed) throw DeadObjectException() else real.version
    }

    /** One worker per tag, as with Shizuku: a bind gets the live one or starts one, and an unbind with remove ends it. */
    private class TagApi(
        private val pipes: FakePipes,
    ) : ShizukuApi by FakeShizukuApi(null) {
        private var live: MortalWorker? = null
        val removes = AtomicInteger()

        @Synchronized
        override fun bindWorker(callback: WorkerCallback) {
            val worker = live ?: MortalWorker(FakeWorker(pipes)).also { live = it }
            callback.onConnected(FakeWorkerBinder(worker))
        }

        @Synchronized
        override fun unbindWorker(
            callback: WorkerCallback,
            remove: Boolean,
        ) {
            if (!remove) return
            removes.incrementAndGet()
            live?.removed = true
            live = null
        }
    }

    @Test
    fun theIdleUnbindNeverRemovesAWorkerACallHolds() =
        runBlocking {
            val api = TagApi(FakePipes())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // Idle at once: every call's end sends an unbind after it, while the other thread's call begins.
            val connector =
                WorkerConnector(api, scope, Dispatchers.IO, FakeWorker.VERSION, { System.nanoTime() / NS_PER_MS }, idleUnbindMs = 0)

            val results =
                List(THREADS) { async(Dispatchers.Default) { List(CALLS) { connector.call { it.version } } } }.awaitAll().flatten()
            scope.cancel()

            assertThat(results.filterNot { it is WorkerCall.Ok }.map { it.javaClass.simpleName }).isEmpty()
            // The unbinds did run, so the calls above did meet them.
            assertThat(api.removes.get()).isGreaterThan(0)
        }

    private companion object {
        const val THREADS = 2
        const val CALLS = 2_000
        const val NS_PER_MS = 1_000_000
    }
}

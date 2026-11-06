// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import android.os.DeadObjectException
import android.os.RemoteException
import io.github.akane599.coulomb.core.model.WorkerFailure
import io.github.akane599.coulomb.core.model.WorkerStatus
import io.github.akane599.coulomb.core.privileged.ICoulombPrivileged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class WorkerConnectionState(
    val status: WorkerStatus = WorkerStatus.IDLE,
    val failure: WorkerFailure? = null,
    /** Wall time from bindUserService to a verified connection, for the capability report (SPEC 4.3). */
    val lastBindMs: Long? = null,
    val workerUid: Int? = null,
    val workerSeLinuxContext: String? = null,
)

internal sealed interface WorkerCall<out T> {
    data class Ok<T>(
        val value: T,
    ) : WorkerCall<T>

    /** The worker cannot be used this session (or the manager is not ready). */
    data class Unavailable(
        val failure: WorkerFailure?,
    ) : WorkerCall<Nothing>

    /** The worker died during the call. */
    data class Died(
        val error: Throwable,
    ) : WorkerCall<Nothing>

    /** The worker refused or failed the call (SecurityException, IllegalArgumentException, ...). */
    data class Failed(
        val error: Throwable,
    ) : WorkerCall<Nothing>
}

/**
 * Owns the UserService connection (SPEC 4.3): binds on demand with a 5 s timeout, checks the
 * version handshake, keeps the worker while calls are in flight and unbinds with `remove = true`
 * 60 s after the last one, so no shell process lingers.
 */
internal class WorkerConnector(
    private val api: ShizukuApi,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val expectedVersion: Long,
    private val clock: () -> Long,
    private val bindTimeoutMs: Long = BIND_TIMEOUT_MS,
    private val idleUnbindMs: Long = IDLE_UNBIND_MS,
) {
    private val _state = MutableStateFlow(WorkerConnectionState())
    val state: StateFlow<WorkerConnectionState> = _state.asStateFlow()

    private class Bound(
        val binder: WorkerBinder,
        val callback: WorkerCallback,
    )

    private val bindMutex = Mutex()
    private val lock = Any()
    private var bound: Bound? = null
    private var inFlight = 0
    private var idleJob: Job? = null
    private var deaths = 0

    /** A worker that outlived its manager while a call was running; stopped when the call ends. */
    private var orphan: Bound? = null

    /** Debug drill (protocol F): binds never reach Shizuku, so the real 5 s timeout path runs. */
    @Volatile var simulateBindHang: Boolean = false

    /**
     * Runs [block] against the worker, binding it first if needed. Worker-side failures cross binder
     * as RuntimeException subclasses (Parcel.readException: SecurityException,
     * IllegalArgumentException, IllegalStateException, ...); every one becomes a result.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> call(block: suspend (ICoulombPrivileged) -> T): WorkerCall<T> {
        val worker = acquire() ?: return WorkerCall.Unavailable(_state.value.failure)
        begin()
        return try {
            WorkerCall.Ok(withContext(io) { block(worker) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeadObjectException) {
            WorkerCall.Died(e)
        } catch (e: RemoteException) {
            WorkerCall.Died(e)
        } catch (e: RuntimeException) {
            WorkerCall.Failed(e)
        } finally {
            end()
        }
    }

    /** A new manager binder arrived: earlier failures may not apply to it (SPEC 4.5 auto-resume). */
    fun reset() {
        synchronized(lock) { deaths = 0 }
        _state.update { if (it.status == WorkerStatus.UNAVAILABLE) WorkerConnectionState() else it.copy(failure = null) }
    }

    fun markUnavailable(failure: WorkerFailure) {
        release(remove = true)
        _state.update { it.copy(status = WorkerStatus.UNAVAILABLE, failure = failure) }
    }

    /**
     * The manager's binder died. A non-daemon worker normally goes with it; if it is still alive,
     * Shizuku can no longer remove it, so Coulomb stops it directly once no call is using it.
     */
    fun onManagerDead() {
        val stopNow =
            synchronized(lock) {
                idleJob?.cancel()
                idleJob = null
                val b = bound
                bound = null
                b?.binder?.unlinkToDeath()
                if (b != null && inFlight > 0) {
                    orphan = b
                    null
                } else {
                    b
                }
            }
        stopNow?.let(::destroyDirectly)
        _state.update { if (it.status == WorkerStatus.UNAVAILABLE) it else it.copy(status = WorkerStatus.IDLE) }
    }

    private fun destroyDirectly(b: Bound) {
        // destroy() exits the worker process, so the call itself usually fails with DeadObjectException.
        scope.launch(io) { runCatching { b.binder.worker.destroy() } }
    }

    /** Unbinds now if nothing is running. */
    fun release(remove: Boolean) {
        val b =
            synchronized(lock) {
                if (inFlight > 0) return
                idleJob?.cancel()
                idleJob = null
                bound.also { bound = null }
            } ?: return
        b.binder.unlinkToDeath()
        scope.launch(io) { api.unbindWorker(b.callback, remove) }
        _state.update { if (it.status == WorkerStatus.UNAVAILABLE) it else it.copy(status = WorkerStatus.IDLE) }
    }

    private suspend fun acquire(): ICoulombPrivileged? =
        bindMutex.withLock {
            synchronized(lock) { bound }?.let { return it.binder.worker }
            if (_state.value.status == WorkerStatus.UNAVAILABLE) return null
            repeat(HANDSHAKE_ATTEMPTS) { attempt ->
                val started = clock()
                val connected =
                    bindOnce() ?: run {
                        markUnavailable(WorkerFailure.BIND_TIMEOUT)
                        return null
                    }
                val worker = connected.binder.worker
                val handshake = withContext(io) { runCatching { Triple(worker.version, worker.uid, worker.seLinuxContext) } }.getOrNull()
                if (handshake != null && handshake.first == expectedVersion) {
                    attach(connected)
                    _state.update {
                        it.copy(
                            status = WorkerStatus.BOUND,
                            failure = null,
                            lastBindMs = clock() - started,
                            workerUid = handshake.second,
                            workerSeLinuxContext = handshake.third,
                        )
                    }
                    return connected.binder.worker
                }
                // SPEC 4.3: version mismatch -> unbind with remove = true, rebind.
                withContext(io) { api.unbindWorker(connected.callback, remove = true) }
                if (attempt == HANDSHAKE_ATTEMPTS - 1) {
                    _state.update { it.copy(status = WorkerStatus.UNAVAILABLE, failure = WorkerFailure.VERSION_MISMATCH) }
                }
            }
            null
        }

    private suspend fun bindOnce(): Bound? {
        _state.update { it.copy(status = WorkerStatus.BINDING) }
        val connected = CompletableDeferred<WorkerBinder>()
        val callback =
            object : WorkerCallback {
                override fun onConnected(binder: WorkerBinder) {
                    if (!connected.complete(binder)) return
                }

                override fun onDisconnected() {
                    if (!connected.isCompleted) return
                    onWorkerGone(this)
                }
            }
        // bindUserService itself may block on Android 17 with official Shizuku; never on our caller.
        if (!simulateBindHang) scope.launch(io) { runCatching { api.bindWorker(callback) } }
        val binder = withTimeoutOrNull(bindTimeoutMs) { connected.await() }
        if (binder == null) {
            connected.cancel()
            scope.launch(io) { api.unbindWorker(callback, remove = true) }
            return null
        }
        return Bound(binder, callback)
    }

    private fun attach(b: Bound) {
        synchronized(lock) { bound = b }
        if (!b.binder.linkToDeath { onWorkerGone(b.callback) }) onWorkerGone(b.callback)
    }

    private fun onWorkerGone(callback: WorkerCallback) {
        val failed =
            synchronized(lock) {
                val current = bound
                if (current == null || current.callback !== callback) return
                current.binder.unlinkToDeath()
                bound = null
                idleJob?.cancel()
                idleJob = null
                deaths += 1
                deaths >= MAX_DEATHS
            }
        _state.update {
            if (failed) {
                it.copy(status = WorkerStatus.UNAVAILABLE, failure = WorkerFailure.WORKER_DIED)
            } else {
                it.copy(status = WorkerStatus.IDLE)
            }
        }
    }

    private fun begin() =
        synchronized(lock) {
            inFlight += 1
            idleJob?.cancel()
            idleJob = null
        }

    private fun end() {
        val stop =
            synchronized(lock) {
                inFlight -= 1
                if (inFlight > 0) return
                if (bound != null) {
                    idleJob =
                        scope.launch {
                            delay(idleUnbindMs)
                            release(remove = true)
                        }
                }
                orphan.also { orphan = null }
            }
        stop?.let(::destroyDirectly)
    }

    internal companion object {
        const val BIND_TIMEOUT_MS = 5_000L
        const val IDLE_UNBIND_MS = 60_000L
        const val HANDSHAKE_ATTEMPTS = 2
        const val MAX_DEATHS = 3
    }
}

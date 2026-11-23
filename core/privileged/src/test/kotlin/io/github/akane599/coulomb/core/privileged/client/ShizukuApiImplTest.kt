// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import android.content.ContextWrapper
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.privileged.ICoulombPrivileged
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.shizuku.server.IShizukuService
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.FileDescriptor
import java.lang.reflect.Proxy

/**
 * [WorkerConnector] over [ShizukuApiImpl] and the real Shizuku client classes, with a stand-in for
 * the server (audit CONC-002).
 *
 * The client keeps every `ServiceConnection` bound on a tag in one set and hands a connecting
 * worker to all of them; `unbindUserService(remove = true)` takes none out. The reflection below
 * (`Shizuku.service`, `ShizukuServiceConnections.CACHE`, `ShizukuServiceConnection.connections`
 * and its `lambda$connected$0`) pins client 13.1.5. If it fails after an upgrade, read the new
 * client's `ShizukuServiceConnection` again before changing this test: the connector's guard
 * rests on what it does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShizukuApiImplTest {
    private val pipes = FakePipes()

    /** The server's methods, in the order the client called them. */
    private val log = mutableListOf<String>()
    private val serviceField = Class.forName("rikka.shizuku.Shizuku").getDeclaredField("service").apply { isAccessible = true }

    @Before
    fun installTheServer() {
        val service =
            Proxy.newProxyInstance(IShizukuService::class.java.classLoader, arrayOf(IShizukuService::class.java)) { _, method, _ ->
                log += method.name
                when (method.returnType) {
                    Int::class.javaPrimitiveType -> 0
                    Boolean::class.javaPrimitiveType -> false
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        serviceField.set(null, service)
        cache().clear()
    }

    @After
    fun removeTheServer() {
        serviceField.set(null, null)
        cache().clear()
    }

    private fun cache(): MutableMap<*, *> =
        Class
            .forName("rikka.shizuku.ShizukuServiceConnections")
            .getDeclaredField("CACHE")
            .apply { isAccessible = true }
            .get(null) as MutableMap<*, *>

    /** What the client's main-thread runnable does when the server reports the tag's worker connected. */
    private fun serverConnects(binder: IBinder) {
        val tag = checkNotNull(cache()[TAG])
        tag.javaClass
            .getDeclaredMethod("lambda\$connected\$0", IBinder::class.java)
            .apply { isAccessible = true }
            .invoke(tag, binder)
    }

    private fun membersOnTag(): Int {
        val tag = cache()[TAG] ?: return 0
        return (
            tag.javaClass
                .getDeclaredField("connections")
                .apply { isAccessible = true }
                .get(tag) as Set<*>
        ).size
    }

    private class WorkerIBinder(
        private val worker: ICoulombPrivileged,
    ) : IBinder {
        override fun getInterfaceDescriptor(): String = ICoulombPrivileged.DESCRIPTOR

        override fun pingBinder(): Boolean = true

        override fun isBinderAlive(): Boolean = true

        override fun queryLocalInterface(descriptor: String): IInterface = worker

        override fun dump(
            fd: FileDescriptor,
            args: Array<out String>?,
        ) = Unit

        override fun dumpAsync(
            fd: FileDescriptor,
            args: Array<out String>?,
        ) = Unit

        override fun transact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean = false

        override fun linkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ) = Unit

        override fun unlinkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ): Boolean = true
    }

    private fun api() =
        ShizukuApiImpl(
            object : ContextWrapper(null) {
                override fun getPackageName(): String = "test.pkg"
            },
            workerVersion = 1,
            workerDebuggable = false,
        )

    private fun TestScope.connector(api: ShizukuApi) =
        WorkerConnector(
            api,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            FakeWorker.VERSION,
            { currentTime },
            bindKnownGood = { true },
        )

    @Test
    fun aTimedOutBindDoesNotStopTheNextWorker() =
        runTest {
            val c = connector(api())
            val first = async { c.call { it.version } }
            runCurrent()
            advanceTimeBy(WorkerConnector.BIND_TIMEOUT_MS + 1)
            runCurrent()
            assertThat(first.await()).isInstanceOf(WorkerCall.Unavailable::class.java)
            assertThat(log).containsAtLeast("addUserService", "removeUserService").inOrder()
            // The client still holds the given-up bind: this is what the connector must live with.
            assertThat(membersOnTag()).isEqualTo(1)
            advanceTimeBy(WorkerConnector.RETRY_BACKOFF_MS[0] + 1)

            val worker = FakeWorker(pipes)
            val second = async { c.call { it.version } }
            runCurrent()
            assertThat(membersOnTag()).isEqualTo(2)
            serverConnects(WorkerIBinder(worker))
            runCurrent()

            assertThat(second.await()).isInstanceOf(WorkerCall.Ok::class.java)
            assertThat(worker.destroyed).isEqualTo(0)
        }

    @Test
    fun aCancelledColdBindDoesNotStopTheNextWorker() =
        runTest {
            val c = connector(api())
            val first = async { c.call { it.version } }
            runCurrent()
            first.cancel()
            runCurrent()

            val worker = FakeWorker(pipes)
            val second = async { c.call { it.version } }
            runCurrent()
            serverConnects(WorkerIBinder(worker))
            runCurrent()

            assertThat(second.await()).isInstanceOf(WorkerCall.Ok::class.java)
            assertThat(worker.destroyed).isEqualTo(0)
        }

    /** A caller cancelled right after the bind was launched: the unbind runs first, and the bind is not sent after it. */
    @Test
    fun anUnbindBeforeItsBindKeepsTheBindFromShizuku() {
        val api = api()
        val callback =
            object : WorkerCallback {
                override fun onConnected(binder: WorkerBinder) = Unit

                override fun onDisconnected() = Unit
            }

        api.unbindWorker(callback, remove = true)
        api.bindWorker(callback)

        assertThat(log).doesNotContain("addUserService")
        assertThat(membersOnTag()).isEqualTo(0)
        // Only that one bind is held back: the same callback binds again afterwards.
        api.bindWorker(callback)
        assertThat(log).contains("addUserService")
    }

    private companion object {
        const val TAG = "coulomb-priv"
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import io.github.akane599.coulomb.core.privileged.ICoulombPrivileged
import io.github.akane599.coulomb.core.privileged.worker.CoulombPrivilegedService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import rikka.sui.Sui

/**
 * Shizuku API 13.1.5 (`dev.rikka.shizuku:api`, `:provider`). Signatures were checked in the
 * published artifacts (javap): Shizuku.{pingBinder, getVersion, getUid,
 * getSELinuxContext, checkSelfPermission, shouldShowRequestPermissionRationale, requestPermission,
 * addBinderReceivedListenerSticky, addBinderDeadListener, addRequestPermissionResultListener,
 * bindUserService, unbindUserService(args, conn, remove)}, Shizuku.UserServiceArgs
 * {daemon, processNameSuffix, version, debuggable, tag}, Sui.isSui(), and
 * ShizukuProvider.PERMISSION = "moe.shizuku.manager.permission.API_V23". ShizukuProvider.onCreate()
 * calls Sui.init(packageName) unless disabled, so Sui is initialised before Application.onCreate
 * (SPEC 4.2). `getServerPatchVersion` is `@RestrictTo(LIBRARY_GROUP_PREFIX)` and is not used.
 */
internal class ShizukuApiImpl(
    private val context: Context,
    private val workerVersion: Int,
    private val workerDebuggable: Boolean,
) : ShizukuApi {
    private val args: Shizuku.UserServiceArgs by lazy {
        Shizuku
            .UserServiceArgs(ComponentName(context.packageName, CoulombPrivilegedService::class.java.name))
            .daemon(false)
            .processNameSuffix("priv")
            .version(workerVersion)
            .debuggable(workerDebuggable)
            .tag("coulomb-priv")
    }

    override fun isSui(): Boolean = Sui.isSui()

    override fun pingBinder(): Boolean = Shizuku.pingBinder()

    override fun serverDetails(): ServerDetails? =
        runCatching {
            ServerDetails(Shizuku.getVersion(), Shizuku.getUid(), Shizuku.getSELinuxContext())
        }.getOrNull()

    override fun managerPackage(): ManagerPackage? =
        runCatching {
            val pm = context.packageManager
            val owner = pm.getPermissionInfo(ShizukuProvider.PERMISSION, 0).packageName ?: return null
            ManagerPackage(owner, runCatching { pm.getPackageInfo(owner, 0).versionName }.getOrNull())
        }.getOrElse { e -> if (e is PackageManager.NameNotFoundException) null else null }

    override fun checkSelfPermission(): Boolean =
        runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    override fun permissionDeniedPermanently(): Boolean = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)

    override fun requestPermission(requestCode: Int) = Shizuku.requestPermission(requestCode)

    private var registered: Listeners? = null

    override fun setListener(listener: ShizukuEvents?) {
        registered?.let {
            Shizuku.removeBinderReceivedListener(it.received)
            Shizuku.removeBinderDeadListener(it.dead)
            Shizuku.removeRequestPermissionResultListener(it.result)
        }
        registered =
            listener?.let { l ->
                Listeners(
                    received = Shizuku.OnBinderReceivedListener { l.onBinderReceived() },
                    dead = Shizuku.OnBinderDeadListener { l.onBinderDead() },
                    result =
                        Shizuku.OnRequestPermissionResultListener { code, result ->
                            l.onPermissionResult(code, result == PackageManager.PERMISSION_GRANTED)
                        },
                ).also {
                    Shizuku.addBinderReceivedListenerSticky(it.received)
                    Shizuku.addBinderDeadListener(it.dead)
                    Shizuku.addRequestPermissionResultListener(it.result)
                }
            }
    }

    private class Listeners(
        val received: Shizuku.OnBinderReceivedListener,
        val dead: Shizuku.OnBinderDeadListener,
        val result: Shizuku.OnRequestPermissionResultListener,
    )

    private val connections = HashMap<WorkerCallback, ServiceConnection>()

    override fun bindWorker(callback: WorkerCallback) {
        val connection =
            connections.getOrPut(callback) {
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName?,
                        service: IBinder?,
                    ) {
                        if (service == null || !service.pingBinder()) return callback.onDisconnected()
                        callback.onConnected(AidlWorkerBinder(service))
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = callback.onDisconnected()
                }
            }
        Shizuku.bindUserService(args, connection)
    }

    override fun unbindWorker(
        callback: WorkerCallback,
        remove: Boolean,
    ) {
        val connection = connections.remove(callback) ?: return
        runCatching { Shizuku.unbindUserService(args, connection, remove) }
    }

    private class AidlWorkerBinder(
        private val binder: IBinder,
    ) : WorkerBinder {
        override val worker: ICoulombPrivileged = ICoulombPrivileged.Stub.asInterface(binder)
        private var recipient: IBinder.DeathRecipient? = null

        override fun linkToDeath(onDeath: () -> Unit): Boolean =
            runCatching {
                val r = IBinder.DeathRecipient { onDeath() }
                binder.linkToDeath(r, 0)
                recipient = r
            }.isSuccess

        override fun unlinkToDeath() {
            recipient?.let { runCatching { binder.unlinkToDeath(it, 0) } }
            recipient = null
        }
    }
}

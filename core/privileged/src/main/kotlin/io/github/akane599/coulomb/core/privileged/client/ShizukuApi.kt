// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import io.github.akane599.coulomb.core.privileged.ICoulombPrivileged

/**
 * The slice of the Shizuku API Coulomb uses, behind an interface so the state machine is testable
 * without a device. The real implementation ([ShizukuApiImpl]) is the only code that touches
 * `rikka.shizuku.*`.
 */
internal interface ShizukuApi {
    /** True when the binder came from Sui (`Sui.isSui()`), set up by ShizukuProvider before Application.onCreate. */
    fun isSui(): Boolean

    fun pingBinder(): Boolean

    /** Reads version, uid and SELinux context from a live binder; null if it died meanwhile. */
    fun serverDetails(): ServerDetails?

    /** Package that defines the Shizuku API permission, i.e. a Shizuku-compatible manager; null if none. */
    fun managerPackage(): ManagerPackage?

    fun checkSelfPermission(): Boolean

    /** True after the user chose "deny and don't ask again" in the manager. */
    fun permissionDeniedPermanently(): Boolean

    fun requestPermission(requestCode: Int)

    /** Registers the three listeners of SPEC 4.2; the binder-received one is sticky. */
    fun setListener(listener: ShizukuEvents?)

    fun bindWorker(callback: WorkerCallback)

    /** `remove = true` stops the worker process (Shizuku sends the destroy transaction). */
    fun unbindWorker(
        callback: WorkerCallback,
        remove: Boolean,
    )
}

internal data class ServerDetails(
    val apiVersion: Int,
    val uid: Int,
    val seLinuxContext: String?,
)

internal data class ManagerPackage(
    val packageName: String,
    val versionName: String?,
)

internal interface ShizukuEvents {
    fun onBinderReceived()

    fun onBinderDead()

    fun onPermissionResult(
        requestCode: Int,
        granted: Boolean,
    )
}

/** A connected worker binder: the AIDL proxy plus death notification. */
internal interface WorkerBinder {
    val worker: ICoulombPrivileged

    /** Returns false when the binder is already dead. */
    fun linkToDeath(onDeath: () -> Unit): Boolean

    fun unlinkToDeath()
}

internal interface WorkerCallback {
    fun onConnected(binder: WorkerBinder)

    fun onDisconnected()
}

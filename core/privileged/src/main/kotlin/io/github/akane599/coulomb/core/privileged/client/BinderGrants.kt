// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import android.app.AppOpsManager
import android.content.Context
import android.os.IBinder
import io.github.akane599.coulomb.core.privileged.DurablePermission
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

internal enum class BinderGrantResult { OK, FAILED, UNAVAILABLE }

/**
 * The SPEC 4.3 fallback for when the worker cannot bind (the Android 17 official-Shizuku hang):
 * the same two bootstrap operations as direct binder calls, sent through the Shizuku server with
 * [ShizukuBinderWrapper], so they run with the server's shell or root identity. Only the bootstrap
 * uses this path; dumps fall back to T1 in Coulomb's own process instead (ADR-0009).
 */
internal interface BinderGrants {
    fun grant(
        permission: DurablePermission,
        packageName: String,
        userId: Int,
    ): BinderGrantResult

    fun allowUsageStats(
        packageName: String,
        uid: Int,
    ): BinderGrantResult
}

/**
 * Hidden interfaces, verified at android-16.0.0_r4 and android-17.0.0_r1 (identical on both):
 * - core/java/android/permission/IPermissionManager.aidl L68-69:
 *   `void grantRuntimePermission(String packageName, String permissionName, String persistentDeviceId, int userId)`,
 *   the call `pm grant` makes (PackageManagerShellCommand.runGrantRevokePermission). Binder name
 *   `permissionmgr` (present in both P0 `dumpsys -l` captures).
 * - core/java/com/android/internal/app/IAppOpsService.aidl L111 / L112:
 *   `void setMode(int code, int uid, String packageName, int mode)`, the call `appops set` makes.
 *   Binder name `appops`. The op code comes from `AppOpsManager.strOpToOp` (L9442 / L9713).
 * - VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT = "default:" + Context.DEVICE_ID_DEFAULT
 *   (L183-184 / L204-205).
 * The stub classes are hidden, so they are reached through HiddenApiBypass.invoke, scoped to these
 * calls (no process-wide exemption).
 */
internal object ShizukuBinderGrants : BinderGrants {
    private const val PERMISSION_MANAGER = "permissionmgr"
    private const val APP_OPS = "appops"
    private const val PERMISSION_MANAGER_STUB = "android.permission.IPermissionManager\$Stub"
    private const val APP_OPS_STUB = "com.android.internal.app.IAppOpsService\$Stub"
    private val persistentDeviceIdDefault = "default:${Context.DEVICE_ID_DEFAULT}"

    override fun grant(
        permission: DurablePermission,
        packageName: String,
        userId: Int,
    ): BinderGrantResult =
        attempt {
            val service =
                asInterface(PERMISSION_MANAGER_STUB, SystemServiceHelper.getSystemService(PERMISSION_MANAGER))
                    ?: return BinderGrantResult.UNAVAILABLE
            HiddenApiBypass.invoke(
                service.javaClass,
                service,
                "grantRuntimePermission",
                packageName,
                permission.permission,
                persistentDeviceIdDefault,
                userId,
            )
        }

    override fun allowUsageStats(
        packageName: String,
        uid: Int,
    ): BinderGrantResult =
        attempt {
            val service = asInterface(APP_OPS_STUB, SystemServiceHelper.getSystemService(APP_OPS)) ?: return BinderGrantResult.UNAVAILABLE
            val op = HiddenApiBypass.invoke(AppOpsManager::class.java, null, "strOpToOp", AppOpsManager.OPSTR_GET_USAGE_STATS) as Int
            HiddenApiBypass.invoke(service.javaClass, service, "setMode", op, uid, packageName, AppOpsManager.MODE_ALLOWED)
        }

    private fun asInterface(
        stubClass: String,
        binder: IBinder?,
    ): Any? {
        if (binder == null) return null
        return HiddenApiBypass.invoke(Class.forName(stubClass), null, "asInterface", ShizukuBinderWrapper(binder))
    }

    /**
     * The service threw (InvocationTargetException, e.g. a SecurityException): the path works but the
     * call failed. Any other reflection or linkage failure: this path does not exist on this build.
     */
    private inline fun attempt(block: () -> Unit): BinderGrantResult =
        try {
            block()
            BinderGrantResult.OK
        } catch (_: java.lang.reflect.InvocationTargetException) {
            BinderGrantResult.FAILED
        } catch (_: ReflectiveOperationException) {
            BinderGrantResult.UNAVAILABLE
        } catch (_: RuntimeException) {
            BinderGrantResult.UNAVAILABLE
        } catch (_: LinkageError) {
            BinderGrantResult.UNAVAILABLE
        }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.system

import android.annotation.SuppressLint
import android.os.Debug
import android.os.IBinder
import java.io.FileDescriptor

/**
 * `dumpsys <service> <args>` without spawning `dumpsys`: [Debug.dumpService] looks the binder up
 * and calls `IBinder.dump(fd, args)`, so the system service writes straight into the caller's pipe.
 * Public API; verified in core/java/android/os/Debug.java, android-16.0.0_r4 L2678-2692 and
 * android-17.0.0_r1 L2706-2720: returns false when the service is missing or the dump threw a
 * RemoteException, and blocks until the service has finished writing. Most services require DUMP;
 * batterystats also checks the usage-stats app op (BatteryStatsService.dump()).
 */
internal object ServiceDumps {
    fun dump(
        binderName: String,
        fd: FileDescriptor,
        args: Array<String>,
    ): Boolean = Debug.dumpService(binderName, fd, args)

    /**
     * Non-blocking registration check, worker process only (hidden API; the worker runs without
     * hidden-API enforcement). `ServiceManager.checkService(String)`: core/java/android/os/
     * ServiceManager.java, android-16.0.0_r4 L274-287, android-17.0.0_r1 L238-251, @UnsupportedAppUsage.
     * Returns null when the lookup itself is unavailable; callers then just try the dump.
     */
    @SuppressLint("PrivateApi")
    fun isRegistered(binderName: String): Boolean? =
        runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            serviceManager.getMethod("checkService", String::class.java).invoke(null, binderName) as IBinder?
        }.map { it != null }.getOrNull()
}

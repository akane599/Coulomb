// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import android.os.ParcelFileDescriptor
import io.github.akane599.coulomb.core.privileged.WorkerStatusCode
import io.github.akane599.coulomb.core.privileged.system.ServiceDumps

/**
 * The T1 path for dumps (SPEC 4.1): with the durable DUMP grant (and the usage-stats op for
 * batterystats), Coulomb's own process may call [ServiceDumps.dump] (public `Debug.dumpService`).
 * Used when the worker is unavailable, e.g. after the Android 17 bind hang. ShizukuBinderWrapper is
 * no help here: its `dump()` calls the wrapped binder directly, in Coulomb's own identity
 * (verified in the 13.1.5 bytecode), so it adds nothing over this path (ADR-0009).
 */
internal fun interface InAppDumper {
    /** Blocking; returns a WorkerStatusCode. Does not close [out]. */
    fun dump(
        binderName: String,
        args: Array<String>,
        out: ParcelFileDescriptor,
    ): Int
}

internal object DebugInAppDumper : InAppDumper {
    override fun dump(
        binderName: String,
        args: Array<String>,
        out: ParcelFileDescriptor,
    ): Int =
        // In-app, a false return cannot tell a missing service from a failed dump (no hidden API).
        if (ServiceDumps.dump(binderName, out.fileDescriptor, args)) WorkerStatusCode.OK else WorkerStatusCode.DUMP_FAILED
}

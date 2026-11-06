// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.worker

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import io.github.akane599.coulomb.core.privileged.BatteryStatsMode
import io.github.akane599.coulomb.core.privileged.DurablePermission
import io.github.akane599.coulomb.core.privileged.ICoulombPrivileged
import io.github.akane599.coulomb.core.privileged.PrivilegedQuery
import io.github.akane599.coulomb.core.privileged.SettingsNamespace
import io.github.akane599.coulomb.core.privileged.SysfsRoot
import io.github.akane599.coulomb.core.privileged.SystemService
import io.github.akane599.coulomb.core.privileged.TestPattern
import io.github.akane599.coulomb.core.privileged.WorkerLimits
import io.github.akane599.coulomb.core.privileged.WorkerStatusCode
import io.github.akane599.coulomb.core.privileged.system.CmdCommands
import io.github.akane599.coulomb.core.privileged.system.ServiceDumps
import io.github.akane599.coulomb.core.privileged.system.ShellRunner
import io.github.akane599.coulomb.core.privileged.system.Sysfs
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.system.exitProcess

/**
 * The privileged worker (SPEC 4.3), started by Shizuku or Sui as a UserService in its own process
 * running as shell (uid 2000) or root. It only exposes the typed allowlist in [ICoulombPrivileged].
 *
 * Shizuku creates it reflectively through the `(Context)` constructor, available from Shizuku API 13
 * (Shizuku-API server-shared UserService.java: `getConstructor(Context.class)` with a no-arg
 * fallback). There is deliberately no no-arg constructor: without a Context the worker could not
 * learn Coulomb's uid, so on an older server it fails to start and the app falls back (SPEC 4.5).
 */
public class CoulombPrivilegedService(
    context: Context,
) : ICoulombPrivileged.Stub() {
    private val ownerUid: Int = context.applicationInfo.uid
    private val ownerPackage: String = context.packageName
    private val packageManager: PackageManager = context.packageManager
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val version: Long = runCatching { packageManager.getPackageInfo(ownerPackage, 0).longVersionCode }.getOrDefault(-1L)
    private val shell = ShellRunner(WorkerLimits.COMMAND_TIMEOUT_MS)

    // --- identity -------------------------------------------------------------------------------

    /** SPEC 4.3 caller check, on every call. */
    private fun enforceCaller() {
        val caller = Binder.getCallingUid()
        if (caller != ownerUid) throw SecurityException("uid $caller may not use this worker")
    }

    /** The caller check for calls that received a pipe: a refused caller's pipe is closed as well. */
    private fun enforceCaller(pipe: ParcelFileDescriptor?) {
        try {
            enforceCaller()
        } catch (e: SecurityException) {
            runCatching { pipe?.close() }
            throw e
        }
    }

    /** Coulomb's Android user, from its uid (UserHandle.PER_USER_RANGE = 100000). */
    private val userId: Int get() = ownerUid / PER_USER_RANGE

    override fun destroy() {
        // Shizuku sends this from its server process, which runs with the worker's own uid.
        val caller = Binder.getCallingUid()
        if (caller != ownerUid && caller != Process.myUid()) throw SecurityException("uid $caller may not stop this worker")
        exitProcess(0)
    }

    override fun getVersion(): Long {
        enforceCaller()
        return version
    }

    override fun getUid(): Int {
        enforceCaller()
        return Process.myUid()
    }

    override fun getSeLinuxContext(): String? {
        enforceCaller()
        return runCatching { File("/proc/self/attr/current").readText().trim('\u0000', '\n', ' ') }.getOrNull()
    }

    // --- dumps ----------------------------------------------------------------------------------

    override fun dumpBatteryStats(
        mode: Int,
        historyStart: Long,
        out: ParcelFileDescriptor?,
    ): Int {
        enforceCaller(out)
        if (out == null) return WorkerStatusCode.INVALID_ARGUMENT
        try {
            val m = BatteryStatsMode.fromId(mode) ?: return WorkerStatusCode.INVALID_ARGUMENT
            return dump(BATTERYSTATS, m.args(historyStart), out)
        } finally {
            out.close()
        }
    }

    override fun dumpService(
        service: Int,
        out: ParcelFileDescriptor?,
    ): Int {
        enforceCaller(out)
        if (out == null) return WorkerStatusCode.INVALID_ARGUMENT
        try {
            val s = SystemService.fromId(service) ?: return WorkerStatusCode.INVALID_ARGUMENT
            return dump(s.binderName, emptyArray(), out)
        } finally {
            out.close()
        }
    }

    private fun dump(
        binderName: String,
        args: Array<String>,
        out: ParcelFileDescriptor,
    ): Int {
        if (ServiceDumps.isRegistered(binderName) == false) return WorkerStatusCode.SERVICE_NOT_FOUND
        return if (ServiceDumps.dump(binderName, out.fileDescriptor, args)) WorkerStatusCode.OK else WorkerStatusCode.DUMP_FAILED
    }

    // --- read-only commands ---------------------------------------------------------------------

    override fun runQuery(
        query: Int,
        packageName: String?,
        out: ParcelFileDescriptor?,
    ): Int {
        enforceCaller(out)
        if (out == null) return WorkerStatusCode.INVALID_ARGUMENT
        ParcelFileDescriptor.AutoCloseOutputStream(out).use { sink ->
            val argv = queryArgv(query, packageName) ?: return WorkerStatusCode.INVALID_ARGUMENT
            return runTo(argv, sink)
        }
    }

    /** Null for an unknown query or a package that is not installed (SPEC 4.3 validation). */
    private fun queryArgv(
        query: Int,
        packageName: String?,
    ): List<String>? =
        when (PrivilegedQuery.fromId(query)) {
            PrivilegedQuery.STANDBY_BUCKETS -> CmdCommands.standbyBuckets(userId)
            PrivilegedQuery.DEVICE_IDLE_WHITELIST -> CmdCommands.deviceIdleWhitelist()
            PrivilegedQuery.APP_OPS -> packageName?.takeIf(::isInstalledPackage)?.let { CmdCommands.appOps(it, userId) }
            null -> null
        }

    private fun runTo(
        argv: List<String>,
        sink: OutputStream,
    ): Int =
        try {
            val r = shell.run(argv, sink, WorkerLimits.MAX_COMMAND_OUTPUT_BYTES)
            when {
                r.timedOut -> WorkerStatusCode.TIMEOUT
                r.exitCode != 0 -> WorkerStatusCode.COMMAND_FAILED
                else -> WorkerStatusCode.OK
            }
        } catch (_: IOException) {
            WorkerStatusCode.IO_ERROR
        }

    override fun getSetting(
        namespace: Int,
        key: String?,
        status: IntArray?,
    ): String? {
        enforceCaller()
        val ns = SettingsNamespace.fromId(namespace)
        if (ns == null || key == null || !Validation.isBatterySettingsKey(key)) return status.set(WorkerStatusCode.INVALID_ARGUMENT, null)
        val r = shell.run(CmdCommands.settingsGet(ns, key, userId), null, MAX_SETTINGS_OUTPUT)
        if (!r.ok) return status.set(commandStatus(r), null)
        return status.set(WorkerStatusCode.OK, CmdCommands.parseSettingValue(r.stdoutText))
    }

    override fun listSettingKeys(
        namespace: Int,
        status: IntArray?,
    ): Array<String>? {
        enforceCaller()
        val ns = SettingsNamespace.fromId(namespace) ?: return status.set(WorkerStatusCode.INVALID_ARGUMENT, null)
        val r = shell.run(CmdCommands.settingsList(ns, userId), null, WorkerLimits.MAX_COMMAND_OUTPUT_BYTES)
        if (!r.ok) return status.set(commandStatus(r), null)
        // Values never leave the worker; only allowlisted key names do (ADR-0009).
        val keys =
            CmdCommands
                .parseSettingKeys(r.stdoutText)
                .filter(Validation::isBatterySettingsKey)
                .distinct()
                .sorted()
        return status.set(WorkerStatusCode.OK, keys.toTypedArray())
    }

    override fun readSysfs(
        root: Int,
        relativePath: String?,
        status: IntArray?,
    ): ByteArray? {
        enforceCaller()
        val path = sysfsPath(root, relativePath, allowRoot = false) ?: return status.set(WorkerStatusCode.INVALID_ARGUMENT, null)
        return when (val r = Sysfs.read(path, WorkerLimits.MAX_SYSFS_BYTES)) {
            is Sysfs.Read.Ok -> status.set(WorkerStatusCode.OK, r.bytes)
            is Sysfs.Read.Failed -> status.set(errnoStatus(r.errno), null)
        }
    }

    override fun listSysfs(
        root: Int,
        relativeDir: String?,
        status: IntArray?,
    ): Array<String>? {
        enforceCaller()
        val path = sysfsPath(root, relativeDir, allowRoot = true) ?: return status.set(WorkerStatusCode.INVALID_ARGUMENT, null)
        return when (val r = Sysfs.list(path, WorkerLimits.MAX_SYSFS_ENTRIES)) {
            is Sysfs.Listing.Ok -> status.set(WorkerStatusCode.OK, r.names.toTypedArray())
            is Sysfs.Listing.Failed -> status.set(errnoStatus(r.errno), null)
        }
    }

    /** Null when the root id or path is not allowed. */
    private fun sysfsPath(
        root: Int,
        relative: String?,
        allowRoot: Boolean,
    ): String? {
        val base = SysfsRoot.fromId(root) ?: return null
        val segments = Validation.sysfsSegments(relative, allowRoot) ?: return null
        val path = (listOf(base.path) + segments).joinToString("/")
        // SPEC 19.1: only the class entry (first segment) may be a symlink into /sys/devices; inner
        // links such as `device` or `subsystem` would leave it, so the canonical path must stay inside
        // the entry's canonical directory. Canonicalisation failures are refused.
        val entry = segments.firstOrNull()?.let { "${base.path}/$it" } ?: base.path
        val canonicalEntry = canonical(entry) ?: return null
        val canonicalPath = canonical(path) ?: return null
        return path.takeIf { canonicalPath.startsWith("/sys/") && Validation.isContained(canonicalEntry, canonicalPath) }
    }

    private fun canonical(path: String): String? = runCatching { File(path).canonicalPath }.getOrNull()

    private fun errnoStatus(errno: Int): Int =
        when (errno) {
            OsConstants.EACCES, OsConstants.EPERM -> WorkerStatusCode.ACCESS_DENIED
            OsConstants.ENOENT, OsConstants.ENOTDIR -> WorkerStatusCode.NOT_FOUND
            else -> WorkerStatusCode.IO_ERROR
        }

    /** Writes [code] into the caller's `out int[1]` and returns [value]. */
    private fun <T> IntArray?.set(
        code: Int,
        value: T,
    ): T {
        if (this != null && isNotEmpty()) this[0] = code
        return value
    }

    // --- bootstrap (SPEC 4.4) --------------------------------------------------------------------

    override fun grantPermission(permission: Int): Int {
        enforceCaller()
        val p = DurablePermission.fromId(permission) ?: return WorkerStatusCode.INVALID_ARGUMENT
        return commandStatus(shell.run(CmdCommands.grant(ownerPackage, p, userId), null, MAX_SETTINGS_OUTPUT))
    }

    override fun allowUsageStatsAppOp(): Int {
        enforceCaller()
        return commandStatus(shell.run(CmdCommands.allowUsageStats(ownerPackage, userId), null, MAX_SETTINGS_OUTPUT))
    }

    private fun commandStatus(r: ShellRunner.Result): Int =
        when {
            r.timedOut -> WorkerStatusCode.TIMEOUT
            r.exitCode != 0 -> WorkerStatusCode.COMMAND_FAILED
            else -> WorkerStatusCode.OK
        }

    // --- transport test ---------------------------------------------------------------------------

    override fun streamTestPattern(
        bytes: Long,
        truncateAt: Long,
        chunkDelayMs: Int,
        out: ParcelFileDescriptor?,
    ): Int {
        enforceCaller(out)
        if (out == null) return WorkerStatusCode.INVALID_ARGUMENT
        ParcelFileDescriptor.AutoCloseOutputStream(out).use { sink ->
            return when {
                !debuggable -> WorkerStatusCode.NOT_PERMITTED
                bytes !in 0..WorkerLimits.MAX_TEST_PATTERN_BYTES -> WorkerStatusCode.INVALID_ARGUMENT
                else -> writePattern(sink, truncateAt.coerceIn(0L, bytes), chunkDelayMs.coerceIn(0, MAX_CHUNK_DELAY_MS).toLong())
            }
        }
    }

    private fun writePattern(
        sink: OutputStream,
        limit: Long,
        delayMs: Long,
    ): Int {
        val buf = ByteArray(TEST_CHUNK)
        var written = 0L
        return try {
            while (written < limit) {
                val n = minOf(TEST_CHUNK.toLong(), limit - written).toInt()
                TestPattern.fill(buf, written, n)
                sink.write(buf, 0, n)
                written += n
                if (delayMs > 0) Thread.sleep(delayMs)
            }
            WorkerStatusCode.OK
        } catch (_: IOException) {
            // The reader closed its end (timeout or cancellation): EPIPE.
            WorkerStatusCode.IO_ERROR
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            WorkerStatusCode.IO_ERROR
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** SPEC 4.3: package names are validated by shape and against PackageManager. */
    private fun isInstalledPackage(name: String): Boolean =
        Validation.isPackageName(name) &&
            runCatching { packageManager.getPackageUid(name, 0) }.isSuccess

    private companion object {
        const val BATTERYSTATS = "batterystats"
        const val PER_USER_RANGE = 100_000
        const val MAX_SETTINGS_OUTPUT = 64L * 1024
        const val TEST_CHUNK = 64 * 1024
        const val MAX_CHUNK_DELAY_MS = 1_000
    }
}

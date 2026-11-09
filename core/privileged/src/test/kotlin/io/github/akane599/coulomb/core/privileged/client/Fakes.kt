// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import android.content.Intent
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.privileged.DurablePermission
import io.github.akane599.coulomb.core.privileged.ICoulombPrivileged
import io.github.akane599.coulomb.core.privileged.TestPattern
import io.github.akane599.coulomb.core.privileged.WorkerStatusCode
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.IdentityHashMap

/**
 * In-memory pipes. The "ParcelFileDescriptor" handed to the worker is a stub token (the mockable
 * android.jar has no real fds); the fake worker looks up the matching output stream.
 */
internal class FakePipes : PipeFactory {
    private val outputs = IdentityHashMap<ParcelFileDescriptor, OutputStream>()

    fun output(token: ParcelFileDescriptor): OutputStream = synchronized(outputs) { outputs.getValue(token) }

    override fun create(): PipePair<ParcelFileDescriptor> {
        val out = PipedOutputStream()
        val input = PipedInputStream(out, PIPE_BUFFER)
        val token = ParcelFileDescriptor(null as ParcelFileDescriptor?)
        synchronized(outputs) { outputs[token] = out }
        return object : PipePair<ParcelFileDescriptor> {
            override val input: InputStream = input
            override val sink: ParcelFileDescriptor = token

            override fun closeSink() {
                runCatching { out.close() }
            }

            override fun closeInput() {
                runCatching { input.close() }
            }
        }
    }

    companion object {
        const val PIPE_BUFFER = 1024 * 1024
    }
}

internal class FakeWorker(
    private val pipes: FakePipes,
    var reportedVersion: Long = VERSION,
) : ICoulombPrivileged {
    var dumpPayload: ByteArray = "battery stats\n".toByteArray()
    var dumpStatus: Int = WorkerStatusCode.OK
    var dieDuringDump = false
    var destroyed = 0
    val granted = mutableListOf<Int>()
    var usageStatsAllowed = 0
    var onGrant: (Int) -> Unit = {}
    var onAllowUsageStats: () -> Unit = {}
    var sysfs: Map<String, ByteArray> = emptyMap()
    var workerUid: Int = 2000
    var kernelFiles: ByteArray = "#end\n".toByteArray()
    var kernelLog: ByteArray = "#begin boot=00000000-0000-0000-0000-000000000000 after=- first=-\n#end last=- lost=0\n".toByteArray()
    var kernelStatus: Int = WorkerStatusCode.OK
    val kernelLogCalls = mutableListOf<Pair<Long, Long>>()

    override fun asBinder(): IBinder? = null

    override fun destroy() {
        destroyed += 1
    }

    override fun getVersion(): Long = reportedVersion

    override fun getUid(): Int = workerUid

    override fun getSeLinuxContext(): String = "u:r:shell:s0"

    private fun write(
        pipe: ParcelFileDescriptor?,
        bytes: ByteArray,
    ): Int {
        val out = pipes.output(pipe!!)
        out.write(bytes)
        out.flush()
        if (dieDuringDump) throw DeadObjectException()
        return dumpStatus
    }

    override fun dumpBatteryStats(
        mode: Int,
        historyStart: Long,
        pipe: ParcelFileDescriptor?,
    ): Int = write(pipe, dumpPayload)

    override fun dumpService(
        service: Int,
        pipe: ParcelFileDescriptor?,
    ): Int = write(pipe, dumpPayload)

    override fun runQuery(
        query: Int,
        packageName: String?,
        pipe: ParcelFileDescriptor?,
    ): Int = write(pipe, "pkg: 10\n".toByteArray())

    override fun getSetting(
        namespace: Int,
        key: String?,
        status: IntArray?,
    ): String? {
        status!![0] = WorkerStatusCode.OK
        return null
    }

    override fun listSettingKeys(
        namespace: Int,
        status: IntArray?,
    ): Array<String> {
        status!![0] = WorkerStatusCode.OK
        return arrayOf("protect_battery")
    }

    override fun readSysfs(
        root: Int,
        relativePath: String?,
        status: IntArray?,
    ): ByteArray? {
        val bytes = sysfs[relativePath]
        status!![0] = if (bytes == null) WorkerStatusCode.ACCESS_DENIED else WorkerStatusCode.OK
        return bytes
    }

    override fun listSysfs(
        root: Int,
        relativeDir: String?,
        status: IntArray?,
    ): Array<String> {
        status!![0] = WorkerStatusCode.OK
        return arrayOf("battery")
    }

    override fun grantPermission(permission: Int): Int {
        granted += permission
        onGrant(permission)
        return WorkerStatusCode.OK
    }

    override fun allowUsageStatsAppOp(): Int {
        usageStatsAllowed += 1
        onAllowUsageStats()
        return WorkerStatusCode.OK
    }

    override fun readKernelFiles(
        sources: Int,
        pipe: ParcelFileDescriptor?,
    ): Int {
        if (kernelStatus == WorkerStatusCode.OK) pipes.output(pipe!!).write(kernelFiles)
        return kernelStatus
    }

    override fun readKernelLog(
        bootKey: Long,
        afterSeq: Long,
        pipe: ParcelFileDescriptor?,
    ): Int {
        kernelLogCalls += bootKey to afterSeq
        if (kernelStatus == WorkerStatusCode.OK) pipes.output(pipe!!).write(kernelLog)
        return kernelStatus
    }

    override fun streamTestPattern(
        bytes: Long,
        truncateAt: Long,
        chunkDelayMs: Int,
        pipe: ParcelFileDescriptor?,
    ): Int {
        val out = pipes.output(pipe!!)
        val limit = truncateAt.coerceIn(0, bytes)
        val buf = ByteArray(CHUNK)
        var written = 0L
        while (written < limit) {
            val n = minOf(CHUNK.toLong(), limit - written).toInt()
            TestPattern.fill(buf, written, n)
            out.write(buf, 0, n)
            written += n
        }
        return WorkerStatusCode.OK
    }

    companion object {
        const val VERSION = 7L
        const val CHUNK = 64 * 1024
    }
}

internal class FakeWorkerBinder(
    override val worker: ICoulombPrivileged,
) : WorkerBinder {
    private var onDeath: (() -> Unit)? = null
    var alive = true

    override fun linkToDeath(onDeath: () -> Unit): Boolean {
        if (!alive) return false
        this.onDeath = onDeath
        return true
    }

    override fun unlinkToDeath() {
        onDeath = null
    }

    fun die() {
        alive = false
        onDeath?.invoke()
    }
}

internal class FakeShizukuApi(
    var worker: ICoulombPrivileged?,
) : ShizukuApi {
    var sui = false
    var binderAlive = false
    var details = ServerDetails(apiVersion = 13, uid = 2000, seLinuxContext = "u:r:shell:s0")
    var manager: ManagerPackage? = ManagerPackage("moe.shizuku.privileged.api", "13.6.0")
    var permissionGranted = false
    var deniedPermanently = false
    var events: ShizukuEvents? = null
    var permissionRequests = 0

    /** When false, bindWorker never calls back (the Android 17 hang). */
    var connects = true
    var binds = 0
    val unbinds = mutableListOf<Boolean>()
    var lastBinder: FakeWorkerBinder? = null
    private var connected: WorkerCallback? = null

    override fun isSui(): Boolean = sui

    override fun pingBinder(): Boolean = binderAlive

    override fun serverDetails(): ServerDetails? = if (binderAlive) details else null

    override fun managerPackage(): ManagerPackage? = manager

    override fun managerLaunchIntent(): Intent? = null

    override fun checkSelfPermission(): Boolean = permissionGranted

    override fun permissionDeniedPermanently(): Boolean = deniedPermanently

    override fun requestPermission(requestCode: Int) {
        permissionRequests += 1
    }

    override fun setListener(listener: ShizukuEvents?) {
        events = listener
        if (binderAlive) listener?.onBinderReceived()
    }

    override fun bindWorker(callback: WorkerCallback) {
        binds += 1
        if (!connects) return
        val w = worker ?: return
        connected = callback
        callback.onConnected(FakeWorkerBinder(w).also { lastBinder = it })
    }

    override fun unbindWorker(
        callback: WorkerCallback,
        remove: Boolean,
    ) {
        unbinds += remove
    }

    fun startServer() {
        binderAlive = true
        events?.onBinderReceived()
    }

    fun stopServer() {
        binderAlive = false
        events?.onBinderDead()
    }
}

internal class FakeGrants(
    var status: GrantStatus = GrantStatus(),
) : GrantSource {
    override fun read(): GrantStatus = status

    fun grant(permission: Int) {
        status =
            when (permission) {
                DurablePermission.BATTERY_STATS.id -> status.copy(batteryStats = true)
                DurablePermission.DUMP.id -> status.copy(dump = true)
                DurablePermission.WRITE_SECURE_SETTINGS.id -> status.copy(writeSecureSettings = true)
                DurablePermission.PACKAGE_USAGE_STATS.id -> status.copy(usageStatsPermission = true)
                DurablePermission.INTERACT_ACROSS_USERS.id -> status.copy(interactAcrossUsers = true)
                else -> status
            }
    }
}

internal class FakeLedger(
    var record: LedgerRecord = LedgerRecord(),
) : PrivilegeLedger {
    override fun read(): LedgerRecord = record

    override fun write(record: LedgerRecord) {
        this.record = record
    }
}

internal class FakeBinderGrants(
    var result: BinderGrantResult = BinderGrantResult.UNAVAILABLE,
) : BinderGrants {
    val calls = mutableListOf<String>()
    var onGrant: (DurablePermission) -> Unit = {}
    var onAllowUsageStats: () -> Unit = {}

    override fun grant(
        permission: DurablePermission,
        packageName: String,
        userId: Int,
    ): BinderGrantResult {
        calls += "grant ${permission.name} $packageName $userId"
        if (result == BinderGrantResult.OK) onGrant(permission)
        return result
    }

    override fun allowUsageStats(
        packageName: String,
        uid: Int,
    ): BinderGrantResult {
        calls += "appops $packageName $uid"
        if (result == BinderGrantResult.OK) onAllowUsageStats()
        return result
    }
}

internal class FakeInAppDumper(
    private val pipes: FakePipes,
) : InAppDumper {
    var calls = 0
    var payload: ByteArray = "in-app dump\n".toByteArray()
    var throwAfterWrite: Throwable? = null

    override fun dump(
        binderName: String,
        args: Array<String>,
        out: ParcelFileDescriptor,
    ): Int {
        calls += 1
        pipes.output(out).write(payload)
        throwAfterWrite?.let { throw it }
        return WorkerStatusCode.OK
    }
}

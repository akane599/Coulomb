// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.os.BatteryManager
import io.github.akane599.coulomb.core.collect.CollectorClock
import io.github.akane599.coulomb.core.collect.snapshot.HistoryRead
import io.github.akane599.coulomb.core.collect.snapshot.KernelFilesRead
import io.github.akane599.coulomb.core.collect.snapshot.KernelLogResult
import io.github.akane599.coulomb.core.collect.snapshot.KernelReader
import io.github.akane599.coulomb.core.collect.snapshot.LightReading
import io.github.akane599.coulomb.core.collect.snapshot.LightSource
import io.github.akane599.coulomb.core.collect.snapshot.SourceStatus
import io.github.akane599.coulomb.core.collect.snapshot.StatsRead
import io.github.akane599.coulomb.core.collect.snapshot.UsageRead
import io.github.akane599.coulomb.core.model.PrivilegeMode
import io.github.akane599.coulomb.core.model.kernel.KernelLogEvent
import io.github.akane599.coulomb.core.model.kernel.KernelLogRecord
import io.github.akane599.coulomb.core.model.kernel.LastWakeup
import io.github.akane599.coulomb.core.model.kernel.SuspendStats
import io.github.akane599.coulomb.core.model.kernel.SuspendStep
import io.github.akane599.coulomb.core.model.kernel.WakeupCause
import io.github.akane599.coulomb.core.model.kernel.WakeupIrq
import io.github.akane599.coulomb.core.model.kernel.WakeupSourceInstance
import io.github.akane599.coulomb.core.model.kernel.WakeupSourceStats
import io.github.akane599.coulomb.core.model.stats.BatteryStatsDump
import io.github.akane599.coulomb.core.model.stats.ComponentPower
import io.github.akane599.coulomb.core.model.stats.DeviceMisc
import io.github.akane599.coulomb.core.model.stats.DeviceStats
import io.github.akane599.coulomb.core.model.stats.PowerColumn
import io.github.akane599.coulomb.core.model.stats.PowerSummary
import io.github.akane599.coulomb.core.model.stats.SipperKind
import io.github.akane599.coulomb.core.model.stats.SipperPower
import io.github.akane599.coulomb.core.model.stats.UidStats
import io.github.akane599.coulomb.core.model.stats.UsageStatsDump
import io.github.akane599.coulomb.core.parsing.kernel.KernelLogRead
import io.github.akane599.coulomb.core.privileged.WorkerOrigin
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToLong

// Fakes for the engine tests: the battery, the clock, the dumps and the kernel.

internal class FakeClock : CollectorClock {
    var elapsedNs = 0L
    var wallOffsetMs = START_WALL_MS
    private var sleptMs = 0L

    override fun elapsedNs(): Long = elapsedNs

    override fun uptimeMs(): Long = elapsedNs / NS_PER_MS - sleptMs

    override fun wallMs(): Long = wallOffsetMs + elapsedNs / NS_PER_MS

    override fun wallChanges(): Int = 0

    /** The CPU sleeps: elapsed time goes on, uptime does not. */
    fun sleep(ms: Long) {
        elapsedNs += ms * NS_PER_MS
        sleptMs += ms
    }
}

/** The battery: its reading, with the clock set to the same instant. */
internal class FakeDevice(
    private val clock: FakeClock,
) : LightSource {
    private var reading: LightReading? = null

    fun set(
        hours: Double,
        level: Int,
        chargeMah: Double,
        screenOn: Boolean,
        plugged: Boolean = false,
        status: Int = if (plugged) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING,
    ) {
        clock.elapsedNs = hoursNs(hours)
        reading =
            LightReading(
                elapsedNs = clock.elapsedNs,
                wallMs = clock.wallMs(),
                levelPct = level,
                status = status,
                plugType = if (plugged) BatteryManager.BATTERY_PLUGGED_USB else 0,
                health = BatteryManager.BATTERY_HEALTH_GOOD,
                voltageMv = 3_900,
                tempDeciC = 300,
                cycleCount = 100,
                currentRaw = if (plugged) 3_000 else -400,
                currentAverageRaw = null,
                chargeUah = (chargeMah * 1_000).toLong(),
                energyNwh = null,
                screenOn = screenOn,
                dozeState = 0,
                powerSave = false,
                thermalStatus = 0,
            )
    }

    override fun read(): LightReading = checkNotNull(reading)
}

/**
 * S5 as a growing app and screen total; a reset or reboot starts the counters again. S4 grows with
 * it: the app's and the device's CPU energy, the same as S5's modeled totals.
 */
internal class FakeStats(
    private val clock: FakeClock,
) : HeavySource {
    var available = true
    val historyTimeoutsMs = mutableListOf<Long>()

    /** How long the CPU sleeps inside the next dump. */
    var sleepMs = 0L

    /** How long the next S5 dump takes awake. */
    var statsTakesMs = 0L

    /** Realtime not on battery since the counters started: S5's battery time trails its total by it. */
    var notOnBatteryMs = 0L

    /** S4 reads made, how long the CPU sleeps inside the next, and what it returns instead of the default. */
    var usageReads = 0
    var usageSleepMs = 0L
    var usage: ((UsageRead) -> UsageRead)? = null

    /** The statuses the next dump reports; S5 whole by default. */
    var statuses = mapOf("S5" to SourceStatus.OK)

    /** What the next history read returns. */
    var history: () -> HistoryRead = { HistoryRead(emptyList(), null, SourceStatus.FAILED) }
    private var countedFromNs = 0L
    private var startWallMs = START_WALL_MS

    fun resetAt(hours: Double) {
        countedFromNs = hoursNs(hours)
        startWallMs = clock.wallOffsetMs + hoursMs(hours)
    }

    fun reboot() {
        countedFromNs = 0
    }

    override fun available(): Boolean = available

    override suspend fun stats(timeoutMs: Long): StatsRead {
        val sinceMs = (clock.elapsedNs - countedFromNs) / NS_PER_MS
        val dump =
            BatteryStatsDump(
                header = null,
                device =
                    DeviceStats(
                        startClockTimeWallMs = startWallMs,
                        totalRealtimeMs = sinceMs,
                        batteryRealtimeMs = sinceMs - notOnBatteryMs,
                        misc = DeviceMisc(screenOnMs = sinceMs / 4),
                        powerSummary = PowerSummary(capacityMah = 5_000.0),
                        powerUse = listOf(SipperPower(SipperKind.UNKNOWN, uid = null, mah = mah(sinceMs))),
                    ),
                uids = listOf(UidStats(10_100, packages = listOf("com.example.video"), powerMah = mah(sinceMs))),
                uidsComplete = true,
            )
        clock.elapsedNs += statsTakesMs * NS_PER_MS
        clock.sleep(sleepMs)
        val whole = statuses.values.all { it == SourceStatus.OK }
        return StatsRead(dump, statuses, rawProto = byteArrayOf(1), rawIncomplete = byteArrayOf(2).takeUnless { whole })
    }

    override suspend fun usage(timeoutMs: Long): UsageRead {
        usageReads++
        val dc = (mah((clock.elapsedNs - countedFromNs) / NS_PER_MS) * 36).roundToLong()
        val app = ComponentPower.of(mapOf(PowerColumn.CPU to dc, PowerColumn.BACKGROUND to dc))
        val dump =
            UsageStatsDump(
                sessionStartMillis = startWallMs,
                sessionEndMillis = clock.wallMs(),
                device = ComponentPower.of(mapOf(PowerColumn.CPU to dc)),
                uids = mapOf(10_100 to app),
                failures = emptyMap(),
            )
        clock.sleep(usageSleepMs)
        val read = UsageRead(dump, SourceStatus.OK, byteArrayOf(3))
        return usage?.invoke(read) ?: read
    }

    /** The app's modeled energy after [sinceMs] of counting: 1 mAh a minute. */
    private fun mah(sinceMs: Long) = sinceMs / 60_000.0

    override suspend fun history(
        cursorMs: Long,
        timeoutMs: Long,
    ): HistoryRead {
        historyTimeoutsMs += timeoutMs
        return history()
    }

    /** The Shizuku server's mode; null when Shizuku is stopped. */
    var mode: PrivilegeMode? = PrivilegeMode.SHELL

    /** The manager binder's number; a restart or mode switch moves it on. */
    var connection = 1L

    /** Set to move [connection] on between the kernel files and the log: a switch inside a snapshot. */
    var switchInsideSnapshot = false
    val kernel = FakeKernel()
    val logCursors = mutableListOf<Long>()

    private val origin get() = WorkerOrigin(if (mode == PrivilegeMode.ROOT) 0 else 2_000, connection)

    override fun liveMode(): PrivilegeMode? = mode

    override fun kernelReadable(): Boolean = available && mode == PrivilegeMode.ROOT

    /** How long the CPU sleeps inside the next kernel files read. */
    var kernelFilesSleepMs = 0L

    override suspend fun kernelFiles(timeoutMs: Long): KernelFilesRead = kernel.files(origin).also { clock.sleep(kernelFilesSleepMs) }

    override suspend fun kernelLog(
        bootKey: Long,
        afterSeq: Long,
        timeoutMs: Long,
    ): KernelLogResult {
        if (switchInsideSnapshot) connection++
        logCursors += afterSeq
        return kernel.log(bootKey, afterSeq, origin)
    }
}

/**
 * A kernel as the worker would report it: `suspend_stats`, the last wakeup, `wakeupN` sources and
 * the S27 ring, whose kept records sit among others the worker filters out.
 */
internal class FakeKernel {
    var bootKey = 0x5eed_0000_0000_0001L
    private var success = 0L
    private var fail = 0L
    private var head = 100L
    private val records = mutableListOf<KernelLogRecord>()
    private val sources = linkedMapOf(1 to source("wlan_rx_wake"), 2 to source("alarmtimer"))
    private var cause: WakeupCause = WakeupCause.None

    /** Set to fail the kernel files read before the worker answers, as an unavailable call does. */
    var filesFail = false

    /** Kept records per log read, as the worker's cap; a capped read is cut short. */
    var capAt: Int? = null

    /** [n] suspends, each woken by [irq]; the matching source's wakeups rise with them. */
    fun suspend(
        n: Int,
        irq: String = "qcom,smp2p-modem",
        sourceInstance: Int = 1,
    ) {
        repeat(n) {
            success++
            head += 2
            records += KernelLogRecord(head, head * 1_000, KernelLogEvent.ResumeIrq(WakeupIrq(200, irq)))
            sources.computeIfPresent(sourceInstance) { _, s -> s.copy(wakeupCount = s.wakeupCount + 1, totalTimeMs = s.totalTimeMs + 50) }
        }
        cause = WakeupCause.Irqs(listOf(WakeupIrq(200, irq)))
    }

    /** A suspend aborted by [reason], with the device line the kernel prints beside it. */
    fun abort(reason: String) {
        fail++
        head += 1
        records += KernelLogRecord(head, head * 1_000, KernelLogEvent.DeviceFailed("alarmtimer alarmtimer.0.auto", "suspend", -16))
        head += 1
        records += KernelLogRecord(head, head * 1_000, KernelLogEvent.Abort(reason))
        cause = WakeupCause.Abort(reason)
    }

    fun reboot() {
        bootKey++
        success = 0
        fail = 0
        head = 0
        records.clear()
    }

    fun files(origin: WorkerOrigin): KernelFilesRead =
        if (filesFail) {
            KernelFilesRead.all(SourceStatus.FAILED)
        } else {
            readFiles(origin)
        }

    private fun readFiles(origin: WorkerOrigin): KernelFilesRead =
        KernelFilesRead(
            bootKey = bootKey,
            suspendStats = SuspendStats(success, fail, mapOf(SuspendStep.SUSPEND to fail), null, null, null),
            lastWakeup = LastWakeup(cause, 1_000, 2_000),
            wakeupSources = sources.map { (n, s) -> WakeupSourceInstance(n, s) },
            statuses = KernelReader.FILE_SOURCES.associateWith { SourceStatus.OK },
            origin = origin,
        )

    /** As the worker: a cursor of another boot is not applied. */
    fun log(
        bootKey: Long,
        afterSeq: Long,
        origin: WorkerOrigin,
    ): KernelLogResult {
        val after = afterSeq.takeIf { bootKey == this.bootKey && it >= 0 }
        val from = after ?: -1
        val past = records.filter { it.seq > from }
        val cap = capAt
        if (cap != null && past.size > cap) {
            val kept = past.take(cap)
            val cut = KernelLogRead(this.bootKey, after, kept, firstSeq = from + 1, lastSeq = kept.last().seq, gaps = emptyList())
            return KernelLogResult(cut, SourceStatus.PARTIAL, origin)
        }
        val kept = past
        val read =
            KernelLogRead(
                this.bootKey,
                after,
                kept,
                firstSeq = (from + 1).takeIf { head > from },
                lastSeq = head.takeIf { head > from },
                gaps = emptyList(),
            )
        return KernelLogResult(read, SourceStatus.OK, origin)
    }

    private fun source(name: String) = WakeupSourceStats(name, 10, 10, 0, 0, 0, 1_000, 100, 0, 0)
}

internal class FakeIdentity : DeviceIdentity {
    var bootCount = 7
    override var fingerprint = "test/device:17/1"
    override val model = "test"
    override val oem = "test"
    override val sdkInt = 37
    override val sdkIntFull = 3_700_000
    override val appUid = OWN_UID

    override fun bootCount(): Int = bootCount

    override fun zone(): ZoneId = ZoneOffset.UTC
}

internal class MemoryBlobs : BlobStore {
    private val blobs = LinkedHashMap<String, Long>()

    override fun write(
        baseName: String,
        parts: Map<String, ByteArray>,
    ): String {
        blobs[baseName] = parts.values.sumOf { it.size.toLong() }
        return baseName
    }

    override fun delete(baseNames: Collection<String>) {
        baseNames.forEach(blobs::remove)
    }

    override fun list(): List<Pair<String, Long>> = blobs.toList()
}

internal const val NS_PER_MS = 1_000_000L
internal const val OWN_UID = 10_250

/** 2026-11-01 00:00 UTC. */
internal const val START_WALL_MS = 1_793_491_200_000L

internal fun hoursMs(hours: Double): Long = (hours * 3_600_000).toLong()

internal fun hoursNs(hours: Double): Long = hoursMs(hours) * NS_PER_MS

/** Names from a map; a UID missing from it has nothing installed. */
internal class FakeLabels(
    val installed: MutableMap<Int, AppLabel> = mutableMapOf(),
) : AppLabels {
    var lookups = 0
        private set

    override fun label(uid: Int): AppLabel? {
        lookups++
        return installed[uid]
    }
}

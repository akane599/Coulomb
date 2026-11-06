// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import io.github.akane599.coulomb.core.model.stats.BluetoothStat
import io.github.akane599.coulomb.core.model.stats.JobStopCount
import io.github.akane599.coulomb.core.model.stats.JobTimer
import io.github.akane599.coulomb.core.model.stats.NamedCount
import io.github.akane599.coulomb.core.model.stats.NetworkStat
import io.github.akane599.coulomb.core.model.stats.ProcessStat
import io.github.akane599.coulomb.core.model.stats.ProcessStateTime
import io.github.akane599.coulomb.core.model.stats.SensorStat
import io.github.akane599.coulomb.core.model.stats.SyncTimer
import io.github.akane599.coulomb.core.model.stats.TimerStat
import io.github.akane599.coulomb.core.model.stats.UidProcessState
import io.github.akane599.coulomb.core.model.stats.UidStats
import io.github.akane599.coulomb.core.model.stats.WakelockStat
import io.github.akane599.coulomb.core.model.stats.WifiStat

/**
 * Collects one UID's checkin lines into a [UidStats] in the proto parser's shape (ADR-0013).
 *
 * The writer leaves a line out when all its values are zero (`cpu`, `nt`, `wfl`, `st`, `pr`, the
 * dumpTimer lines) or the total is zero (`jb`, `sy`, `sr`, `blem`), so an absent counter is 0 here,
 * as in [ProtoMapper]. A line that is there but short, or has a column that is not a number, gives
 * null for that column: the value is unknown, not zero. Wakelocks without partial time and zero
 * stop-reason counts are dropped, as the proto drops them.
 */
internal class CheckinUid(
    val uid: Int,
    private val stopReasonCodes: IntArray,
) {
    var powerMah: Double? = null
    private val packages = LinkedHashSet<String>()
    private var cpu: Pair<Long?, Long?>? = null
    private val wakelocks = ArrayList<WakelockStat>()
    private var aggregated: Pair<Long?, Long?>? = null
    private val alarms = ArrayList<NamedCount>()
    private val jobs = ArrayList<JobTimer>()
    private val stopCounts = ArrayList<JobStopCount>()
    private val syncs = ArrayList<SyncTimer>()
    private val sensors = ArrayList<SensorStat>()
    private var network: NetworkStat? = null
    private var wifi: WifiStat? = null
    private var bluetooth: BluetoothStat? = null
    private val states = ArrayList<ProcessStateTime>()
    private val timers = HashMap<String, Long?>()
    private val processes = ArrayList<ProcessStat>()

    /** Reads [row] when it is a section this class maps; false otherwise. */
    fun accept(row: CheckinRow): Boolean {
        when (row.type) {
            "cpu" -> cpu(row)
            "wl" -> wakelock(row)
            "awl" -> aggregated(row)
            "wua" -> alarm(row)
            "jb" -> job(row)
            "jbc" -> stopCounts(row)
            "sy" -> sync(row)
            "sr" -> sensor(row)
            "nt" -> network(row)
            "wfl" -> wifi(row)
            "blem" -> bluetooth(row)
            "st" -> states(row)
            "pr" -> process(row)
            "apk" -> apk(row)
            in TIMER_TYPES -> timer(row)
            else -> return false
        }
        return true
    }

    fun addPackages(names: List<String>) {
        packages += names
    }

    fun build(): UidStats =
        UidStats(
            uid = uid,
            packages = packages.toList(),
            powerMah = powerMah,
            cpuUserMs = cpu.let { if (it == null) 0 else it.first },
            cpuSystemMs = cpu.let { if (it == null) 0 else it.second },
            partialWakelocks = wakelocks.toList(),
            aggregatedPartialWakelockMs = aggregated.let { if (it == null) 0 else it.first },
            aggregatedBackgroundPartialWakelockMs = aggregated.let { if (it == null) 0 else it.second },
            wakeupAlarms = alarms.toList(),
            jobs = jobs.toList(),
            jobStopReasons = stopCounts.toList(),
            syncs = syncs.toList(),
            sensors = sensors.toList(),
            network = network ?: ZERO_NETWORK,
            wifi = wifi ?: ZERO_WIFI,
            bluetooth = bluetooth,
            processStates = states.toList(),
            foregroundActivityMs = timer("fg"),
            foregroundServiceMs = timer("fgs"),
            audioMs = timer("aud"),
            videoMs = timer("vid"),
            cameraMs = timer("cam"),
            flashlightMs = timer("fla"),
            vibratorMs = timer("vib"),
            processes = processes.toList(),
        )

    /** Absent line: 0. Line with a bad column: null. */
    private fun timer(type: String): Long? = if (type in timers) timers[type] else 0

    private fun cpu(row: CheckinRow) {
        row.expect(CpuCol.entries.size)
        cpu = row.long(CpuCol.USER) to row.long(CpuCol.SYSTEM)
    }

    private fun wakelock(row: CheckinRow) {
        row.expect(WakelockCol.entries.size)
        val name = row.text(WakelockCol.NAME)
        val total = row.long(WakelockCol.PARTIAL_TOTAL)
        val count = row.long(WakelockCol.PARTIAL_COUNT)
        if (name == null || total == null || count == null) return
        // A missing partial timer prints zeros; either way there is no partial time to show.
        if (total == 0L && count == 0L) return
        val max = row.long(WakelockCol.PARTIAL_MAX)?.takeIf { it >= 0 }
        wakelocks += WakelockStat(name, TimerStat(total, count, max), row.long(WakelockCol.BG_PARTIAL_TOTAL))
    }

    private fun aggregated(row: CheckinRow) {
        row.expect(AggregatedWakelockCol.entries.size)
        aggregated = row.long(AggregatedWakelockCol.PARTIAL) to row.long(AggregatedWakelockCol.BACKGROUND_PARTIAL)
    }

    private fun alarm(row: CheckinRow) {
        row.expect(WakeupAlarmCol.entries.size)
        val name = row.text(WakeupAlarmCol.NAME) ?: return
        alarms += NamedCount(name, row.long(WakeupAlarmCol.COUNT) ?: return)
    }

    private fun job(row: CheckinRow) {
        val (name, total, background) = namedTimer(row) ?: return
        jobs += JobTimer(name, total, background)
    }

    private fun sync(row: CheckinRow) {
        val (name, total, background) = namedTimer(row) ?: return
        syncs += SyncTimer(name, total, background)
    }

    private fun namedTimer(row: CheckinRow): Triple<String, TimerStat, Long?>? {
        row.expect(NamedTimerCol.entries.size)
        val name = row.text(NamedTimerCol.NAME)
        val total = row.long(NamedTimerCol.TOTAL)
        val count = row.long(NamedTimerCol.COUNT)
        if (name == null || total == null || count == null) return null
        // -1: the timer has no background sub-timer.
        return Triple(name, TimerStat(total, count), row.long(NamedTimerCol.BG_DURATION)?.takeIf { it >= 0 })
    }

    private fun stopCounts(row: CheckinRow) {
        row.expect(1 + stopReasonCodes.size)
        val job = row.text(0) ?: return
        stopReasonCodes.forEachIndexed { i, code ->
            val count = row.long(1 + i)
            if (count != null && count != 0L) stopCounts += JobStopCount(job, JobStopReasons.name(code), count)
        }
    }

    private fun sensor(row: CheckinRow) {
        row.expect(SensorCol.entries.size)
        val id = row.long(SensorCol.ID)?.toInt() ?: return
        val total = row.long(SensorCol.TOTAL) ?: return
        val count = row.long(SensorCol.COUNT) ?: return
        sensors += SensorStat(id, TimerStat(total, count), row.long(SensorCol.BG_ACTUAL))
    }

    private fun network(row: CheckinRow) {
        row.expect(NetworkCol.entries.size)
        network =
            NetworkStat(
                mobileRxBytes = row.long(NetworkCol.MOBILE_RX_BYTES),
                mobileTxBytes = row.long(NetworkCol.MOBILE_TX_BYTES),
                wifiRxBytes = row.long(NetworkCol.WIFI_RX_BYTES),
                wifiTxBytes = row.long(NetworkCol.WIFI_TX_BYTES),
                bluetoothRxBytes = row.long(NetworkCol.BT_RX_BYTES),
                bluetoothTxBytes = row.long(NetworkCol.BT_TX_BYTES),
                mobileActiveMs = row.long(NetworkCol.MOBILE_ACTIVE_US)?.usToMs(),
                mobileActiveCount = row.long(NetworkCol.MOBILE_ACTIVE_COUNT),
                mobileWakeups = row.long(NetworkCol.MOBILE_WAKEUPS),
                wifiWakeups = row.long(NetworkCol.WIFI_WAKEUPS),
            )
    }

    private fun wifi(row: CheckinRow) {
        row.expect(WifiCol.entries.size)
        val scanMs = row.long(WifiCol.SCAN_US)?.usToMs()
        val scanCount = row.long(WifiCol.SCAN_COUNT)
        wifi =
            WifiStat(
                runningMs = row.long(WifiCol.RUNNING_US)?.usToMs(),
                fullLockMs = row.long(WifiCol.FULL_LOCK_US)?.usToMs(),
                scan = if (scanMs != null && scanCount != null) TimerStat(scanMs, scanCount) else null,
                backgroundScanMs = row.long(WifiCol.SCAN_ACTUAL_BG),
            )
    }

    private fun bluetooth(row: CheckinRow) {
        row.expect(BluetoothCol.entries.size)
        val total = row.long(BluetoothCol.SCAN_TOTAL)
        val count = row.long(BluetoothCol.SCAN_COUNT)
        bluetooth =
            BluetoothStat(
                bleScan = if (total != null && count != null) TimerStat(total, count) else null,
                backgroundBleScanMs = row.long(BluetoothCol.SCAN_ACTUAL_BG),
                unoptimizedBleScanMs = row.long(BluetoothCol.UNOPTIMIZED_TOTAL),
                bleScanResults = row.long(BluetoothCol.RESULT_COUNT),
            )
    }

    private fun states(row: CheckinRow) {
        row.expect(PROCESS_STATE_COLUMNS)
        for (i in 0 until PROCESS_STATE_COLUMNS) {
            val ms = row.long(i) ?: continue
            if (ms != 0L) states += ProcessStateTime(PROCESS_STATES[i], ms)
        }
    }

    private fun timer(row: CheckinRow) {
        row.expect(TimerCol.entries.size)
        timers[row.type] = row.long(TimerCol.TOTAL)
    }

    private fun process(row: CheckinRow) {
        row.expect(ProcessCol.entries.size)
        val name = row.text(ProcessCol.NAME) ?: return
        processes +=
            ProcessStat(
                name = name,
                userMs = row.long(ProcessCol.USER),
                systemMs = row.long(ProcessCol.SYSTEM),
                foregroundMs = row.long(ProcessCol.FOREGROUND),
                starts = row.long(ProcessCol.STARTS),
                anrs = row.long(ProcessCol.ANRS),
                crashes = row.long(ProcessCol.CRASHES),
            )
    }

    private fun apk(row: CheckinRow) {
        row.expect(ApkCol.entries.size)
        row.text(ApkCol.PACKAGE)?.let { packages += it }
    }

    private companion object {
        val TIMER_TYPES = setOf("fg", "fgs", "aud", "vid", "cam", "fla", "vib")

        /** `Uid.PROCESS_STATE_*` 0 to 6 (BatteryStats.java android-17.0.0_r1 L904-940). */
        val PROCESS_STATES =
            listOf(
                UidProcessState.TOP,
                UidProcessState.FOREGROUND_SERVICE,
                UidProcessState.FOREGROUND,
                UidProcessState.BACKGROUND,
                UidProcessState.TOP_SLEEPING,
                UidProcessState.HEAVY_WEIGHT,
                UidProcessState.CACHED,
            )

        val ZERO_NETWORK = NetworkStat(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val ZERO_WIFI = WifiStat(runningMs = 0, fullLockMs = 0, scan = TimerStat(0, 0), backgroundScanMs = 0)
    }
}

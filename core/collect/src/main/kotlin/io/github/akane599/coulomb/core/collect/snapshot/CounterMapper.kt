// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.database.entity.AlarmStatEntity
import io.github.akane599.coulomb.core.database.entity.DeviceStatEntity
import io.github.akane599.coulomb.core.database.entity.JobStatEntity
import io.github.akane599.coulomb.core.database.entity.NetStatEntity
import io.github.akane599.coulomb.core.database.entity.PowerColumns
import io.github.akane599.coulomb.core.database.entity.UidComponentPowerEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.UidStatEntity
import io.github.akane599.coulomb.core.database.entity.WakeStatEntity
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.stats.BatteryStatsDump
import io.github.akane599.coulomb.core.model.stats.ComponentPower
import io.github.akane599.coulomb.core.model.stats.DeviceKeys
import io.github.akane599.coulomb.core.model.stats.DeviceStats
import io.github.akane599.coulomb.core.model.stats.NamedTimer
import io.github.akane599.coulomb.core.model.stats.SensorStat
import io.github.akane599.coulomb.core.model.stats.SipperKind
import io.github.akane599.coulomb.core.model.stats.UidKeys
import io.github.akane599.coulomb.core.model.stats.UidStats
import io.github.akane599.coulomb.core.model.stats.UsageStatsDump
import kotlin.math.roundToLong

/**
 * S5 (or S6) counters as snapshot rows (ADR-0018). Every value is cumulative since the platform's
 * statistics started. A value the dump did not report has no row, and neither does 0: a missing
 * key and 0 give the same window change, and leaving zeros out keeps the rows small.
 */
internal object CounterMapper {
    /** `uid_power.component` of a UID's whole modeled total. */
    const val ALL_COMPONENTS: String = "all"

    private const val UAH_PER_MAH = 1_000.0

    fun rows(dump: BatteryStatsDump): SnapshotRows {
        val uidPower = ArrayList<UidPowerEntity>()
        val wakes = ArrayList<WakeStatEntity>()
        val alarms = ArrayList<AlarmStatEntity>()
        val jobs = ArrayList<JobStatEntity>()
        val nets = ArrayList<NetStatEntity>()
        val uidStats = ArrayList<UidStatEntity>()
        for (uid in dump.uids) {
            uid.powerMah?.takeIf { it > 0 }?.let {
                uidPower +=
                    UidPowerEntity(0, uid.uid, ALL_COMPONENTS, UidPowerEntity.ANY_STATE, it, Provenance.MODELED)
            }
            wakes += partialWakelocks(uid)
            alarmRow(uid)?.let { alarms += it }
            jobRow(uid)?.let { jobs += it }
            netRow(uid)?.let { nets += it }
            uidStats += uidValues(uid).map { (key, value) -> UidStatEntity(0, uid.uid, key, value) }
        }
        dump.device?.let { device ->
            uidPower += devicePower(device)
            wakes += deviceTimers(device.kernelWakelocks, WakeKind.KERNEL)
            wakes += deviceTimers(device.wakeupReasons, WakeKind.WAKEUP_REASON)
        }
        val deviceStats =
            dump.device
                ?.let(::deviceValues)
                .orEmpty()
                .map { (key, value) -> DeviceStatEntity(0, key, value) }
        return SnapshotRows(
            uidPower = uidPower,
            wakeStats = wakes,
            alarmStats = alarms,
            jobStats = jobs,
            netStats = nets,
            uidStats = uidStats,
            deviceStats = deviceStats,
        )
    }

    /**
     * S4's cumulative columns as `uid_component_power` rows (ADR-0035 §6): one per consumer, the
     * device under [UidPowerEntity.DEVICE_UID]. A consumer with nothing in any column has no row,
     * as a zero counter has none above. Only for an S4 read that is `ok`.
     */
    fun componentRows(dump: UsageStatsDump): List<UidComponentPowerEntity> {
        val consumers = dump.uids + listOfNotNull(dump.device?.let { UidPowerEntity.DEVICE_UID to it })
        return consumers
            .filter { (_, power) -> power != ComponentPower.ZERO }
            .map { (uid, power) -> UidComponentPowerEntity(0, uid, PowerColumns.of(power)) }
    }

    /** UID to its packages, for `uid_package` (ADR-0015: UIDs are reused after uninstall). */
    fun packages(dump: BatteryStatsDump): Map<Int, List<String>> =
        dump.uids.filter { it.packages.isNotEmpty() }.associate {
            it.uid to
                it.packages
        }

    /**
     * One row per tag: a tag the platform lists twice is summed, so a window diff sees one counter.
     * The times are actual times held (ADR-0039), which can overlap, so such a sum is an upper bound.
     */
    private fun partialWakelocks(uid: UidStats): List<WakeStatEntity> =
        uid.partialWakelocks
            .filter { it.partial.totalMs > 0 || it.partial.count > 0 }
            .groupBy { it.tag }
            .map { (tag, stats) ->
                WakeStatEntity(
                    snapshotId = 0,
                    uid = uid.uid,
                    tag = tag,
                    kind = WakeKind.PARTIAL,
                    totalMs = stats.sumOf { it.partial.totalMs },
                    count = stats.sumOf { it.partial.count }.toIntClamped(),
                    maxMs = stats.mapNotNull { it.partial.maxMs }.maxOrNull(),
                    screenOffMs = null,
                )
            }

    private fun deviceTimers(
        timers: List<NamedTimer>,
        kind: WakeKind,
    ): List<WakeStatEntity> =
        timers
            .filter { it.timer.totalMs > 0 || it.timer.count > 0 }
            .groupBy { it.name }
            .map { (name, all) ->
                WakeStatEntity(
                    snapshotId = 0,
                    uid = null,
                    tag = name,
                    kind = kind,
                    totalMs = all.sumOf { it.timer.totalMs },
                    count = all.sumOf { it.timer.count }.toIntClamped(),
                    maxMs = all.mapNotNull { it.timer.maxMs }.maxOrNull(),
                    screenOffMs = null,
                )
            }

    /**
     * The power model's device total per component, apps included: the system-level power items
     * are the device-scope aggregate consumer (`AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE`), one per
     * component below `POWER_COMPONENT_COUNT` (BatteryStats android-17.0.0_r1 L8733-8782 for the
     * proto and L4529-4540 for checkin; android-16.0.0_r4 L8701-8750 and L4508-4519). Both writers
     * give them uid 0, so they are told apart from the per-UID totals by where they are printed,
     * not by uid. They are never added to the UID rows. Components the proto has no name for share
     * `UNKNOWN_SIPPER` and are summed into one row. Their sum is the model's total; the summary's
     * `computed_power_mah` is the measured discharge instead ([DeviceKeys.STATS_DISCHARGE_UAH]).
     */
    private fun devicePower(device: DeviceStats): List<UidPowerEntity> =
        device.powerUse
            .filter { it.kind != SipperKind.USER && it.mah > 0 }
            .groupBy { it.kind.name }
            .map { (component, sippers) ->
                UidPowerEntity(
                    0,
                    UidPowerEntity.DEVICE_UID,
                    component,
                    UidPowerEntity.ANY_STATE,
                    sippers.sumOf { it.mah },
                    Provenance.MODELED,
                )
            }

    private fun alarmRow(uid: UidStats): AlarmStatEntity? {
        if (uid.wakeupAlarms.isEmpty()) return null
        val wakeups = uid.wakeupAlarms.sumOf { it.count }
        // S5 reports wakeup alarms only; the total of all alarms needs S10.
        return AlarmStatEntity(0, uid.uid, wakeups.toIntClamped(), total = null).takeIf { wakeups > 0 }
    }

    private fun jobRow(uid: UidStats): JobStatEntity? {
        if (uid.jobs.isEmpty()) return null
        val count = uid.jobs.sumOf { it.total.count }
        val runtime = uid.jobs.sumOf { it.total.totalMs }
        if (count == 0L && runtime == 0L) return null
        val topReason =
            uid.jobStopReasons
                .groupBy { it.reason }
                .maxByOrNull { (_, counts) -> counts.sumOf { it.count } }
                ?.key
        return JobStatEntity(0, uid.uid, count.toIntClamped(), runtime, abandoned = null, topStopReason = topReason)
    }

    private fun netRow(uid: UidStats): NetStatEntity? {
        val net = uid.network ?: return null
        val row = NetStatEntity(0, uid.uid, net.mobileRxBytes, net.mobileTxBytes, net.wifiRxBytes, net.wifiTxBytes, net.mobileActiveMs)
        val values = listOf(row.mobileRxB, row.mobileTxB, row.wifiRxB, row.wifiTxB, row.radioActiveMs)
        return row.takeIf { values.any { (it ?: 0) > 0 } }
    }

    /** `uid_stat` keys ([UidKeys]); only reported, non-zero values. */
    private fun uidValues(uid: UidStats): Map<String, Long> {
        val values = LinkedHashMap<String, Long?>()
        values[UidKeys.CPU_USER_MS] = uid.cpuUserMs
        values[UidKeys.CPU_SYSTEM_MS] = uid.cpuSystemMs
        values[UidKeys.PARTIAL_WAKELOCK_MS] = uid.aggregatedPartialWakelockMs
        values[UidKeys.BACKGROUND_PARTIAL_WAKELOCK_MS] = uid.aggregatedBackgroundPartialWakelockMs
        values[UidKeys.FOREGROUND_ACTIVITY_MS] = uid.foregroundActivityMs
        values[UidKeys.FOREGROUND_SERVICE_MS] = uid.foregroundServiceMs
        values[UidKeys.AUDIO_MS] = uid.audioMs
        values[UidKeys.VIDEO_MS] = uid.videoMs
        values[UidKeys.CAMERA_MS] = uid.cameraMs
        values[UidKeys.FLASHLIGHT_MS] = uid.flashlightMs
        values[UidKeys.VIBRATOR_MS] = uid.vibratorMs
        values[UidKeys.SYNC_MS] = uid.syncs.takeIf { it.isNotEmpty() }?.sumOf { it.total.totalMs }
        values[UidKeys.GNSS_MS] =
            uid.sensors
                .filter { it.id == SensorStat.GPS_SENSOR_ID }
                .takeIf { it.isNotEmpty() }
                ?.sumOf { it.total.totalMs }
        values[UidKeys.SENSOR_MS] =
            uid.sensors
                .filter { it.id != SensorStat.GPS_SENSOR_ID }
                .takeIf { it.isNotEmpty() }
                ?.sumOf { it.total.totalMs }
        values[UidKeys.BACKGROUND_SENSOR_MS] =
            uid.sensors
                .mapNotNull { s -> s.backgroundMs.takeIf { s.id != SensorStat.GPS_SENSOR_ID } }
                .takeIf { it.isNotEmpty() }
                ?.sum()
        values[UidKeys.WIFI_RUNNING_MS] = uid.wifi?.runningMs
        values[UidKeys.WIFI_SCAN_MS] = uid.wifi?.scan?.totalMs
        values[UidKeys.WIFI_SCAN_COUNT] = uid.wifi?.scan?.count
        values[UidKeys.BLE_SCAN_MS] = uid.bluetooth?.bleScan?.totalMs
        values[UidKeys.BACKGROUND_BLE_SCAN_MS] = uid.bluetooth?.backgroundBleScanMs
        values[UidKeys.UNOPTIMIZED_BLE_SCAN_MS] = uid.bluetooth?.unoptimizedBleScanMs
        values[UidKeys.MOBILE_WAKEUPS] = uid.network?.mobileWakeups
        values[UidKeys.WIFI_WAKEUPS] = uid.network?.wifiWakeups
        values[UidKeys.MOBILE_ACTIVE_COUNT] = uid.network?.mobileActiveCount
        for (state in uid.processStates) values[UidKeys.processState(state.state)] = state.ms
        return values.filterValues { it != null && it > 0 }.mapValues { checkNotNull(it.value) }
    }

    /** `device_stat` keys ([DeviceKeys]); only reported, non-zero values. */
    private fun deviceValues(device: DeviceStats): Map<String, Long> {
        val values = LinkedHashMap<String, Long?>()
        values[DeviceKeys.START_COUNT] = device.startCount
        values[DeviceKeys.TOTAL_REALTIME_MS] = device.totalRealtimeMs
        values[DeviceKeys.TOTAL_UPTIME_MS] = device.totalUptimeMs
        values[DeviceKeys.BATTERY_REALTIME_MS] = device.batteryRealtimeMs
        values[DeviceKeys.BATTERY_UPTIME_MS] = device.batteryUptimeMs
        values[DeviceKeys.SCREEN_OFF_REALTIME_MS] = device.screenOffRealtimeMs
        values[DeviceKeys.SCREEN_OFF_UPTIME_MS] = device.screenOffUptimeMs
        values[DeviceKeys.SCREEN_DOZE_MS] = device.screenDozeMs
        values[DeviceKeys.SIGNAL_SCANNING_MS] = device.signalScanningMs
        device.discharge?.let { d ->
            values[DeviceKeys.DISCHARGE_MAH] = d.totalMah
            values[DeviceKeys.DISCHARGE_SCREEN_OFF_MAH] = d.screenOffMah
            values[DeviceKeys.DISCHARGE_SCREEN_DOZE_MAH] = d.screenDozeMah
            values[DeviceKeys.DISCHARGE_LIGHT_DOZE_MAH] = d.lightDozeMah
            values[DeviceKeys.DISCHARGE_DEEP_DOZE_MAH] = d.deepDozeMah
        }
        device.misc?.let { m ->
            values[DeviceKeys.SCREEN_ON_MS] = m.screenOnMs
            values[DeviceKeys.INTERACTIVE_MS] = m.interactiveMs
            values[DeviceKeys.PHONE_ON_MS] = m.phoneOnMs
            values[DeviceKeys.PARTIAL_WAKELOCK_MS] = m.partialWakelockTotalMs
            values[DeviceKeys.MOBILE_RADIO_ACTIVE_MS] = m.mobileRadioActiveMs
            values[DeviceKeys.MOBILE_RADIO_ACTIVE_COUNT] = m.mobileRadioActiveCount
            values[DeviceKeys.BATTERY_SAVER_MS] = m.batterySaverMs
            values[DeviceKeys.DEEP_DOZE_MS] = m.deepDozeIdlingMs
            values[DeviceKeys.DEEP_DOZE_COUNT] = m.deepDozeIdlingCount
            values[DeviceKeys.LIGHT_DOZE_MS] = m.lightDozeIdlingMs
            values[DeviceKeys.LIGHT_DOZE_COUNT] = m.lightDozeIdlingCount
        }
        for (bin in device.phoneSignalStrength) values[DeviceKeys.signalBin(bin.bin)] = bin.totalMs
        for (bin in device.screenBrightness) values[DeviceKeys.brightnessBin(bin.bin)] = bin.totalMs
        device.powerSummary?.computedMah?.let { values[DeviceKeys.STATS_DISCHARGE_UAH] = (it * UAH_PER_MAH).roundToLong() }
        return values.filterValues { it != null && it > 0 }.mapValues { checkNotNull(it.value) }
    }

    private fun Long.toIntClamped(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

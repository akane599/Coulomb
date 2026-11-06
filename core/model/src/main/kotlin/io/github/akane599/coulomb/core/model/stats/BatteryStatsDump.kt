// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.stats

/**
 * One `batterystats` dump, from `--proto` (S5) or `-c` (S6), in the same shape (ADR-0013).
 *
 * Every counter is cumulative since the stats start ([DeviceStats.startClockTimeWallMs]), exactly as
 * the system reports it; windows are differences of two dumps (SPEC 6.5). A field the source does
 * not report is null, never 0.
 */
public data class BatteryStatsDump(
    val header: StatsHeader?,
    /** Device-wide totals. Null when the dump ended before them (they come last in the proto). */
    val device: DeviceStats?,
    val uids: List<UidStats>,
    /** False when the dump ended inside the UID list, so per-UID sums are partial. */
    val uidsComplete: Boolean,
)

public data class StatsHeader(
    /** `BatteryStats.CHECKIN_VERSION`. */
    val reportVersion: Int,
    /** `BatteryStatsImpl` parcel version; OEM builds use their own numbers. */
    val parcelVersion: Long,
    val startPlatformVersion: String?,
    val endPlatformVersion: String?,
)

/** A timer as the system keeps it: total time, number of times it started, longest single run. */
public data class TimerStat(
    val totalMs: Long,
    val count: Long,
    val maxMs: Long? = null,
)

public data class NamedTimer(
    val name: String,
    val timer: TimerStat,
)

public data class NamedCount(
    val name: String,
    val count: Long,
)

/**
 * Time spent in one bin (signal strength, screen brightness). [bin] is the platform's bin index.
 * [count] is how often the bin was entered; null when the source does not report it (checkin
 * screen brightness).
 */
public data class BinTime(
    val bin: Int,
    val totalMs: Long,
    val count: Long?,
)

public data class DeviceStats(
    val startClockTimeWallMs: Long? = null,
    val startCount: Long? = null,
    val totalRealtimeMs: Long? = null,
    val totalUptimeMs: Long? = null,
    val batteryRealtimeMs: Long? = null,
    val batteryUptimeMs: Long? = null,
    val screenOffRealtimeMs: Long? = null,
    val screenOffUptimeMs: Long? = null,
    val screenDozeMs: Long? = null,
    val estimatedCapacityMah: Long? = null,
    val minLearnedCapacityUah: Long? = null,
    val maxLearnedCapacityUah: Long? = null,
    val discharge: Discharge? = null,
    val misc: DeviceMisc? = null,
    val kernelWakelocks: List<NamedTimer> = emptyList(),
    val wakeupReasons: List<NamedTimer> = emptyList(),
    /** `SignalStrengthEnum` bins 0 (none or unknown) to 4 (great). */
    val phoneSignalStrength: List<BinTime> = emptyList(),
    /** `ScreenBrightness` bins 0 (dark) to 4 (bright). */
    val screenBrightness: List<BinTime> = emptyList(),
    /** Time the modem spent scanning for a signal. */
    val signalScanningMs: Long? = null,
    val powerUse: List<SipperPower> = emptyList(),
    val powerSummary: PowerSummary? = null,
)

public data class Discharge(
    val lowerBoundSinceChargePct: Int? = null,
    val upperBoundSinceChargePct: Int? = null,
    val screenOnSinceChargePct: Int? = null,
    val screenOffSinceChargePct: Int? = null,
    val screenDozeSinceChargePct: Int? = null,
    val totalMah: Long? = null,
    val screenOffMah: Long? = null,
    val screenDozeMah: Long? = null,
    val lightDozeMah: Long? = null,
    val deepDozeMah: Long? = null,
)

public data class DeviceMisc(
    val screenOnMs: Long? = null,
    val phoneOnMs: Long? = null,
    val partialWakelockTotalMs: Long? = null,
    val mobileRadioActiveMs: Long? = null,
    val mobileRadioActiveCount: Long? = null,
    val interactiveMs: Long? = null,
    val batterySaverMs: Long? = null,
    val deepDozeEnabledMs: Long? = null,
    val deepDozeCount: Long? = null,
    val deepDozeIdlingMs: Long? = null,
    val deepDozeIdlingCount: Long? = null,
    val longestDeepDozeMs: Long? = null,
    val lightDozeEnabledMs: Long? = null,
    val lightDozeCount: Long? = null,
    val lightDozeIdlingMs: Long? = null,
    val lightDozeIdlingCount: Long? = null,
    val longestLightDozeMs: Long? = null,
)

/**
 * One device-wide entry of the platform power model, per power component (the legacy "sippers").
 * Each app's own total is [UidStats.powerMah].
 */
public data class SipperPower(
    val kind: SipperKind,
    val uid: Int?,
    val mah: Double,
    val screenMah: Double? = null,
    val smearMah: Double? = null,
    val hidden: Boolean = false,
)

public enum class SipperKind {
    UNKNOWN,
    IDLE,
    CELL,
    PHONE,
    WIFI,
    BLUETOOTH,
    FLASHLIGHT,
    SCREEN,
    USER,
    UNACCOUNTED,
    OVERCOUNTED,
    CAMERA,
    MEMORY,
    AMBIENT_DISPLAY,
}

public data class PowerSummary(
    val capacityMah: Double? = null,
    val computedMah: Double? = null,
    val minDrainedMah: Double? = null,
    val maxDrainedMah: Double? = null,
)

/** Process states as `BatteryStats` buckets them. */
public enum class UidProcessState {
    TOP,
    FOREGROUND_SERVICE,
    FOREGROUND,
    BACKGROUND,
    TOP_SLEEPING,
    HEAVY_WEIGHT,
    CACHED,
    UNKNOWN,
}

public data class UidStats(
    val uid: Int,
    val packages: List<String> = emptyList(),
    /** Platform power model total for this UID (Modeled, SPEC 6.7). */
    val powerMah: Double? = null,
    val cpuUserMs: Long? = null,
    val cpuSystemMs: Long? = null,
    val partialWakelocks: List<WakelockStat> = emptyList(),
    /** Partial wakelock time with overlaps between tags removed. */
    val aggregatedPartialWakelockMs: Long? = null,
    val aggregatedBackgroundPartialWakelockMs: Long? = null,
    val wakeupAlarms: List<NamedCount> = emptyList(),
    val jobs: List<JobTimer> = emptyList(),
    val jobStopReasons: List<JobStopCount> = emptyList(),
    val syncs: List<SyncTimer> = emptyList(),
    val sensors: List<SensorStat> = emptyList(),
    val network: NetworkStat? = null,
    val wifi: WifiStat? = null,
    val bluetooth: BluetoothStat? = null,
    val processStates: List<ProcessStateTime> = emptyList(),
    val foregroundActivityMs: Long? = null,
    val foregroundServiceMs: Long? = null,
    val audioMs: Long? = null,
    val videoMs: Long? = null,
    val cameraMs: Long? = null,
    val flashlightMs: Long? = null,
    val vibratorMs: Long? = null,
    val processes: List<ProcessStat> = emptyList(),
)

public data class WakelockStat(
    val tag: String,
    val partial: TimerStat,
    val backgroundPartialMs: Long? = null,
)

public data class JobTimer(
    val name: String,
    val total: TimerStat,
    val backgroundMs: Long? = null,
)

/** How often a job stopped for one platform reason (`InternalStopReasonEnum` name). */
public data class JobStopCount(
    val job: String,
    val reason: String,
    val count: Long,
)

public data class SyncTimer(
    val name: String,
    val total: TimerStat,
    val backgroundMs: Long? = null,
)

/** A sensor held by a UID; [id] [GPS_SENSOR_ID] is GNSS. */
public data class SensorStat(
    val id: Int,
    val total: TimerStat,
    val backgroundMs: Long? = null,
) {
    public companion object {
        /** `BatteryStats.Uid.Sensor.GPS`. */
        public const val GPS_SENSOR_ID: Int = -10000
    }
}

public data class NetworkStat(
    val mobileRxBytes: Long? = null,
    val mobileTxBytes: Long? = null,
    val wifiRxBytes: Long? = null,
    val wifiTxBytes: Long? = null,
    val bluetoothRxBytes: Long? = null,
    val bluetoothTxBytes: Long? = null,
    val mobileActiveMs: Long? = null,
    val mobileActiveCount: Long? = null,
    val mobileWakeups: Long? = null,
    val wifiWakeups: Long? = null,
)

public data class WifiStat(
    val runningMs: Long? = null,
    val fullLockMs: Long? = null,
    val scan: TimerStat? = null,
    val backgroundScanMs: Long? = null,
)

public data class BluetoothStat(
    val bleScan: TimerStat? = null,
    val backgroundBleScanMs: Long? = null,
    val unoptimizedBleScanMs: Long? = null,
    val bleScanResults: Long? = null,
)

public data class ProcessStateTime(
    val state: UidProcessState,
    val ms: Long,
)

public data class ProcessStat(
    val name: String,
    val userMs: Long? = null,
    val systemMs: Long? = null,
    val foregroundMs: Long? = null,
    val starts: Long? = null,
    val anrs: Long? = null,
    val crashes: Long? = null,
)

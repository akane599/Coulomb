// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import io.github.akane599.coulomb.core.model.stats.BatteryStatsDump
import io.github.akane599.coulomb.core.model.stats.DeviceStats
import io.github.akane599.coulomb.core.model.stats.NamedTimer
import io.github.akane599.coulomb.core.model.stats.NetworkStat
import io.github.akane599.coulomb.core.model.stats.TimerStat
import io.github.akane599.coulomb.core.model.stats.UidStats

/** The stats clock of one dump: when the platform's counters started and how long they have run. */
internal data class StatsTimes(
    val startWallMs: Long,
    val startCount: Long,
    val realtimeMs: Long,
    val batteryRealtimeMs: Long,
)

/**
 * Since-reset S5 counters grown from one real dump, the phone fixture's (SPEC 16.3, ADR-0019).
 *
 * Each UID, partial wakelock and kernel timer grows only in the 10-minute slots it is active in,
 * at the rate that brings it to the fixture's value over the fixture's span on average. Within an
 * active slot, a UID's CPU time always grows and each other group of its counters (jobs, network,
 * process states and so on) only sometimes. So a window between two heavy snapshots sees the few
 * that ran, and only some of their counters, as on the phone (about 18 UID, 3 wakelock and 110
 * counter rows per window at the 2026-11-07 checkpoint). Device-wide counters grow evenly. Every
 * counter only grows until [reset].
 */
internal class SyntheticCounters(
    private val template: BatteryStatsDump,
) {
    private val device = checkNotNull(template.device) { "the fixture dump has no device stats" }
    private val templateMs = checkNotNull(device.totalRealtimeMs).toDouble()

    /** The UIDs that run all the time, as the system and the launcher do. */
    private val busy =
        template.uids.indices
            .sortedByDescending { template.uids[it].powerMah ?: 0.0 }
            .take(BUSY_UIDS)
            .toSet()
    private val uidChance = DoubleArray(template.uids.size) { if (it in busy) 1.0 else UID_CHANCE }
    private val uidGrowth = Array(template.uids.size) { DoubleArray(GROUPS) }
    private val wakeGrowth = Array(template.uids.size) { DoubleArray(template.uids[it].partialWakelocks.size) }
    private val kernelGrowth = DoubleArray(device.kernelWakelocks.size)
    private val reasonGrowth = DoubleArray(device.wakeupReasons.size)
    private var accruedMs = 0L
    private var epoch = 0L

    /** A stats reset: every counter starts again from 0. */
    fun reset() {
        uidGrowth.forEach { it.fill(0.0) }
        wakeGrowth.forEach { it.fill(0.0) }
        kernelGrowth.fill(0.0)
        reasonGrowth.fill(0.0)
        accruedMs = 0
        epoch++
    }

    /** The dump at [times]; the stats clock never goes back between two resets. */
    fun dump(times: StatsTimes): BatteryStatsDump {
        accrueTo(times.realtimeMs)
        val even = times.realtimeMs / templateMs
        val uids =
            template.uids.mapIndexed { i, uid -> uid.grown(uidGrowth[i], wakeGrowth[i]) }
        return template.copy(device = device.grown(times, even), uids = uids)
    }

    private fun accrueTo(realtimeMs: Long) {
        var t = accruedMs
        while (t < realtimeMs) {
            val slot = t / SLOT_MS
            val end = minOf(realtimeMs, (slot + 1) * SLOT_MS)
            val share = (end - t) / templateMs
            for (i in uidGrowth.indices) {
                if (active(i.toLong(), slot, uidChance[i])) {
                    val groups = uidGrowth[i]
                    groups[CPU] += share / uidChance[i]
                    for (g in 1 until GROUPS) {
                        if (active(GROUP_ID + i * GROUPS + g, slot, GROUP_CHANCE)) groups[g] += share / uidChance[i] / GROUP_CHANCE
                    }
                }
                val wakes = wakeGrowth[i]
                for (j in wakes.indices) {
                    if (active(WAKE_ID + i * WAKES_PER_UID + j, slot, TIMER_CHANCE)) wakes[j] += share / TIMER_CHANCE
                }
            }
            for (k in kernelGrowth.indices) {
                if (active(KERNEL_ID + k, slot, TIMER_CHANCE)) kernelGrowth[k] += share / TIMER_CHANCE
            }
            for (k in reasonGrowth.indices) {
                if (active(REASON_ID + k, slot, TIMER_CHANCE)) reasonGrowth[k] += share / TIMER_CHANCE
            }
            t = end
        }
        accruedMs = maxOf(accruedMs, realtimeMs)
    }

    /** Whether an entity runs in a slot: a fixed hash, so a rerun sees the same sequence. */
    private fun active(
        id: Long,
        slot: Long,
        chance: Double,
    ): Boolean {
        if (chance >= 1.0) return true
        var x = id * GOLDEN + slot * LCG + epoch * MIX_A
        x = (x xor (x ushr SHIFT)) * MIX_A
        x = (x xor (x ushr SHIFT)) * MIX_B
        x = x xor (x ushr SHIFT)
        return (x ushr EXPONENT_BITS) * UNIT < chance
    }

    private fun DeviceStats.grown(
        times: StatsTimes,
        g: Double,
    ) = copy(
        startClockTimeWallMs = times.startWallMs,
        startCount = times.startCount,
        totalRealtimeMs = times.realtimeMs,
        totalUptimeMs = (times.realtimeMs * AWAKE).toLong(),
        batteryRealtimeMs = times.batteryRealtimeMs,
        batteryUptimeMs = (times.batteryRealtimeMs * AWAKE).toLong(),
        screenOffRealtimeMs = screenOffRealtimeMs.grown(g),
        screenOffUptimeMs = screenOffUptimeMs.grown(g),
        screenDozeMs = screenDozeMs.grown(g),
        discharge =
            discharge?.let {
                it.copy(
                    totalMah = it.totalMah.grown(g),
                    screenOffMah = it.screenOffMah.grown(g),
                    screenDozeMah = it.screenDozeMah.grown(g),
                    lightDozeMah = it.lightDozeMah.grown(g),
                    deepDozeMah = it.deepDozeMah.grown(g),
                )
            },
        misc =
            misc?.let {
                it.copy(
                    screenOnMs = it.screenOnMs.grown(g),
                    phoneOnMs = it.phoneOnMs.grown(g),
                    partialWakelockTotalMs = it.partialWakelockTotalMs.grown(g),
                    mobileRadioActiveMs = it.mobileRadioActiveMs.grown(g),
                    mobileRadioActiveCount = it.mobileRadioActiveCount.grown(g),
                    interactiveMs = it.interactiveMs.grown(g),
                    batterySaverMs = it.batterySaverMs.grown(g),
                    deepDozeIdlingMs = it.deepDozeIdlingMs.grown(g),
                    deepDozeIdlingCount = it.deepDozeIdlingCount.grown(g),
                    lightDozeIdlingMs = it.lightDozeIdlingMs.grown(g),
                    lightDozeIdlingCount = it.lightDozeIdlingCount.grown(g),
                )
            },
        kernelWakelocks = kernelWakelocks.mapIndexed { k, t -> t.grown(kernelGrowth[k]) },
        wakeupReasons = wakeupReasons.mapIndexed { k, t -> t.grown(reasonGrowth[k]) },
        phoneSignalStrength = phoneSignalStrength.map { it.copy(totalMs = (it.totalMs * g).toLong()) },
        screenBrightness = screenBrightness.map { it.copy(totalMs = (it.totalMs * g).toLong()) },
        signalScanningMs = signalScanningMs.grown(g),
        powerUse = powerUse.map { it.copy(mah = it.mah * g) },
        powerSummary = powerSummary?.let { it.copy(computedMah = it.computedMah?.times(g)) },
    )

    /** The counters CounterMapper reads, grown by group; the rest stay as in the fixture. */
    @Suppress("LongMethod")
    private fun UidStats.grown(
        g: DoubleArray,
        wakes: DoubleArray,
    ) = copy(
        powerMah = powerMah?.times(g[CPU]),
        cpuUserMs = cpuUserMs.grown(g[CPU]),
        cpuSystemMs = cpuSystemMs.grown(g[CPU]),
        partialWakelocks =
            partialWakelocks.mapIndexed { j, w ->
                w.copy(partial = w.partial.grown(wakes[j]), backgroundPartialMs = w.backgroundPartialMs.grown(wakes[j]))
            },
        aggregatedPartialWakelockMs = aggregatedPartialWakelockMs.grown(g[WAKELOCKS]),
        aggregatedBackgroundPartialWakelockMs = aggregatedBackgroundPartialWakelockMs.grown(g[WAKELOCKS]),
        wakeupAlarms = wakeupAlarms.map { it.copy(count = (it.count * g[ALARMS]).toLong()) },
        jobs = jobs.map { it.copy(total = it.total.grown(g[JOBS]), backgroundMs = it.backgroundMs.grown(g[JOBS])) },
        jobStopReasons = jobStopReasons.map { it.copy(count = (it.count * g[JOBS]).toLong()) },
        syncs = syncs.map { it.copy(total = it.total.grown(g[SYNCS]), backgroundMs = it.backgroundMs.grown(g[SYNCS])) },
        sensors = sensors.map { it.copy(total = it.total.grown(g[SENSORS]), backgroundMs = it.backgroundMs.grown(g[SENSORS])) },
        network = network?.grown(g[NETWORK]),
        wifi =
            wifi?.let {
                val w = g[WIFI]
                it.copy(
                    runningMs = it.runningMs.grown(w),
                    fullLockMs = it.fullLockMs.grown(w),
                    scan = it.scan?.grown(w),
                    backgroundScanMs = it.backgroundScanMs.grown(w),
                )
            },
        bluetooth =
            bluetooth?.let {
                val b = g[BLUETOOTH]
                it.copy(
                    bleScan = it.bleScan?.grown(b),
                    backgroundBleScanMs = it.backgroundBleScanMs.grown(b),
                    unoptimizedBleScanMs = it.unoptimizedBleScanMs.grown(b),
                    bleScanResults = it.bleScanResults.grown(b),
                )
            },
        processStates = processStates.map { it.copy(ms = (it.ms * g[PROCESS]).toLong()) },
        foregroundActivityMs = foregroundActivityMs.grown(g[FOREGROUND]),
        foregroundServiceMs = foregroundServiceMs.grown(g[FOREGROUND]),
        audioMs = audioMs.grown(g[MEDIA]),
        videoMs = videoMs.grown(g[MEDIA]),
        cameraMs = cameraMs.grown(g[MEDIA]),
        flashlightMs = flashlightMs.grown(g[MEDIA]),
        vibratorMs = vibratorMs.grown(g[MEDIA]),
    )

    private fun NetworkStat.grown(g: Double) =
        copy(
            mobileRxBytes = mobileRxBytes.grown(g),
            mobileTxBytes = mobileTxBytes.grown(g),
            wifiRxBytes = wifiRxBytes.grown(g),
            wifiTxBytes = wifiTxBytes.grown(g),
            bluetoothRxBytes = bluetoothRxBytes.grown(g),
            bluetoothTxBytes = bluetoothTxBytes.grown(g),
            mobileActiveMs = mobileActiveMs.grown(g),
            mobileActiveCount = mobileActiveCount.grown(g),
            mobileWakeups = mobileWakeups.grown(g),
            wifiWakeups = wifiWakeups.grown(g),
        )

    private fun Long?.grown(g: Double): Long? = this?.let { (it * g).toLong() }

    private fun TimerStat.grown(g: Double) = copy(totalMs = (totalMs * g).toLong(), count = (count * g).toLong())

    private fun NamedTimer.grown(g: Double) = copy(timer = timer.grown(g))

    private companion object {
        const val SLOT_MS = 10 * 60_000L
        const val BUSY_UIDS = 8
        const val UID_CHANCE = 0.15
        const val TIMER_CHANCE = 0.02

        /** The chance that one of an active UID's other counter groups changes in a slot. */
        const val GROUP_CHANCE = 0.4

        // A UID's counter groups; CPU time grows whenever the UID is active.
        const val CPU = 0
        const val WAKELOCKS = 1
        const val ALARMS = 2
        const val JOBS = 3
        const val SYNCS = 4
        const val SENSORS = 5
        const val NETWORK = 6
        const val WIFI = 7
        const val BLUETOOTH = 8
        const val PROCESS = 9
        const val FOREGROUND = 10
        const val MEDIA = 11
        const val GROUPS = 12

        /** The share of the stats clock the CPU is awake: the phone fixture's ratio, rounded. */
        const val AWAKE = 0.7

        const val WAKE_ID = 1L shl 40
        const val KERNEL_ID = 2L shl 40
        const val REASON_ID = 3L shl 40
        const val GROUP_ID = 4L shl 40
        const val WAKES_PER_UID = 1L shl 20

        // splitmix64 and murmur3 fmix64 constants.
        val GOLDEN = 0x9E3779B97F4A7C15UL.toLong()
        val LCG = 0x5851F42D4C957F2DUL.toLong()
        val MIX_A = 0xFF51AFD7ED558CCDUL.toLong()
        val MIX_B = 0xC4CEB9FE1A85EC53UL.toLong()
        const val SHIFT = 33
        const val EXPONENT_BITS = 11
        const val UNIT = 1.0 / (1L shl 53)
    }
}

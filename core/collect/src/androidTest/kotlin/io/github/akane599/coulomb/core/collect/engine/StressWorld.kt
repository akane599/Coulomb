// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.os.BatteryManager
import io.github.akane599.coulomb.core.collect.CollectorClock
import io.github.akane599.coulomb.core.collect.snapshot.HeavyReader
import io.github.akane599.coulomb.core.collect.snapshot.HistoryRead
import io.github.akane599.coulomb.core.collect.snapshot.LightReading
import io.github.akane599.coulomb.core.collect.snapshot.LightSource
import io.github.akane599.coulomb.core.collect.snapshot.SourceStatus
import io.github.akane599.coulomb.core.collect.snapshot.StatsRead
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A phone on a simulated clock for the 30-day stress test (SPEC 16.3, ADR-0019): its battery, its
 * clocks across reboots, and the dumps a heavy snapshot reads. Time moves only through [advanceTo].
 */
internal class StressWorld(
    private val counters: SyntheticCounters,
    private val rawProto: ByteArray,
    pool: HistoryPool,
    startWallMs: Long,
    private val zone: ZoneId,
    storedHistoryPerDay: Double,
    newTagsPerDay: Double,
) {
    // Clocks: the device has been up for a while when Coulomb is installed.
    private var elapsedMs = BOOTED_FOR_MS
    private var sleptMs = 0L
    private var wallMinusElapsedMs = startWallMs - elapsedMs
    private var monotonicMinusElapsedMs = HISTORY_BASE_MS

    // Battery.
    private var level = START_LEVEL
    var plugged: Boolean = false
        private set
    var screenOn: Boolean = true
        private set
    var screen: VisibleScreen = VisibleScreen.COULOMB

    // The platform's statistics.
    private var statsStartWallMs = startWallMs - STATS_RUNNING_MS
    private var statsRealtimeMs = STATS_RUNNING_MS
    private var batteryRealtimeMs = STATS_RUNNING_MS / 2
    private var startCount = 1L

    /** The next dump's read sleeps: the CPU suspends inside it (ADR-0018). */
    var sleepInNextDump: Boolean = false
    var bootCount: Int = 41
        private set
    var resets: Int = 0
        private set

    val history =
        SyntheticHistory(
            pool,
            monotonicMs() - HISTORY_BEFORE_INSTALL_MS,
            storedHistoryPerDay,
            newTagsPerDay,
            state(),
            SyntheticHistory.Epoch(0, monotonicMinusElapsedMs, wallMinusElapsedMs),
        )

    val wallMs: Long get() = wallMinusElapsedMs + elapsedMs
    val levelPct: Int get() = floor(level).toInt()
    private val full: Boolean get() = plugged && level >= FULL

    val clock =
        object : CollectorClock {
            override fun elapsedNs(): Long = elapsedMs * NS_PER_MS

            override fun uptimeMs(): Long = elapsedMs - sleptMs

            override fun wallMs(): Long = this@StressWorld.wallMs

            override fun wallChanges(): Int = 0
        }

    val light = LightSource { reading() }

    val identity =
        object : DeviceIdentity {
            override val fingerprint = "stress/device:17/1"
            override val model = "stress"
            override val oem = "stress"
            override val sdkInt = 37
            override val sdkIntFull = 3_700_000
            override val appUid = OWN_UID

            override fun bootCount(): Int = this@StressWorld.bootCount

            override fun zone(): ZoneId = this@StressWorld.zone
        }

    val heavy =
        object : HeavySource {
            override fun available(): Boolean = true

            override suspend fun stats(timeoutMs: Long): StatsRead {
                val dump = counters.dump(StatsTimes(statsStartWallMs, startCount, statsRealtimeMs, batteryRealtimeMs))
                if (sleepInNextDump) {
                    sleepInNextDump = false
                    advanceTo(wallMs + DUMP_SLEEP_MS, asleep = true)
                }
                return StatsRead(dump, mapOf(HeavyReader.S5 to SourceStatus.OK), rawProto, rawIncomplete = null)
            }

            override suspend fun history(
                cursorMs: Long,
                timeoutMs: Long,
            ): HistoryRead = history.read(cursorMs, monotonicMs())
        }

    /** Runs the clocks, the battery and the statistics on to [targetWallMs]. */
    fun advanceTo(
        targetWallMs: Long,
        asleep: Boolean = false,
    ) {
        val ms = targetWallMs - wallMs
        require(ms >= 0) { "time cannot go back: $ms ms" }
        level = (level + ratePerMs() * ms).coerceIn(EMPTY, FULL)
        elapsedMs += ms
        if (asleep) sleptMs += ms
        statsRealtimeMs += ms
        if (!plugged) batteryRealtimeMs += ms
    }

    /** When the whole-percent level next changes at the current rate, or null when it does not. */
    fun nextLevelChangeWallMs(): Long? {
        val rate = ratePerMs()
        if (rate == 0.0) return null
        val whole = floor(level)
        val ms = if (rate > 0) ceil((whole + 1 - level) / rate) else floor((level - whole) / -rate) + 1
        return wallMs + maxOf(1L, ms.toLong())
    }

    /** The history records of a whole-percent change, and of a full battery. */
    fun levelChanged() {
        val at = monotonicMs()
        history.append(at, HistoryKind.BATTERY_LEVEL, HistoryChange.SET, levelPct.toLong())
        if (full) {
            history.append(at, HistoryKind.CHARGING, HistoryChange.STOP)
            history.append(at, HistoryKind.BATTERY_STATUS, HistoryChange.SET, status().toLong())
        }
    }

    fun setScreen(on: Boolean) {
        screenOn = on
        history.append(monotonicMs(), HistoryKind.SCREEN, if (on) HistoryChange.START else HistoryChange.STOP)
    }

    /**
     * Plugs in or out. Unplugging at 90% or more resets the platform's statistics, as
     * `BatteryStatsImpl` does after a near-full charge.
     */
    fun setPlugged(on: Boolean) {
        val charging = plugged && !full
        plugged = on
        val at = monotonicMs()
        history.append(at, HistoryKind.PLUGGED, if (on) HistoryChange.START else HistoryChange.STOP)
        history.append(at, HistoryKind.BATTERY_PLUG, HistoryChange.SET, plugType().toLong())
        if (on && !full) history.append(at, HistoryKind.CHARGING, HistoryChange.START)
        if (!on && charging) history.append(at, HistoryKind.CHARGING, HistoryChange.STOP)
        history.append(at, HistoryKind.BATTERY_STATUS, HistoryChange.SET, status().toLong())
        if (!on && level >= RESET_LEVEL) {
            statsStartWallMs = wallMs
            statsRealtimeMs = 0
            batteryRealtimeMs = 0
            startCount = 1
            counters.reset()
            resets++
            history.reset(at, state())
        }
    }

    /**
     * A clean reboot that takes [downMs]: elapsed time starts again, the history clock goes on from
     * its last record, and `BOOT_COUNT` and the stats start count go up. The statistics survive it
     * and do not count the time the device was off.
     */
    fun reboot(downMs: Long) {
        val shutdownAt = monotonicMs()
        val wallAtBoot = wallMs + downMs
        elapsedMs = BOOT_RECORD_ELAPSED_MS
        sleptMs = 0
        wallMinusElapsedMs = wallAtBoot - elapsedMs
        monotonicMinusElapsedMs = shutdownAt + BOOT_HISTORY_STEP_MS - elapsedMs
        bootCount++
        startCount++
        history.reboot(shutdownAt, SyntheticHistory.Epoch(monotonicMs(), monotonicMinusElapsedMs, wallMinusElapsedMs), state())
    }

    private fun monotonicMs() = elapsedMs + monotonicMinusElapsedMs

    /** Percent per millisecond: about a day of mixed use from full, and a 2.5 h charge from empty. */
    private fun ratePerMs(): Double =
        when {
            plugged -> if (full) 0.0 else CHARGE_PCT_PER_H
            screenOn -> -SCREEN_ON_PCT_PER_H
            else -> -SCREEN_OFF_PCT_PER_H
        } / MS_PER_H

    private fun plugType() = if (plugged) BatteryManager.BATTERY_PLUGGED_AC else 0

    private fun status() =
        when {
            full -> BatteryManager.BATTERY_STATUS_FULL
            plugged -> BatteryManager.BATTERY_STATUS_CHARGING
            else -> BatteryManager.BATTERY_STATUS_DISCHARGING
        }

    /** The compared history state now (HistoryIngest's flags and values). */
    private fun state(): Map<HistoryKind, Long> =
        buildMap {
            if (screenOn) put(HistoryKind.SCREEN, 1)
            if (plugged) put(HistoryKind.PLUGGED, 1)
            if (plugged && !full) put(HistoryKind.CHARGING, 1)
            put(HistoryKind.BATTERY_LEVEL, levelPct.toLong())
            put(HistoryKind.BATTERY_PLUG, plugType().toLong())
            put(HistoryKind.BATTERY_STATUS, status().toLong())
        }

    private fun reading(): LightReading {
        val currentUa =
            when {
                full -> FULL_TRICKLE_UA
                plugged -> CHARGE_PCT_PER_H * UA_PER_PCT_PER_H
                screenOn -> -SCREEN_ON_PCT_PER_H * UA_PER_PCT_PER_H
                else -> -SCREEN_OFF_PCT_PER_H * UA_PER_PCT_PER_H
            }.toLong()
        return LightReading(
            elapsedNs = elapsedMs * NS_PER_MS,
            wallMs = wallMs,
            levelPct = levelPct,
            status = status(),
            plugType = plugType(),
            health = BatteryManager.BATTERY_HEALTH_GOOD,
            voltageMv = VOLTAGE_EMPTY_MV + (level * MV_PER_PCT).toInt(),
            tempDeciC = if (plugged) TEMP_CHARGING else TEMP_IDLE,
            cycleCount = CYCLES,
            currentRaw = currentUa,
            currentAverageRaw = null,
            chargeUah = (level * UAH_PER_PCT).toLong(),
            energyNwh = null,
            screenOn = screenOn,
            dozeState = if (screenOn || plugged) 0 else DEEP_DOZE,
            powerSave = false,
            thermalStatus = 0,
        )
    }

    companion object {
        const val OWN_UID = 10_999
        const val CAPACITY_MAH = 5_000.0
        private const val NS_PER_MS = 1_000_000L
        private const val MS_PER_H = 3_600_000.0
        private const val BOOTED_FOR_MS = 40 * 3_600_000L
        private const val HISTORY_BASE_MS = 1_000_000L
        private const val HISTORY_BEFORE_INSTALL_MS = 6 * 3_600_000L
        private const val STATS_RUNNING_MS = 20 * 3_600_000L
        private const val BOOT_RECORD_ELAPSED_MS = 5_000L
        private const val BOOT_HISTORY_STEP_MS = 2_000L
        private const val DUMP_SLEEP_MS = 3_000L
        private const val START_LEVEL = 70.0
        private const val EMPTY = 1.0
        private const val FULL = 100.0
        private const val RESET_LEVEL = 90.0
        private const val CHARGE_PCT_PER_H = 40.0
        private const val SCREEN_ON_PCT_PER_H = 10.0
        private const val SCREEN_OFF_PCT_PER_H = 0.5
        private const val UAH_PER_PCT = CAPACITY_MAH * 10.0
        private const val UA_PER_PCT_PER_H = CAPACITY_MAH * 10.0
        private const val FULL_TRICKLE_UA = 50_000.0
        private const val VOLTAGE_EMPTY_MV = 3_500
        private const val MV_PER_PCT = 7.0
        private const val TEMP_IDLE = 290
        private const val TEMP_CHARGING = 340
        private const val CYCLES = 120
        private const val DEEP_DOZE = 2
    }
}

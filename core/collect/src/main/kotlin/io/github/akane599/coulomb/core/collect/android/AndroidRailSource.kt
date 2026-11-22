// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.android

import android.content.Context
import android.os.OutcomeReceiver
import android.os.PowerMonitor
import android.os.PowerMonitorReadings
import android.os.SystemClock
import android.os.health.SystemHealthManager
import io.github.akane599.coulomb.core.collect.engine.RailReading
import io.github.akane599.coulomb.core.collect.engine.RailSource
import io.github.akane599.coulomb.core.model.RailNames
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * S16 through `SystemHealthManager` (T0, API 35): the supported monitors once per process, then
 * one reading of all of them per heavy snapshot. `getConsumedEnergy` is "energy consumed ... since
 * boot in microwatt-seconds", on battery and plugged in alike, or `ENERGY_UNAVAILABLE`
 * (PowerMonitorReadings.java, android-16.0.0_r4 and android-17.0.0_r1 L36, L92-104;
 * SystemHealthManager.java L486, L542 in both). A failed or slow read is no rows, never a partial
 * snapshot: the rails are an addition to S4 and S5, not a source they need.
 */
internal class AndroidRailSource(
    private val context: Context,
) : RailSource {
    private var monitors: List<PowerMonitor>? = null

    override suspend fun read(timeoutMs: Long): List<RailReading> {
        val shm = context.getSystemService(SystemHealthManager::class.java) ?: return emptyList()
        return read(shm, SystemClock.elapsedRealtime() + timeoutMs)
    }

    /** Both steps by [deadline], each within TIMEOUT_MS (audit PERF-004). */
    private suspend fun read(
        shm: SystemHealthManager,
        deadline: Long,
    ): List<RailReading> {
        val supported = monitors ?: supported(shm, left(deadline))?.also { monitors = it } ?: return emptyList()
        if (supported.isEmpty()) return emptyList()
        val readings = readings(shm, supported, left(deadline)) ?: return emptyList()
        return supported.mapNotNull { monitor ->
            val energy = readings.getConsumedEnergy(monitor)
            if (energy == PowerMonitorReadings.ENERGY_UNAVAILABLE.toLong() || energy < 0) {
                null
            } else {
                RailReading(RailNames.stored(monitor.type, monitor.name), energy, readings.getTimestampMillis(monitor) * NS_PER_MS)
            }
        }
    }

    private fun left(deadline: Long): Long = minOf(TIMEOUT_MS, deadline - SystemClock.elapsedRealtime())

    private suspend fun supported(
        shm: SystemHealthManager,
        timeoutMs: Long,
    ): List<PowerMonitor>? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                shm.getSupportedPowerMonitors(Runnable::run) { list -> if (cont.isActive) cont.resume(list) }
            }
        }

    private suspend fun readings(
        shm: SystemHealthManager,
        supported: List<PowerMonitor>,
        timeoutMs: Long,
    ): PowerMonitorReadings? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                shm.getPowerMonitorReadings(
                    supported,
                    Runnable::run,
                    object : OutcomeReceiver<PowerMonitorReadings, RuntimeException> {
                        override fun onResult(result: PowerMonitorReadings) {
                            if (cont.isActive) cont.resume(result)
                        }

                        override fun onError(error: RuntimeException) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }
        }

    private companion object {
        const val TIMEOUT_MS = 1_000L
        const val NS_PER_MS = 1_000_000L
    }
}

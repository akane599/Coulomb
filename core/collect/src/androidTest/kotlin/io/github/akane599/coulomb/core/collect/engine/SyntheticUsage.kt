// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import io.github.akane599.coulomb.core.model.stats.BatteryStatsDump
import io.github.akane599.coulomb.core.model.stats.ComponentPower
import io.github.akane599.coulomb.core.model.stats.PowerColumn
import io.github.akane599.coulomb.core.model.stats.SipperKind
import io.github.akane599.coulomb.core.model.stats.UsageStatsDump
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * S4 dumps shaped like the S5 dump of the same moment (ADR-0035, Size): each UID with energy gets
 * its S5 total spread over a few components and two process states, and the device consumer gets
 * S5's modeled total. So every consecutive window on battery passes decision 5 and stores its
 * component rows, which is the upper bound the size gate needs.
 *
 * Each column is its share of the total rounded by itself, so it only grows while S5's total does;
 * the columns then add up to the total within a few dC, inside decision 5's margins.
 */
internal object SyntheticUsage {
    fun of(dump: BatteryStatsDump): UsageStatsDump {
        val device = checkNotNull(dump.device) { "the synthetic dump has no device stats" }
        // As CounterMapper.devicePower reads S5's device rows.
        val modeled = device.powerUse.filter { it.kind != SipperKind.USER && it.mah > 0 }.sumOf { it.mah }
        val uids = HashMap<Int, Double>()
        for (uid in dump.uids) uid.powerMah?.takeIf { it > 0 }?.let { uids.merge(uid.uid, it, Double::plus) }
        return UsageStatsDump(
            sessionStartMillis = device.startClockTimeWallMs,
            sessionEndMillis = null,
            device = split(modeled, DEVICE_SHARES),
            uids = uids.mapValues { (_, mah) -> split(mah, UID_SHARES + STATE_SHARES) },
            failures = emptyMap(),
        )
    }

    /**
     * Stand-in bytes for the blob, at an estimated size: no phone S4 capture exists yet (ADR-0035,
     * pending real device). About [BYTES_PER_UID] a UID consumer (its id, a total and six
     * component entries with process-state slices); random, so compression gains nothing.
     */
    fun raw(dump: UsageStatsDump): ByteArray = Random(dump.uids.size).nextBytes(HEADER_BYTES + dump.uids.size * BYTES_PER_UID)

    private fun split(
        mah: Double,
        shares: Map<PowerColumn, Double>,
    ): ComponentPower {
        val dc = mah * ComponentPower.DC_PER_MAH
        return ComponentPower.of(shares.mapValues { (_, share) -> (dc * share).roundToLong() })
    }

    private val DEVICE_SHARES =
        mapOf(
            PowerColumn.SCREEN to 0.35,
            PowerColumn.CPU to 0.30,
            PowerColumn.MOBILE_RADIO to 0.15,
            PowerColumn.WIFI to 0.10,
            PowerColumn.GNSS to 0.05,
            PowerColumn.BLUETOOTH to 0.05,
        )
    private val UID_SHARES = mapOf(PowerColumn.CPU to 0.6, PowerColumn.WIFI to 0.2, PowerColumn.MOBILE_RADIO to 0.2)
    private val STATE_SHARES = mapOf(PowerColumn.FOREGROUND to 0.7, PowerColumn.BACKGROUND to 0.3)
    private const val HEADER_BYTES = 200
    private const val BYTES_PER_UID = 96
}

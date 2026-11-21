// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.database.dao.DeviceDao
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.dao.SnapshotDao
import io.github.akane599.coulomb.core.database.dao.WindowDao
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Coulomb's own share of the battery (SPEC 6.9) between two heavy snapshots at most a day apart:
 * what Android's power model gives Coulomb's UID, as the self-cost insight (R20) measures it.
 */
public data class SelfCost(
    val ownMah: Double,
    /** The battery's capacity, estimated or by design; null while neither is known. */
    val capacityMah: Double?,
    val fromWallMs: Long,
    val toWallMs: Long,
) {
    /** Percent of the battery over the span; null without a capacity. */
    val pct: Double? get() = capacityMah?.takeIf { it > 0 }?.let { ownMah / it * PERCENT }

    private companion object {
        const val PERCENT = 100.0
    }
}

/** Reads [SelfCost] from the stored windows. It reads once; Settings asks when it opens. */
public class SelfCostReader
    @Inject
    internal constructor(
        private val snapshots: SnapshotDao,
        private val windows: WindowDao,
        private val sessions: SessionDao,
        private val devices: DeviceDao,
        private val dispatchers: AppDispatchers,
    ) {
        /**
         * The day up to the latest heavy snapshot for [ownUid]; null until two heavy snapshots
         * of the last day are stored. [fingerprint] picks the phone's design capacity where no
         * estimate has settled.
         */
        public suspend fun lastDay(
            ownUid: Int,
            fingerprint: String,
        ): SelfCost? =
            withContext(dispatchers.io) {
                val to = snapshots.latestHeavy() ?: return@withContext null
                val from = snapshots.firstHeavyFrom(to.wallMs - DAY_MS) ?: return@withContext null
                val before = from.bootSeq < to.bootSeq || (from.bootSeq == to.bootSeq && from.elapsedNs < to.elapsedNs)
                if (!before) return@withContext null
                val own =
                    windows
                        .chainUids(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)
                        .filter { it.uid == ownUid && it.component == WindowDrainsBuilder.ALL_COMPONENTS }
                        .sumOf { it.mAh }
                val capacity = sessions.latestHealthEstimateMah() ?: devices.profile(fingerprint)?.designCapacityMah?.toDouble()
                SelfCost(own, capacity, from.wallMs, to.wallMs)
            }

        private companion object {
            const val DAY_MS = 86_400_000L
        }
    }

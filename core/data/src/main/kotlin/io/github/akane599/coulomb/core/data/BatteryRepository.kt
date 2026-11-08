// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.common.AppScope
import io.github.akane599.coulomb.core.common.ZoneSource
import io.github.akane599.coulomb.core.database.dao.SampleDao
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The battery right now, the live current, the open discharge session and its level line (SPEC
 * 10.2, 10.8). Each new sample re-reads them; nothing here polls (SPEC 7.4). Flows stop 5 s after the last screen
 * leaves, so a rotation keeps them.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
public class BatteryRepository
    @Inject
    internal constructor(
        private val samples: SampleDao,
        private val sessions: SessionDao,
        private val zone: ZoneSource,
        dispatchers: AppDispatchers,
        scope: AppScope,
    ) {
        private val latest: Flow<BatterySampleEntity?> = samples.observeLatest().distinctUntilChanged()

        private val trail = LevelTrailCache()

        /**
         * Everything one sample changes, read together: Now takes it whole, so a sample recomposes the
         * screen once and the chart never pairs a new reading with an old window (ADR-0034).
         */
        public val reading: StateFlow<BatteryReading?> =
            latest
                .mapLatest { sample ->
                    if (sample == null) {
                        trail.clear()
                        null
                    } else {
                        read(sample)
                    }
                }.flowOn(dispatchers.io)
                .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        private suspend fun read(sample: BatterySampleEntity): BatteryReading {
            val overlapping = sessions.overlapping(DischargeSummaries.KINDS, sample.wallMs - DischargeSummaries.LOOKBACK_MS)
            val discharge = DischargeSummaries.build(sample, overlapping, zone.zone())
            val start = discharge?.startWallMs
            val levels =
                if (start == null) {
                    trail.clear()
                    emptyList()
                } else {
                    trail.read(start, sample.wallMs, samples::samplesBetween)
                }
            val live =
                samples
                    .samplesBetween(sample.wallMs - LIVE_WINDOW_MS, sample.wallMs + 1)
                    .mapNotNull { s -> s.currentUa?.let { CurrentPoint(s.wallMs, it / UA_PER_MA) } }
            return BatteryReading(sample.toBatteryNow(), discharge, levels, live)
        }

        private fun BatterySampleEntity.toBatteryNow() =
            BatteryNow(
                levelPct = levelPct,
                currentMa = currentUa?.let { it / UA_PER_MA },
                plugged = plugType != 0,
                status = status,
                tempDeciC = tempDeciC,
                voltageMv = voltageMv,
                screenOn = screenOn,
                wallMs = wallMs,
            )

        public companion object {
            /** SPEC 10.2: the live chart shows 10 minutes. */
            public const val LIVE_WINDOW_MS: Long = 10 * 60_000L
            internal const val STOP_TIMEOUT_MS: Long = 5_000
            private const val UA_PER_MA = 1_000.0
        }
    }

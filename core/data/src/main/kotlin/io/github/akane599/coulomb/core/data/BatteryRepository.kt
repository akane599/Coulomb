// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.util.Log
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.common.ZoneSource
import io.github.akane599.coulomb.core.database.dao.HistoryDao
import io.github.akane599.coulomb.core.database.dao.SampleDao
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.dao.StoredLevelChange
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.CorruptChunkException
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.SessionKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The battery right now, the live current, the open discharge session and its level line (SPEC
 * 10.2, 10.8). Each new sample re-reads them; nothing here polls (SPEC 7.4). The flow is cold, one
 * read per collector (ADR-0048): a failed read reaches the screen's error state, and "Try again"
 * reads afresh. The screen's own model keeps it over a rotation.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
public class BatteryRepository
    @Inject
    internal constructor(
        private val samples: SampleDao,
        private val sessions: SessionDao,
        private val history: HistoryDao,
        private val zone: ZoneSource,
        private val dispatchers: AppDispatchers,
    ) {
        private val latest: Flow<BatterySampleEntity?> = samples.observeLatest().distinctUntilChanged()

        /**
         * Everything one sample changes, read together: Now takes it whole, so a sample recomposes the
         * screen once and the chart never pairs a new reading with an old window (ADR-0034).
         */
        public val reading: Flow<BatteryReading?> =
            flow {
                // The level line's and the drops' caches belong to this collection alone.
                val trail = LevelTrailCache()
                val drops = LevelDropsCache()
                emitAll(
                    latest.mapLatest { sample ->
                        if (sample == null) {
                            trail.clear()
                            drops.clear()
                            null
                        } else {
                            read(sample, trail, drops)
                        }
                    },
                )
            }.flowOn(dispatchers.io)

        private suspend fun read(
            sample: BatterySampleEntity,
            trail: LevelTrailCache,
            dropCache: LevelDropsCache,
        ): BatteryReading {
            val overlapping = sessions.overlapping(DischargeSummaries.KINDS, sample.wallMs - DischargeSummaries.LOOKBACK_MS)
            val drops = dropCache.read(timedCandidates(sample, overlapping)) { s -> levelChanges(s) }
            val discharge = DischargeSummaries.build(sample, overlapping, zone.zone(), drops)
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

        /**
         * The closed screen-off sessions in the open discharge: the ones Now's screen-off rate counts
         * (ADR-0049). Older ones feed the time estimate's history by their ends.
         */
        private fun timedCandidates(
            sample: BatterySampleEntity,
            sessions: List<SessionEntity>,
        ): List<SessionEntity> {
            if (sample.plugType != 0) return emptyList()
            val open = sessions.lastOrNull { it.kind == SessionKind.DISCHARGE && it.closeReason == null } ?: return emptyList()
            return sessions.filter { LevelDropsCache.timed(it) && (it.endWall ?: 0) > open.startWall }
        }

        /**
         * A session's stored level changes, or null until a history read past its end is stored. A
         * chunk that does not decode gives none: the session counts from its ends, and the cache
         * keeps that, so the log has one line per session rather than one per sample.
         */
        private suspend fun levelChanges(session: SessionEntity): List<StoredLevelChange>? {
            val end = checkNotNull(session.endElapsedNs)
            if (!history.readAfter(session.bootSeq, end)) return null
            return try {
                history.levelChanges(session.bootSeq, session.startElapsedNs, session.bootSeq, end)
            } catch (e: CorruptChunkException) {
                Log.w(TAG, "history of session ${session.id} does not decode; timed by its ends", e)
                emptyList()
            }
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
            private const val UA_PER_MA = 1_000.0
            private const val TAG = "Coulomb:battery"
        }
    }

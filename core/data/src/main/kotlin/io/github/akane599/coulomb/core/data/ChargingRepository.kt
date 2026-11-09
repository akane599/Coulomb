// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.analytics.ChargeLevel
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.database.dao.LevelStep
import io.github.akane599.coulomb.core.database.dao.SampleDao
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.ChargerProfileEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.datastore.SettingsStore
import io.github.akane599.coulomb.core.model.SessionKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Charging screen's side (SPEC 10.9, ADR-0046): the charge in progress, read again at each
 * sample, which comes every second while the screen shows (SPEC 7.3). Each sample costs three short
 * queries: the latest sample, the open session and the samples since the last. The past charges are
 * read again only when one closes, and a closed charge's line only once while the app runs.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
public class ChargingRepository
    @Inject
    internal constructor(
        private val samples: SampleDao,
        private val sessions: SessionDao,
        private val settings: SettingsStore,
        dispatchers: AppDispatchers,
    ) {
        private val latest: Flow<BatterySampleEntity?> = samples.observeLatest().distinctUntilChanged()

        /** Closed charges' level lines never change, so every collector shares them, one at a time. */
        private val pastLines = PastLineCache()
        private val pastLock = Mutex()

        /** Whether the phone is plugged in, for showing Charging in place of Now (SPEC 10.9). */
        public val plugged: Flow<Boolean> = latest.map { it != null && it.plugType != 0 }.distinctUntilChanged()

        /**
         * The charge in progress, null while unplugged. Cold: each collector has its own caches and
         * sees a failed read as an exception, so the screen can show its error and read again
         * (gate audit). The screen's model shares it.
         */
        public val now: Flow<ChargingNow?> =
            flow {
                val live = LiveChargeCache()
                val history =
                    sessions
                        .observeClosed(SessionKind.CHARGE, HISTORY)
                        .distinctUntilChanged()
                        .mapLatest { rows -> pastLock.withLock { readHistory(rows, pastLines) } }
                emitAll(
                    combine(
                        latest,
                        history,
                        sessions.observeChargerProfiles(),
                        settings.settings.map { it.chargeTargetPct }.distinctUntilChanged(),
                    ) { sample, past, profiles, target -> Inputs(sample, past, profiles, target) }
                        .mapLatest { read(it, live) },
                )
            }.flowOn(dispatchers.io)

        /** The charge target, held to SPEC 14.4's range. */
        public suspend fun setTarget(pct: Int): Unit = settings.chargeTarget(pct)

        private suspend fun read(
            inputs: Inputs,
            live: LiveChargeCache,
        ): ChargingNow? {
            val sample = inputs.sample
            if (sample == null || sample.plugType == 0) {
                live.clear()
                return null
            }
            // The collector opens the session at the plug event; until then the latest sample stands alone.
            val session = sessions.open().firstOrNull { it.kind == SessionKind.CHARGE && it.bootSeq == sample.bootSeq }
            val read =
                session?.let { s -> live.read(s.bootSeq, s.startElapsedNs, sample.elapsedNs, samples::samples) }.orEmpty()
            val charge = read.ifEmpty { listOf(sample) }
            return ChargingReader.now(charge, session?.startWall ?: sample.wallMs, inputs.history, inputs.profiles, inputs.target)
        }

        private suspend fun readHistory(
            rows: List<SessionEntity>,
            pastLines: PastLineCache,
        ): List<PastChargeRow> {
            val details = sessions.chargeDetails(rows.map { it.id }).associateBy { it.sessionId }
            val lines = pastLines.read(rows, samples::levelSteps)
            return rows.mapNotNull { row ->
                val points = lines[row.id] ?: return@mapNotNull null
                val detail = details[row.id]
                PastChargeRow(row.id, detail?.chargerProfileId, detail?.plugType ?: 0, detail?.ccToCvLevel, points)
            }
        }

        private data class Inputs(
            val sample: BatterySampleEntity?,
            val history: List<PastChargeRow>,
            val profiles: List<ChargerProfileEntity>,
            val target: Int,
        )

        private companion object {
            /** Past charges read for the time estimate; it uses at most ten that cover the levels. */
            const val HISTORY = 30
        }
    }

/**
 * The open charge's samples, reading only those since the last call. Not thread-safe: one flow
 * drives it, one value at a time; a read cancelled half-way leaves it as it was.
 */
internal class LiveChargeCache {
    private var key: Pair<Int, Long>? = null
    private var samples: List<BatterySampleEntity> = emptyList()

    suspend fun read(
        bootSeq: Int,
        startElapsedNs: Long,
        nowElapsedNs: Long,
        fetch: suspend (bootSeq: Int, fromElapsedNs: Long, toElapsedNs: Long) -> List<BatterySampleEntity>,
    ): List<BatterySampleEntity> {
        val session = bootSeq to startElapsedNs
        val kept = if (key == session) samples else emptyList()
        val from = kept.lastOrNull()?.let { it.elapsedNs + 1 } ?: startElapsedNs
        if (from > nowElapsedNs) return kept
        val next = kept + fetch(bootSeq, from, nowElapsedNs + 1)
        key = session
        samples = next
        return next
    }

    fun clear() {
        key = null
        samples = emptyList()
    }
}

/** Past charges' level lines by session id; a closed session's line is read once. */
internal class PastLineCache {
    private var lines: Map<Long, List<ChargeLevel>> = emptyMap()

    suspend fun read(
        rows: List<SessionEntity>,
        fetch: suspend (bootSeq: Int, fromElapsedNs: Long, toElapsedNs: Long) -> List<LevelStep>,
    ): Map<Long, List<ChargeLevel>> {
        val next =
            rows
                .mapNotNull { row ->
                    val end = row.endElapsedNs ?: return@mapNotNull null
                    val line =
                        lines[row.id] ?: fetch(row.bootSeq, row.startElapsedNs, end + 1).map { ChargeLevel(it.elapsedNs, it.levelPct) }
                    row.id to line
                }.toMap()
        lines = next
        return next
    }
}

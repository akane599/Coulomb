// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.analytics.ChargeLevel
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.common.AppScope
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Charging screen's side (SPEC 10.9, ADR-0046): the charge in progress, read again at each
 * sample, which comes every second while the screen shows (SPEC 7.3). Each sample costs one short
 * query for the samples since the last; the past charges are read again only when one closes.
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
        scope: AppScope,
    ) {
        private val latest: Flow<BatterySampleEntity?> = samples.observeLatest().distinctUntilChanged()

        private val live = LiveChargeCache()
        private val pastLines = PastLineCache()

        /** Whether the phone is plugged in, for showing Charging in place of Now (SPEC 10.9). */
        public val plugged: Flow<Boolean> = latest.map { it != null && it.plugType != 0 }.distinctUntilChanged()

        /** The latest closed charges, newest first, with their level lines. */
        private val history: Flow<List<PastChargeRow>> =
            sessions
                .observeClosed(SessionKind.CHARGE, HISTORY)
                .distinctUntilChanged()
                .mapLatest(::readHistory)

        /** The charge in progress; null while unplugged. */
        public val now: StateFlow<ChargingNow?> =
            combine(
                latest,
                history,
                sessions.observeChargerProfiles(),
                settings.settings.map { it.chargeTargetPct }.distinctUntilChanged(),
            ) { sample, past, profiles, target -> Inputs(sample, past, profiles, target) }
                .mapLatest(::read)
                .flowOn(dispatchers.io)
                .stateIn(scope, SharingStarted.WhileSubscribed(BatteryRepository.STOP_TIMEOUT_MS), null)

        /** The charge target, held to SPEC 14.4's range. */
        public suspend fun setTarget(pct: Int): Unit = settings.chargeTarget(pct)

        private suspend fun read(inputs: Inputs): ChargingNow? {
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

        private suspend fun readHistory(rows: List<SessionEntity>): List<PastChargeRow> {
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

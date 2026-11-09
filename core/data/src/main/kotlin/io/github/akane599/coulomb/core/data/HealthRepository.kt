// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.analytics.Capacity
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.common.ZoneSource
import io.github.akane599.coulomb.core.database.dao.DeviceDao
import io.github.akane599.coulomb.core.database.dao.SampleDao
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.entity.CapacityEstimateEntity
import io.github.akane599.coulomb.core.database.entity.ChargerProfileEntity
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity
import io.github.akane599.coulomb.core.database.entity.HealthSummaryEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.DesignCapacity
import io.github.akane599.coulomb.core.model.DesignCapacitySource
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
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** `Build.FINGERPRINT`, the key of the device profile (SPEC 8). */
public fun interface BuildFingerprint {
    public fun value(): String
}

/**
 * The Health screen's stored side (SPEC 10.8, ADR-0044): the capacity estimate, the day's readings,
 * cycles, the capacity chart, charging habits, charge sessions and chargers. Room invalidation
 * drives every read; nothing polls (SPEC 7.4).
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
public class HealthRepository
    @Inject
    internal constructor(
        private val sessions: SessionDao,
        private val samples: SampleDao,
        private val devices: DeviceDao,
        private val fingerprint: BuildFingerprint,
        private val zone: ZoneSource,
        private val dispatchers: AppDispatchers,
    ) {
        private val design: Flow<DesignCapacity?> =
            devices.observeProfile(fingerprint.value()).map { it?.design() }.distinctUntilChanged()

        /** Re-read when the estimates, the day's rows or the design capacity change. */
        private val stored: Flow<Stored> =
            combine(
                sessions.observeCapacityEstimates(),
                sessions.observeHealthSummaries(),
                design,
            ) { estimates, summaries, design -> Stored(estimates, summaries, design) }.distinctUntilChanged()

        /**
         * The closed charge sessions: the latest few for the list, and those of the habits'
         * window, read again only when one closes (the open session changes with every sample). The
         * window starts from each collection's own time, so a long-lived process does not keep
         * reading back to its start; [read] trims it to the last 30 days at each read.
         */
        private val charges: Flow<Charges> =
            flow {
                emitAll(
                    combine(
                        sessions.observeClosed(SessionKind.CHARGE, RECENT_SESSIONS + 1),
                        sessions.observeClosedSince(SessionKind.CHARGE, System.currentTimeMillis() - HABITS_MS),
                        sessions.observeChargerProfiles(),
                    ) { recent, window, chargers -> Charges(recent, window, chargers) },
                )
            }.distinctUntilChanged()

        /** The report, read again when its rows change; the screen holds it while it shows. */
        public val report: Flow<HealthReport> =
            combine(stored, charges, ::Pair)
                .mapLatest { (stored, charges) -> read(stored, charges) }
                .flowOn(dispatchers.io)

        /** Every closed charge session, newest first, for "See all". */
        public val allSessions: Flow<List<ChargeSessionRow>> =
            sessions
                .observeClosed(SessionKind.CHARGE, ALL_SESSIONS)
                .map { rows -> rows.map(HealthReader::row) }
                .distinctUntilChanged()
                .flowOn(dispatchers.io)

        /** One charge session with its figures and curve; null when there is no such session. */
        public fun session(id: Long): Flow<ChargeSessionDetail?> =
            combine(sessions.observeSession(id), sessions.observeChargeDetail(id), sessions.observeChargerProfiles()) { s, d, c ->
                Triple(s, d, c)
            }.distinctUntilChanged()
                .mapLatest { (session, detail, chargers) ->
                    session?.takeIf { it.kind == SessionKind.CHARGE && it.closeReason != null }?.let { s ->
                        val charger = chargers.firstOrNull { it.id == detail?.chargerProfileId }?.let(HealthReader::charger)
                        ChargeSessionDetail(
                            row = HealthReader.row(s),
                            plugType = detail?.plugType,
                            charger = charger,
                            peakW = detail?.peakW,
                            fullSpeedW = detail?.avgW,
                            ccToCvLevel = detail?.ccToCvLevel,
                            minutesAtOrAbove80 = s.minutesAtOrAbove80,
                            minutesAbove38C = detail?.minutesAbove38C,
                            meanTempDeciC = s.meanTempDeciC,
                            maxTempDeciC = s.maxTempDeciC,
                            curve = curve(s),
                        )
                    }
                }.flowOn(dispatchers.io)

        /** Names a charger; a blank name clears it. */
        public suspend fun renameCharger(
            id: Long,
            name: String,
        ): Unit = withContext(dispatchers.io) { sessions.renameChargerProfile(id, name.trim().take(MAX_NAME)) }

        /**
         * The owner's design capacity (SPEC 6.3, ADR-0044 §5). Returns false, storing nothing, for
         * a value outside [DesignCapacity.PLAUSIBLE_MAH] or before the build has a profile.
         */
        public suspend fun setDesignCapacity(mah: Int): Boolean {
            if (mah.toDouble() !in DesignCapacity.PLAUSIBLE_MAH) return false
            return withContext(dispatchers.io) { devices.setDesignCapacity(fingerprint.value(), mah, DesignCapacitySource.USER.code) > 0 }
        }

        /**
         * Undoes the owner's design capacity on every build (ADR-0044 §5), then stores [reported], the
         * value the phone reports, where there is one; without it the collector's next read fills it.
         */
        public suspend fun useReportedDesignCapacity(reported: DesignCapacity?): Unit =
            withContext(dispatchers.io) {
                // Clear first: should the app stop between the two, the collector's read fills the gap.
                devices.clearDesignCapacityFrom(DesignCapacitySource.USER.code)
                reported?.takeIf { it.source != DesignCapacitySource.USER && it.mah in DesignCapacity.PLAUSIBLE_MAH }?.let {
                    devices.fillDesignCapacity(fingerprint.value(), it.mah.roundToInt(), it.source.code)
                }
            }

        private suspend fun read(
            stored: Stored,
            charges: Charges,
        ): HealthReport {
            val now = System.currentTimeMillis()
            val today = Instant.ofEpochMilli(now).atZone(zone.zone()).toLocalDate()
            val capacity = HealthReader.capacity(stored.estimates, stored.design, now)
            // The day the estimate first had enough windows for an interval of its own (oldest first).
            val settledDay =
                stored.estimates.getOrNull(Capacity.SETTLED_WINDOWS - 1)?.let {
                    Instant.ofEpochMilli(it.createdWall).atZone(zone.zone()).toLocalDate()
                }
            val window = charges.window.filter { (it.endWall ?: 0) >= now - HABITS_MS }
            val highTime = samples.timeAtOrAbove(HIGH_LEVEL, now - HABITS_MS, now + 1, MAX_GAP_MS)
            return HealthReport(
                capacity = capacity,
                design = stored.design,
                change = HealthReader.change(stored.summaries, capacity, today, settledDay),
                crossCheck = HealthReader.crossCheck(stored.summaries),
                cycles = HealthReader.cycles(stored.summaries, firstCountedDay()),
                trend = HealthReader.trend(stored.estimates, stored.summaries, settledDay),
                habits = HealthReader.habits(window, highTime),
                sessions = charges.recent.take(RECENT_SESSIONS).map(HealthReader::row),
                moreSessions = charges.recent.size > RECENT_SESSIONS,
                chargers = charges.chargers.map(HealthReader::charger),
            )
        }

        /** The day the first charge counted in the running sum started (ADR-0037 §4). */
        private suspend fun firstCountedDay(): LocalDate? =
            sessions.firstCounted(SessionKind.CHARGE)?.let { Instant.ofEpochMilli(it.startWall).atZone(zone.zone()).toLocalDate() }

        private suspend fun curve(session: SessionEntity): List<ChargeCurvePoint> {
            val end = session.endElapsedNs ?: return emptyList()
            val raw = samples.samples(session.bootSeq, session.startElapsedNs, end + 1)
            if (raw.isNotEmpty()) return HealthReader.curve(raw)
            val endWall = session.endWall ?: return emptyList()
            return HealthReader.aggregateCurve(
                samples.aggregatesBetween(MINUTE_S, session.startWall, endWall + 1).filter { it.bootSeq == session.bootSeq },
            )
        }

        private fun DeviceProfileEntity.design(): DesignCapacity? {
            val mah = designCapacityMah ?: return null
            val source = DesignCapacitySource.fromCode(designCapacitySource) ?: return null
            return DesignCapacity(mah.toDouble(), source)
        }

        private data class Stored(
            val estimates: List<CapacityEstimateEntity>,
            val summaries: List<HealthSummaryEntity>,
            val design: DesignCapacity?,
        )

        private data class Charges(
            val recent: List<SessionEntity>,
            val window: List<SessionEntity>,
            val chargers: List<ChargerProfileEntity>,
        )

        public companion object {
            /** Sessions on the Health screen before "See all" (the lab shows three). */
            public const val RECENT_SESSIONS: Int = 3
            private const val ALL_SESSIONS = 1_000
            private const val HABITS_MS = ChargeHabits.DAYS * 86_400_000L
            private const val HIGH_LEVEL = 80

            /** A longer gap between samples is time Coulomb did not watch (ADR-0044 §2). */
            internal const val MAX_GAP_MS: Long = 6 * 3_600_000L
            private const val MINUTE_S = 60
            private const val MAX_NAME = 40
        }
    }

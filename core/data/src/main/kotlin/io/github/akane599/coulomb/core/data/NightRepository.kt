// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.util.Log
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.database.dao.BootClock
import io.github.akane599.coulomb.core.database.dao.HistoryDao
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.dao.SnapshotDao
import io.github.akane599.coulomb.core.database.dao.StoredHistoryEvent
import io.github.akane599.coulomb.core.database.entity.CorruptChunkException
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.history.HistoryKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One screen-off session of an hour or more, as the Night Report lists it (SPEC 10.7). */
public data class NightSummary(
    /** The session's id; [DrainSpan.Night] reads who used the battery in it. */
    val id: Long,
    /** By the boot's clock as last seen, so a clock set right later in the boot dates the session right (ADR-0056). */
    val startWallMs: Long,
    val endWallMs: Long,
    /** By the elapsed clock, which a wall-clock change cannot bend. */
    val durationMs: Long,
    val startLevel: Int,
    val endLevel: Int,
    /**
     * Percent an hour over the session, from the level change. Null when the phone charged in the
     * session or the level fell by fewer than [Nights.MIN_DROP_PCT] points, which whole percents
     * cannot turn into a rate. Derived.
     */
    val ratePctPerHour: Double?,
    /** A charge session overlapped, or the level rose. */
    val charged: Boolean,
    /** The share of the session the CPU was suspended, 0 to 1; null when uptime was not recorded. Measured. */
    val deepSleepShare: Double?,
    /** Whether deep Doze was reached; null when the history does not cover the session. Measured. */
    val deepDozeReached: Boolean?,
    /** How long into the session deep Doze began. */
    val deepDozeAfterMs: Long?,
)

/** Whether a session reached deep Doze, and how long after its start. */
public data class DozeReached(
    val reached: Boolean,
    val afterMs: Long?,
)

/** SPEC 10.7's list rows from stored sessions. Pure. */
public object Nights {
    public const val MIN_MS: Long = 3_600_000

    /** ADR-0054 §6: a rate from whole percents needs three of them. */
    public const val MIN_DROP_PCT: Int = 3

    private const val NS_PER_MS = 1_000_000L
    private const val MS_PER_HOUR = 3_600_000.0

    /**
     * [session] as a night; null while it is open or under an hour. [charges] are charge sessions
     * that may overlap it: they do only in the same boot, by the elapsed clock, since a wall clock
     * that was wrong for a while makes far-apart sessions look alike. [clock] dates the session;
     * without one the session's own wall times stand. [doze] is used where the session stored none.
     */
    public fun summary(
        session: SessionEntity,
        charges: List<SessionEntity>,
        clock: BootClock? = null,
        doze: DozeReached? = null,
    ): NightSummary? {
        val endNs = session.endElapsedNs ?: return null
        val endWall = session.endWall ?: return null
        val endLevel = session.endLevel ?: return null
        val durationMs = (endNs - session.startElapsedNs) / NS_PER_MS
        if (durationMs < MIN_MS) return null
        val drop = session.startLevel - endLevel
        val charged = drop < 0 || charges.any { overlaps(it, session, endNs) }
        val reached = session.deepIdleReached ?: doze?.reached
        return NightSummary(
            id = session.id,
            startWallMs = clock?.wallAt(session.startElapsedNs) ?: session.startWall,
            endWallMs = clock?.wallAt(endNs) ?: endWall,
            durationMs = durationMs,
            startLevel = session.startLevel,
            endLevel = endLevel,
            ratePctPerHour = (drop / (durationMs / MS_PER_HOUR)).takeIf { !charged && drop >= MIN_DROP_PCT },
            charged = charged,
            deepSleepShare = session.awakeRatio?.let { (1 - it).coerceIn(0.0, 1.0) },
            deepDozeReached = reached,
            deepDozeAfterMs = (session.deepIdleReachedMs ?: doze?.afterMs).takeIf { reached == true },
        )
    }

    private fun overlaps(
        charge: SessionEntity,
        session: SessionEntity,
        endNs: Long,
    ): Boolean {
        val chargeEnd = charge.endElapsedNs ?: Long.MAX_VALUE
        return charge.bootSeq == session.bootSeq && charge.startElapsedNs < endNs && chargeEnd > session.startElapsedNs
    }

    /** The wall time at [elapsedNs] of this clock's boot. */
    internal fun BootClock.wallAt(elapsedNs: Long): Long = wallMs - Math.floorDiv(this.elapsedNs - elapsedNs, NS_PER_MS)
}

/** The Night Report's sessions (SPEC 10.7): screen-off stretches of an hour or more, newest first. */
@Singleton
public class NightRepository
    @Inject
    internal constructor(
        private val sessions: SessionDao,
        private val snapshots: SnapshotDao,
        private val history: HistoryDao,
        private val dispatchers: AppDispatchers,
    ) {
        /** A closed session's history does not change once it is read, so what it says about Doze is kept. */
        private val doze = ConcurrentHashMap<Long, DozeReached>()

        /**
         * The latest [MAX_NIGHTS], newest first by boot and elapsed time (SPEC 7.8), read again
         * whenever a session closes. The first is the latest night, whatever the wall clock said.
         */
        public val nights: Flow<List<NightSummary>> =
            sessions
                .observeClosedLasting(SessionKind.SCREEN_OFF, Nights.MIN_MS * NS_PER_MS, MAX_NIGHTS)
                .map(::summaries)
                .flowOn(dispatchers.io)

        /** One night; null when the session is gone or is not a closed screen-off session of an hour. */
        public fun night(id: Long): Flow<NightSummary?> =
            sessions
                .observeSession(id)
                .map { session -> session?.takeIf { it.kind == SessionKind.SCREEN_OFF }?.let { summaries(listOf(it)).firstOrNull() } }
                .flowOn(dispatchers.io)

        private suspend fun summaries(closed: List<SessionEntity>): List<NightSummary> {
            if (closed.isEmpty()) return emptyList()
            val boots = closed.map { it.bootSeq }.distinct()
            val charges = sessions.inBoots(SessionKind.CHARGE, boots)
            val clocks = snapshots.bootClocks(boots.min(), boots.max()).associateBy { it.bootSeq }
            return closed.mapNotNull { Nights.summary(it, charges, clocks[it.bootSeq], dozeOf(it)) }
        }

        /**
         * Whether deep Doze began in [session], from the history's Doze states (S7): the collector
         * stores none with the session. Null until a history read past the session's end is stored,
         * when the history holds nothing at all for the session, and when it never saw deep Doze but
         * has a gap there, which may hold it (audit DATA-010). Deep Doze seen after a gap was
         * reached, at a time not known.
         */
        private suspend fun dozeOf(session: SessionEntity): DozeReached? {
            val end = session.endElapsedNs
            val known = doze[session.id]
            if (known != null || end == null || session.deepIdleReached != null) return known
            val events = dozeEvents(session, end)
            val deep = events.firstOrNull { it.event.type == HistoryKind.DEVICE_IDLE && it.event.value == DEEP }
            val until = deep?.let { it.event.elapsedMs * NS_PER_MS } ?: end
            val gapped = events.isNotEmpty() && history.gaps(session.bootSeq, session.startElapsedNs, session.bootSeq, until).isNotEmpty()
            val afterMs = deep?.let { it.event.elapsedMs - session.startElapsedNs / NS_PER_MS }?.takeUnless { gapped }
            return when {
                events.isEmpty() || (deep == null && gapped) -> null
                else -> DozeReached(deep != null, afterMs).also { doze[session.id] = it }
            }
        }

        /** The session's Doze, CPU and level events; none until a read past [end] is stored, or when they do not decode. */
        private suspend fun dozeEvents(
            session: SessionEntity,
            end: Long,
        ): List<StoredHistoryEvent> =
            try {
                if (history.readAfter(session.bootSeq, end)) {
                    history.eventsOf(DOZE_KINDS, session.bootSeq, session.startElapsedNs, session.bootSeq, end)
                } else {
                    emptyList()
                }
            } catch (e: CorruptChunkException) {
                Log.w(TAG, "history of session ${session.id} does not decode; its Doze is not known", e)
                emptyList()
            }

        private companion object {
            const val TAG = "NightRepository"

            /** Sixty nights: retention keeps sessions far longer, a list does not need them all. */
            const val MAX_NIGHTS = 60
            const val NS_PER_MS = 1_000_000L
            const val DEEP = 2L

            /** Doze states, and the CPU's and the level's records to tell "no Doze" from "no history". */
            val DOZE_KINDS = setOf(HistoryKind.DEVICE_IDLE, HistoryKind.CPU_RUNNING, HistoryKind.BATTERY_LEVEL)
        }
    }

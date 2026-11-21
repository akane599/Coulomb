// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.SessionKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** One screen-off session of an hour or more, as the Night Report lists it (SPEC 10.7). */
public data class NightSummary(
    /** The session's id; [DrainSpan.Night] reads who used the battery in it. */
    val id: Long,
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
    /** Whether deep Doze was reached; null when the history did not cover the session. */
    val deepDozeReached: Boolean?,
    /** How long into the session deep Doze began. */
    val deepDozeAfterMs: Long?,
)

/** SPEC 10.7's list rows from stored sessions. Pure. */
public object Nights {
    public const val MIN_MS: Long = 3_600_000

    /** ADR-0054 §6: a rate from whole percents needs three of them. */
    public const val MIN_DROP_PCT: Int = 3

    private const val NS_PER_MS = 1_000_000L
    private const val MS_PER_HOUR = 3_600_000.0

    /** [session] as a night, with [charges] the charge sessions that may overlap it; null while it is open or under an hour. */
    public fun summary(
        session: SessionEntity,
        charges: List<SessionEntity>,
    ): NightSummary? {
        val endWall = session.endWall ?: return null
        val endLevel = session.endLevel ?: return null
        val durationMs = ((session.endElapsedNs ?: return null) - session.startElapsedNs) / NS_PER_MS
        if (durationMs < MIN_MS) return null
        val drop = session.startLevel - endLevel
        val charged = drop < 0 || charges.any { it.startWall < endWall && (it.endWall ?: Long.MAX_VALUE) > session.startWall }
        return NightSummary(
            id = session.id,
            startWallMs = session.startWall,
            endWallMs = endWall,
            durationMs = durationMs,
            startLevel = session.startLevel,
            endLevel = endLevel,
            ratePctPerHour = (drop / (durationMs / MS_PER_HOUR)).takeIf { !charged && drop >= MIN_DROP_PCT },
            charged = charged,
            deepSleepShare = session.awakeRatio?.let { (1 - it).coerceIn(0.0, 1.0) },
            deepDozeReached = session.deepIdleReached,
            deepDozeAfterMs = session.deepIdleReachedMs.takeIf { session.deepIdleReached == true },
        )
    }
}

/** The Night Report's sessions (SPEC 10.7): screen-off stretches of an hour or more, newest first. */
@Singleton
public class NightRepository
    @Inject
    internal constructor(
        private val sessions: SessionDao,
        private val dispatchers: AppDispatchers,
    ) {
        /** The latest [MAX_NIGHTS], read again whenever a session closes. */
        public val nights: Flow<List<NightSummary>> =
            sessions
                .observeClosedLasting(SessionKind.SCREEN_OFF, Nights.MIN_MS * NS_PER_MS, MAX_NIGHTS)
                .map { closed -> closed.mapNotNull { Nights.summary(it, charges(closed)) } }
                .flowOn(dispatchers.io)

        /** One night; null when the session is gone or is not a closed screen-off session of an hour. */
        public fun night(id: Long): Flow<NightSummary?> =
            sessions
                .observeSession(id)
                .map { session -> session?.takeIf { it.kind == SessionKind.SCREEN_OFF }?.let { Nights.summary(it, charges(listOf(it))) } }
                .flowOn(dispatchers.io)

        /** The charge sessions that can overlap any of [nights]. */
        private suspend fun charges(nights: List<SessionEntity>): List<SessionEntity> =
            nights.minOfOrNull { it.startWall }?.let { sessions.overlapping(listOf(SessionKind.CHARGE), it) }.orEmpty()

        private companion object {
            /** Sixty nights: retention keeps sessions far longer, a list does not need them all. */
            const val MAX_NIGHTS = 60
            const val NS_PER_MS = 1_000_000L
        }
    }

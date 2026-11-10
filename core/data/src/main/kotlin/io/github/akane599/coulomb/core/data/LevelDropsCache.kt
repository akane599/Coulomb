// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.analytics.LevelChange
import io.github.akane599.coulomb.core.analytics.LevelDrops
import io.github.akane599.coulomb.core.analytics.ScreenRates
import io.github.akane599.coulomb.core.database.dao.StoredLevelChange
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.SessionKind

/**
 * The platform's level drops inside each closed screen-off session (ADR-0049), read once per
 * session: a closed session's history does not change once a read past its end is stored. Sessions
 * not asked for again leave the cache. Not thread-safe: one flow drives it, one value at a time,
 * as [LevelTrailCache].
 */
internal class LevelDropsCache {
    private val known = HashMap<Long, LevelDrops?>()

    /**
     * The drops of [sessions]' closed screen-off ones. [fetch] gives a session's stored level
     * changes, or null while no history read past its end is stored: that session counts from its
     * ends this time and is asked for again.
     */
    suspend fun read(
        sessions: List<SessionEntity>,
        fetch: suspend (SessionEntity) -> List<StoredLevelChange>?,
    ): Map<Long, LevelDrops> {
        val closed = sessions.filter(::timed)
        known.keys.retainAll(closed.mapTo(HashSet()) { it.id })
        val out = HashMap<Long, LevelDrops>()
        for (session in closed) {
            val drops =
                if (known.containsKey(session.id)) {
                    known[session.id]
                } else {
                    fetch(session)?.let { changes -> of(session, changes).also { known[session.id] = it } }
                }
            if (drops != null) out[session.id] = drops
        }
        return out
    }

    fun clear() = known.clear()

    companion object {
        /** A closed screen-off session: the only kind whose rate is timed by the platform's drops. */
        fun timed(session: SessionEntity): Boolean =
            session.kind == SessionKind.SCREEN_OFF && session.closeReason != null && session.endElapsedNs != null &&
                session.endLevel != null

        /** [changes] in [session]'s boot, placed on the wall clock through the session's start. */
        fun of(
            session: SessionEntity,
            changes: List<StoredLevelChange>,
        ): LevelDrops? =
            ScreenRates.drops(
                session.startLevel,
                changes
                    .filter { it.bootSeq == session.bootSeq }
                    .map { LevelChange(session.startWall + it.elapsedMs - session.startElapsedNs / NS_PER_MS, it.level.toInt()) },
            )

        private const val NS_PER_MS = 1_000_000L
    }
}

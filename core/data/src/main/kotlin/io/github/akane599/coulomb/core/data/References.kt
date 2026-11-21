// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.database.dao.SnapshotDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A reference the user named (SPEC 7.6), at a heavy snapshot. */
public data class NamedReference(
    val id: Long,
    val mark: DrainSpan.Mark,
)

/**
 * The user's named references (SPEC 7.6): "Before an update", read since then or between any two
 * in Drain. A reference pins its snapshot, so retention keeps the snapshot's counters (ADR-0015);
 * the consecutive windows Drain reads between two snapshots are kept 30 days (ADR-0021).
 */
@Singleton
public class References
    @Inject
    constructor(
        private val snapshots: SnapshotDao,
    ) {
        public val named: Flow<List<NamedReference>> =
            snapshots
                .observeUserReferences()
                .map { rows -> rows.map { NamedReference(it.id, DrainSpan.Mark(it.snapshotId, it.name.orEmpty(), it.wallMs)) } }
                .distinctUntilChanged()

        /**
         * Names the latest heavy snapshot [name] and returns it; null before any heavy snapshot. The
         * caller asks the collector for one first, so it is at most its debounce old (SPEC 7.2).
         */
        public suspend fun mark(
            name: String,
            createdWall: Long,
        ): NamedReference? {
            val snapshot = snapshots.latestHeavy() ?: return null
            val trimmed = name.trim().take(MAX_NAME)
            val id = snapshots.insertUserReference(trimmed, snapshot.id, createdWall)
            return NamedReference(id, DrainSpan.Mark(snapshot.id, trimmed, snapshot.wallMs))
        }

        /** Deletes a named reference; the automatic ones cannot be deleted. */
        public suspend fun delete(id: Long) {
            snapshots.deleteUserReference(id)
        }

        public companion object {
            /** The longest name kept, as a charger's (ADR-0044 §6). */
            public const val MAX_NAME: Int = 40
        }
    }

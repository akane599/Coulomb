// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.akane599.coulomb.core.database.entity.HistoryEventEntity
import io.github.akane599.coulomb.core.database.entity.HistoryGapEntity
import io.github.akane599.coulomb.core.database.entity.HistoryTagEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind

/** A history event to store, with its tag as text; [HistoryDao.insertRead] stores the tag once. */
public data class NewHistoryEvent(
    val bootSeq: Int,
    val elapsedNs: Long,
    val type: HistoryKind,
    val change: HistoryChange,
    val baseline: Boolean,
    val uid: Int?,
    val tag: String?,
    val value: Long?,
)

/** A stored history event with its tag's text. */
public data class StoredHistoryEvent(
    @Embedded val event: HistoryEventEntity,
    val tag: String?,
)

/**
 * Battery history events and gaps (ADR-0014), a 30-day ring (SPEC 7.7). Times are given as a boot and
 * elapsed time, never as wall time, so clock changes cannot cut the ring in the wrong place.
 *
 * The history cursor and [SnapshotEntity.historyEventId] are stored on the snapshot. The collector
 * writes a read's events, then that snapshot with the id [insertRead] returned, in one transaction
 * (`withTransaction`), so a crash cannot store one without the other. Event times map to id ranges
 * through those snapshots, since `history_event` has no time index (ADR-0015).
 */
@Dao
public abstract class HistoryDao {
    /**
     * Stores one read's events, in the order they happened, and its gaps, adding the tags not
     * stored yet. Returns the highest event id now stored, for [SnapshotEntity.historyEventId].
     */
    @Transaction
    public open suspend fun insertRead(
        events: List<NewHistoryEvent>,
        gaps: List<HistoryGapEntity>,
    ): Long {
        val tags = events.mapNotNullTo(LinkedHashSet()) { it.tag }.toList()
        insertTags(tags.map { HistoryTagEntity(tag = it) })
        val ids = HashMap<String, Long>(tags.size)
        tags.chunked(MAX_IDS).forEach { chunk -> tagIds(chunk).forEach { ids[it.tag] = it.id } }
        insertEvents(
            events.map {
                HistoryEventEntity(
                    0,
                    it.bootSeq,
                    it.elapsedNs,
                    it.type,
                    it.change,
                    it.baseline,
                    it.uid,
                    it.tag?.let(ids::getValue),
                    it.value,
                )
            },
        )
        insertGaps(gaps)
        return highestEventId() ?: 0
    }

    /**
     * Events from `(fromBootSeq, fromElapsedNs)` inclusive to `(toBootSeq, toElapsedNs)` exclusive,
     * in order. The span may cross boots. Only the ids between the snapshots around the span are
     * read: the last one whose read had ended before the span, and the first one taken at or after
     * its end. A read runs after its snapshot's time and may store events up to [READ_END].
     */
    @Query(
        "SELECT e.*, t.tag AS tag FROM history_event e LEFT JOIN history_tag t ON t.id = e.tagId WHERE " +
            "e.id > IFNULL((SELECT historyEventId FROM snapshot WHERE historyEventId IS NOT NULL " +
            "AND (bootSeq < :fromBootSeq OR (bootSeq = :fromBootSeq AND $READ_END < :fromElapsedNs)) " +
            "ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1), 0) " +
            "AND e.id <= IFNULL((SELECT historyEventId FROM snapshot WHERE historyEventId IS NOT NULL " +
            "AND (bootSeq > :toBootSeq OR (bootSeq = :toBootSeq AND elapsedNs >= :toElapsedNs)) " +
            "ORDER BY bootSeq, elapsedNs LIMIT 1), $MAX_ID) " +
            "AND (e.bootSeq > :fromBootSeq OR (e.bootSeq = :fromBootSeq AND e.elapsedNs >= :fromElapsedNs)) " +
            "AND (e.bootSeq < :toBootSeq OR (e.bootSeq = :toBootSeq AND e.elapsedNs < :toElapsedNs)) " +
            "ORDER BY e.id",
    )
    public abstract suspend fun events(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<StoredHistoryEvent>

    /** Gaps that overlap the span of [events]. */
    @Query(
        "SELECT * FROM history_gap WHERE " +
            "(startBootSeq < :toBootSeq OR (startBootSeq = :toBootSeq AND startElapsedNs < :toElapsedNs)) " +
            "AND (endBootSeq > :fromBootSeq OR (endBootSeq = :fromBootSeq AND endElapsedNs > :fromElapsedNs)) " +
            "ORDER BY startMonotonicMs, id",
    )
    public abstract suspend fun gaps(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<HistoryGapEntity>

    /**
     * Drops the events stored by snapshots before `(bootSeq, elapsedNs)`, the gaps that end before
     * it, and the tags no event uses. Events read after the last such snapshot stay until a later
     * cut, and so does a gap that straddles the cut.
     */
    @Transaction
    public open suspend fun deleteBefore(
        bootSeq: Int,
        elapsedNs: Long,
    ) {
        deleteEventsBefore(bootSeq, elapsedNs)
        deleteGapsBefore(bootSeq, elapsedNs)
        deleteUnusedTags()
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTags(tags: List<HistoryTagEntity>)

    @Query("SELECT * FROM history_tag WHERE tag IN (:tags)")
    protected abstract suspend fun tagIds(tags: List<String>): List<HistoryTagEntity>

    @Insert
    protected abstract suspend fun insertEvents(events: List<HistoryEventEntity>)

    @Insert
    protected abstract suspend fun insertGaps(gaps: List<HistoryGapEntity>)

    @Query("SELECT MAX(id) FROM history_event")
    protected abstract suspend fun highestEventId(): Long?

    @Query(
        "DELETE FROM history_event WHERE id <= IFNULL((SELECT historyEventId FROM snapshot " +
            "WHERE historyEventId IS NOT NULL AND (bootSeq < :bootSeq OR (bootSeq = :bootSeq AND $READ_END < :elapsedNs)) " +
            "ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1), 0)",
    )
    protected abstract suspend fun deleteEventsBefore(
        bootSeq: Int,
        elapsedNs: Long,
    )

    @Query(
        "DELETE FROM history_gap WHERE endBootSeq < :bootSeq " +
            "OR (endBootSeq = :bootSeq AND endElapsedNs <= :elapsedNs)",
    )
    protected abstract suspend fun deleteGapsBefore(
        bootSeq: Int,
        elapsedNs: Long,
    )

    /** `tagId` has no index; `NOT IN` reads the events once rather than once per tag. */
    @Query("DELETE FROM history_tag WHERE id NOT IN (SELECT tagId FROM history_event WHERE tagId IS NOT NULL)")
    protected abstract suspend fun deleteUnusedTags()

    private companion object {
        const val MAX_IDS = 500
        const val MAX_ID = "9223372036854775807"

        /** When a snapshot's history read had ended, at the latest: its time plus its duration. */
        const val READ_END = "(elapsedNs + durationMs * 1000000)"
    }
}

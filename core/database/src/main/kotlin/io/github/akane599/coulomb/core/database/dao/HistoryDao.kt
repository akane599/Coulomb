// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.TypeConverters
import io.github.akane599.coulomb.core.database.entity.HistoryCodes
import io.github.akane599.coulomb.core.database.entity.HistoryEventEntity
import io.github.akane599.coulomb.core.database.entity.HistoryGapEntity
import io.github.akane599.coulomb.core.database.entity.HistoryTagEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind

/**
 * A history event to store, with its tag as text; [HistoryDao.insertRead] stores the tag once.
 * [elapsedMs] is in milliseconds, as the history clock counts.
 */
public data class NewHistoryEvent(
    val bootSeq: Int,
    val elapsedMs: Long,
    val type: HistoryKind,
    val change: HistoryChange,
    val baseline: Boolean,
    val uid: Int?,
    val tag: String?,
    val value: Long?,
)

/** A battery level the platform's history recorded: [level] percent at [elapsedMs] in boot [bootSeq]. */
public data class StoredLevelChange(
    val bootSeq: Int,
    val elapsedMs: Long,
    val level: Long,
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
                    it.elapsedMs,
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
     * its end. A read runs after its snapshot's time and may store events up to [READ_END]. The
     * span is in nanoseconds, like every other elapsed time; events are compared at [EVENT_NS].
     */
    @Query(
        "SELECT e.*, t.tag AS tag FROM history_event e LEFT JOIN history_tag t ON t.id = e.tagId WHERE " +
            "e.id > IFNULL((SELECT historyEventId FROM snapshot WHERE historyEventId IS NOT NULL " +
            "AND (bootSeq < :fromBootSeq OR (bootSeq = :fromBootSeq AND $READ_END < :fromElapsedNs)) " +
            "ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1), 0) " +
            "AND e.id <= IFNULL((SELECT historyEventId FROM snapshot WHERE historyEventId IS NOT NULL " +
            "AND (bootSeq > :toBootSeq OR (bootSeq = :toBootSeq AND elapsedNs >= :toElapsedNs)) " +
            "ORDER BY bootSeq, elapsedNs LIMIT 1), $MAX_ID) " +
            "AND (e.bootSeq > :fromBootSeq OR (e.bootSeq = :fromBootSeq AND $EVENT_NS >= :fromElapsedNs)) " +
            "AND (e.bootSeq < :toBootSeq OR (e.bootSeq = :toBootSeq AND $EVENT_NS < :toElapsedNs)) " +
            "ORDER BY e.id",
    )
    public abstract suspend fun events(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<StoredHistoryEvent>

    /**
     * The battery level changes over the span of [events], read through the same id range:
     * `BATTERY_LEVEL` events that are not restated at a read's start (ADR-0014), in order. A closed
     * screen-off session's rate is timed by them (ADR-0049).
     */
    public open suspend fun levelChanges(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<StoredLevelChange> = changesOf(HistoryKind.BATTERY_LEVEL, fromBootSeq, fromElapsedNs, toBootSeq, toElapsedNs)

    /**
     * Whether a snapshot at or after `(bootSeq, elapsedNs)` has stored its history read: until one
     * has, the events up to that time may still be on their way, and [levelChanges] can come back
     * short.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM snapshot WHERE historyEventId IS NOT NULL " +
            "AND (bootSeq > :bootSeq OR (bootSeq = :bootSeq AND elapsedNs >= :elapsedNs)))",
    )
    public abstract suspend fun readAfter(
        bootSeq: Int,
        elapsedNs: Long,
    ): Boolean

    @TypeConverters(HistoryCodes::class)
    @Query(
        "SELECT e.bootSeq AS bootSeq, e.elapsedMs AS elapsedMs, e.value AS level FROM history_event e WHERE " +
            "e.id > IFNULL((SELECT historyEventId FROM snapshot WHERE historyEventId IS NOT NULL " +
            "AND (bootSeq < :fromBootSeq OR (bootSeq = :fromBootSeq AND $READ_END < :fromElapsedNs)) " +
            "ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1), 0) " +
            "AND e.id <= IFNULL((SELECT historyEventId FROM snapshot WHERE historyEventId IS NOT NULL " +
            "AND (bootSeq > :toBootSeq OR (bootSeq = :toBootSeq AND elapsedNs >= :toElapsedNs)) " +
            "ORDER BY bootSeq, elapsedNs LIMIT 1), $MAX_ID) " +
            "AND (e.bootSeq > :fromBootSeq OR (e.bootSeq = :fromBootSeq AND $EVENT_NS >= :fromElapsedNs)) " +
            "AND (e.bootSeq < :toBootSeq OR (e.bootSeq = :toBootSeq AND $EVENT_NS < :toElapsedNs)) " +
            "AND e.type = :type AND e.baseline = 0 AND e.value IS NOT NULL " +
            "ORDER BY e.id",
    )
    protected abstract suspend fun changesOf(
        type: HistoryKind,
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<StoredLevelChange>

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

    /**
     * Deletes the tags no history or kernel log event uses. [deleteBefore] runs it; retention also
     * runs it after the S27 ring alone, before the database is old enough for a history cut.
     * `tagId` has no index; `NOT IN` reads the events once rather than once per tag.
     */
    @Query(
        "DELETE FROM history_tag WHERE id NOT IN (SELECT tagId FROM history_event WHERE tagId IS NOT NULL) " +
            "AND id NOT IN (SELECT tagId FROM kernel_log_event WHERE tagId IS NOT NULL)",
    )
    public abstract suspend fun deleteUnusedTags()

    private companion object {
        const val MAX_IDS = 500
        const val MAX_ID = "9223372036854775807"

        /** When a snapshot's history read had ended, at the latest: its time plus its duration. */
        const val READ_END = "(elapsedNs + durationMs * 1000000)"

        /** An event's elapsed time in nanoseconds; exact, since events are stored in whole milliseconds. */
        const val EVENT_NS = "(e.elapsedMs * 1000000)"
    }
}

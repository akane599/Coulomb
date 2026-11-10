// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.akane599.coulomb.core.database.entity.HistoryChunkCodec
import io.github.akane599.coulomb.core.database.entity.HistoryChunkEntity
import io.github.akane599.coulomb.core.database.entity.HistoryCodes
import io.github.akane599.coulomb.core.database.entity.HistoryCounterEntity
import io.github.akane599.coulomb.core.database.entity.HistoryEventRow
import io.github.akane599.coulomb.core.database.entity.HistoryGapEntity
import io.github.akane599.coulomb.core.database.entity.HistoryTagEntity
import io.github.akane599.coulomb.core.database.entity.PackedEvent
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

/**
 * A chunk read for a span, with the ids the span covers: those above [afterId], up to [upToId]. Only
 * [HistoryDao] reads it; it is public because the DAO's generated code maps it.
 */
public class SpanChunk(
    @Embedded public val chunk: HistoryChunkEntity,
    public val afterId: Long,
    public val upToId: Long,
)

/** A stored history event with its tag's text. */
public data class StoredHistoryEvent(
    val event: HistoryEventRow,
    val tag: String?,
)

/**
 * Battery history events and gaps (ADR-0014), a 30-day ring (SPEC 7.7). Times are given as a boot and
 * elapsed time, never as wall time, so clock changes cannot cut the ring in the wrong place.
 *
 * Events are stored packed, a read at a time ([HistoryChunkEntity], ADR-0051), and decoded here:
 * no query reads a single event. Every event still has an id, counted on by `history_counter`.
 *
 * The history cursor and [SnapshotEntity.historyEventId] are stored on the snapshot. The collector
 * writes a read's events, then that snapshot with the id [insertRead] returned, in one transaction
 * (`withTransaction`), so a crash cannot store one without the other. Event times map to id ranges
 * through those snapshots, and an id range to the chunks that hold it.
 */
@Dao
public abstract class HistoryDao {
    /**
     * Stores one read's events, in the order they happened, and its gaps, adding the tags not
     * stored yet. Returns the id of the last event stored so far, for
     * [SnapshotEntity.historyEventId]; a read without events returns the one before it.
     */
    @Transaction
    public open suspend fun insertRead(
        events: List<NewHistoryEvent>,
        gaps: List<HistoryGapEntity>,
    ): Long {
        val firstId = nextEventId() ?: FIRST_ID
        val lastId = firstId + events.size - 1
        if (events.isNotEmpty()) {
            val tags = events.mapNotNullTo(LinkedHashSet()) { it.tag }.toList()
            insertTags(tags.map { HistoryTagEntity(tag = it) })
            val ids = HashMap<String, Long>(tags.size)
            tags.chunked(MAX_IDS).forEach { chunk -> tagIds(chunk).forEach { ids[it.tag] = it.id } }
            events.chunked(HistoryChunkCodec.MAX_EVENTS).forEachIndexed { index, part ->
                val packed =
                    part.map {
                        PackedEvent(
                            it.bootSeq,
                            it.elapsedMs,
                            HistoryCodes.kindCode(it.type),
                            HistoryCodes.changeCode(it.change),
                            it.baseline,
                            it.uid,
                            it.tag?.let(ids::getValue),
                            it.value,
                        )
                    }
                insertChunk(
                    HistoryChunkEntity(
                        firstId + index.toLong() * HistoryChunkCodec.MAX_EVENTS,
                        part.size,
                        HistoryChunkCodec.encode(packed),
                    ),
                )
            }
            ids.values.chunked(MAX_IDS).forEach { markTagsUsed(it, lastId) }
            setCounter(HistoryCounterEntity(nextEventId = lastId + 1))
        }
        insertGaps(gaps)
        return lastId
    }

    /**
     * Events from `(fromBootSeq, fromElapsedNs)` inclusive to `(toBootSeq, toElapsedNs)` exclusive,
     * in order. The span may cross boots. Only the ids between the snapshots around the span are
     * read: the last one whose read had ended before the span, and the first one taken at or after
     * its end. A read runs after its snapshot's time and may store events up to [READ_END]. The
     * span is in nanoseconds, like every other elapsed time; events are in whole milliseconds.
     * The transaction keeps the tags of the events read: a cut between the two reads would drop them.
     *
     * @throws io.github.akane599.coulomb.core.database.entity.CorruptChunkException when a stored
     *   chunk does not decode.
     */
    @Transaction
    public open suspend fun events(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<StoredHistoryEvent> {
        val rows = stored(fromBootSeq, fromElapsedNs, toBootSeq, toElapsedNs) { true }
        val tags = HashMap<Long, String>()
        rows.mapNotNullTo(HashSet()) { it.tagId }.chunked(MAX_IDS).forEach { chunk -> tagsById(chunk).forEach { tags[it.id] = it.tag } }
        return rows.map { StoredHistoryEvent(it, it.tagId?.let(tags::get)) }
    }

    /**
     * The battery level changes over the span of [events], read through the same id range:
     * `BATTERY_LEVEL` events that are not restated at a read's start (ADR-0014), in order. A closed
     * screen-off session's rate is timed by them (ADR-0049). One query reads the marks and the
     * chunks together, so it needs no transaction and leaves the writer to the collector.
     *
     * @throws io.github.akane599.coulomb.core.database.entity.CorruptChunkException when a stored
     *   chunk does not decode.
     */
    public open suspend fun levelChanges(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<StoredLevelChange> =
        stored(fromBootSeq, fromElapsedNs, toBootSeq, toElapsedNs) { it.typeCode == LEVEL_CODE && !it.baseline && it.value != null }
            .map { StoredLevelChange(it.bootSeq, it.elapsedMs, checkNotNull(it.value)) }

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
     * cut, and so does a gap that straddles the cut. Whole chunks go: a snapshot's mark is the end
     * of a read, which is the end of a chunk, and a chunk that reaches past the mark stays whole.
     */
    @Transaction
    public open suspend fun deleteBefore(
        bootSeq: Int,
        elapsedNs: Long,
    ) {
        deleteChunksThrough(markBefore(bootSeq, elapsedNs) ?: 0)
        deleteGapsBefore(bootSeq, elapsedNs)
        deleteUnusedTags()
    }

    /**
     * Deletes the tags no history or kernel log event uses. [deleteBefore] runs it; retention also
     * runs it after the S27 ring alone, before the database is old enough for a history cut.
     *
     * A tag is out of the history once its last event is older than the ring's oldest
     * ([HistoryTagEntity.lastEventId]); with no chunk left, every event is older than the next id.
     * `kernel_log_event.tagId` has no index; `NOT IN` reads those events once rather than once per tag.
     */
    @Query(
        "DELETE FROM history_tag WHERE lastEventId < IFNULL((SELECT MIN(firstId) FROM history_chunk), " +
            "IFNULL((SELECT nextEventId FROM history_counter WHERE id = ${HistoryCounterEntity.ID}), $FIRST_ID)) " +
            "AND id NOT IN (SELECT tagId FROM kernel_log_event WHERE tagId IS NOT NULL)",
    )
    public abstract suspend fun deleteUnusedTags()

    /**
     * The events of [events]' span that [keep] accepts, decoded from the chunks that hold the
     * span's ids. [keep] sees an event before its codes are turned into enums, so a filter costs
     * no lookup for the events it drops.
     */
    private suspend fun stored(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
        keep: (PackedEvent) -> Boolean,
    ): List<HistoryEventRow> {
        val out = ArrayList<HistoryEventRow>()
        for (span in chunks(fromBootSeq, fromElapsedNs, toBootSeq, toElapsedNs)) {
            val chunk = span.chunk
            HistoryChunkCodec.decode(chunk.count, chunk.data).forEachIndexed { index, event ->
                val id = chunk.firstId + index
                val inSpan =
                    id > span.afterId &&
                        id <= span.upToId &&
                        notBefore(event, fromBootSeq, fromElapsedNs) &&
                        before(event, toBootSeq, toElapsedNs)
                if (inSpan && keep(event)) {
                    out +=
                        HistoryEventRow(
                            id,
                            event.bootSeq,
                            event.elapsedMs,
                            HistoryCodes.kind(event.typeCode),
                            HistoryCodes.change(event.changeCode),
                            event.baseline,
                            event.uid,
                            event.tagId,
                            event.value,
                        )
                }
            }
        }
        return out
    }

    private fun notBefore(
        event: PackedEvent,
        bootSeq: Int,
        elapsedNs: Long,
    ): Boolean = event.bootSeq > bootSeq || (event.bootSeq == bootSeq && !msBefore(event.elapsedMs, elapsedNs))

    private fun before(
        event: PackedEvent,
        bootSeq: Int,
        elapsedNs: Long,
    ): Boolean = event.bootSeq < bootSeq || (event.bootSeq == bootSeq && msBefore(event.elapsedMs, elapsedNs))

    /**
     * Whether [elapsedMs] is before [elapsedNs]. Compared in milliseconds, so no time can overflow:
     * a whole millisecond is before a nanosecond time exactly when it is below that time rounded up.
     */
    private fun msBefore(
        elapsedMs: Long,
        elapsedNs: Long,
    ): Boolean = elapsedMs < Math.floorDiv(elapsedNs, NS_PER_MS) + if (Math.floorMod(elapsedNs, NS_PER_MS) != 0L) 1 else 0

    /** The mark of the last snapshot whose history read had ended before `(bootSeq, elapsedNs)`. */
    @Query(
        "SELECT historyEventId FROM snapshot WHERE historyEventId IS NOT NULL " +
            "AND (bootSeq < :bootSeq OR (bootSeq = :bootSeq AND $READ_END < :elapsedNs)) " +
            "ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1",
    )
    protected abstract suspend fun markBefore(
        bootSeq: Int,
        elapsedNs: Long,
    ): Long?

    /**
     * The chunks that hold an event of the span's ids, in order, each with the ids it is read
     * between: above the mark of the last snapshot whose read had ended before the span's start
     * ([markBefore]'s), up to the mark of the first snapshot taken at or after its end. The first
     * chunk is the last one that starts at or below `afterId + 1`; both ends use the key.
     */
    @Query(
        "WITH marks(afterId, upToId) AS (SELECT " +
            "IFNULL((SELECT historyEventId FROM snapshot WHERE historyEventId IS NOT NULL " +
            "AND (bootSeq < :fromBootSeq OR (bootSeq = :fromBootSeq AND $READ_END < :fromElapsedNs)) " +
            "ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1), 0), " +
            "IFNULL((SELECT historyEventId FROM snapshot WHERE historyEventId IS NOT NULL " +
            "AND (bootSeq > :toBootSeq OR (bootSeq = :toBootSeq AND elapsedNs >= :toElapsedNs)) " +
            "ORDER BY bootSeq, elapsedNs LIMIT 1), ${Long.MAX_VALUE})), " +
            "span(afterId, upToId, startId) AS (SELECT afterId, upToId, " +
            "IFNULL((SELECT MAX(firstId) FROM history_chunk WHERE firstId <= afterId + 1), 0) FROM marks) " +
            "SELECT history_chunk.*, afterId, upToId FROM span JOIN history_chunk " +
            "ON firstId BETWEEN startId AND upToId WHERE firstId + count - 1 > afterId ORDER BY firstId",
    )
    protected abstract suspend fun chunks(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<SpanChunk>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTags(tags: List<HistoryTagEntity>)

    @Query("SELECT * FROM history_tag WHERE tag IN (:tags)")
    protected abstract suspend fun tagIds(tags: List<String>): List<HistoryTagEntity>

    @Query("SELECT * FROM history_tag WHERE id IN (:ids)")
    protected abstract suspend fun tagsById(ids: List<Long>): List<HistoryTagEntity>

    @Query("UPDATE history_tag SET lastEventId = :lastEventId WHERE id IN (:ids)")
    protected abstract suspend fun markTagsUsed(
        ids: List<Long>,
        lastEventId: Long,
    )

    @Insert
    protected abstract suspend fun insertChunk(chunk: HistoryChunkEntity)

    @Insert
    protected abstract suspend fun insertGaps(gaps: List<HistoryGapEntity>)

    @Query("SELECT nextEventId FROM history_counter WHERE id = ${HistoryCounterEntity.ID}")
    protected abstract suspend fun nextEventId(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun setCounter(counter: HistoryCounterEntity)

    /** Whole chunks only: one that reaches past [mark] keeps the events below it too. */
    @Query("DELETE FROM history_chunk WHERE firstId <= :mark AND firstId + count - 1 <= :mark")
    protected abstract suspend fun deleteChunksThrough(mark: Long)

    @Query(
        "DELETE FROM history_gap WHERE endBootSeq < :bootSeq " +
            "OR (endBootSeq = :bootSeq AND endElapsedNs <= :elapsedNs)",
    )
    protected abstract suspend fun deleteGapsBefore(
        bootSeq: Int,
        elapsedNs: Long,
    )

    private companion object {
        const val MAX_IDS = 500

        /** The first event's id, as `AUTOINCREMENT` gave it before the counter. */
        const val FIRST_ID = 1L

        const val NS_PER_MS = 1_000_000L

        /** When a snapshot's history read had ended, at the latest: its time plus its duration. */
        const val READ_END = "(elapsedNs + durationMs * 1000000)"

        val LEVEL_CODE = HistoryCodes.kindCode(HistoryKind.BATTERY_LEVEL)
    }
}

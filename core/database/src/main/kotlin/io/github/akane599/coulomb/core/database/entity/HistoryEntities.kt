// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.akane599.coulomb.core.model.HistoryGapReason
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind

/**
 * SPEC 8 `HistoryChunk` (ADR-0051): history events in the order they happened, packed by
 * [HistoryChunkCodec], kept 30 days (SPEC 7.7). Event `i` of a chunk has the id `firstId + i`, so
 * ids run on from chunk to chunk in time order, and [SnapshotEntity.historyEventId] marks which
 * ids each snapshot's read had stored: a time span maps to an id range, and that to a few chunks.
 * A read is stored as one chunk, or several when it holds more than
 * [HistoryChunkCodec.MAX_EVENTS] events.
 *
 * Not a data class: [data] is an array, and nothing compares chunks.
 */
@Entity(tableName = "history_chunk")
public class HistoryChunkEntity(
    /** The id of the chunk's first event. */
    @PrimaryKey public val firstId: Long,
    public val count: Int,
    public val data: ByteArray,
)

/**
 * The id the next history event gets, in the one row [ID]. The ring deletes whole chunks, so the
 * highest stored id cannot say what comes next: an emptied ring would hand out ids at or below
 * the snapshots' marks again (ADR-0021's defect).
 */
@Entity(tableName = "history_counter")
public data class HistoryCounterEntity(
    @PrimaryKey val id: Int = ID,
    val nextEventId: Long,
) {
    public companion object {
        public const val ID: Int = 0
    }
}

/**
 * One history event as it is read back. [type], [change] and [baseline] are the parsed record's
 * (ADR-0014): a [baseline] START restates a state that was already on. [elapsedMs] is mapped from
 * the platform's history clock with [BootEntity.historyOffsetMs], which also maps it back. It is in
 * milliseconds, the history clock's own unit. [tagId] is a [HistoryTagEntity] id.
 */
public data class HistoryEventRow(
    val id: Long,
    val bootSeq: Int,
    val elapsedMs: Long,
    val type: HistoryKind,
    val change: HistoryChange,
    val baseline: Boolean,
    val uid: Int?,
    val tagId: Long?,
    val value: Long?,
)

/**
 * A history tag stored once (ADR-0015): wakelock, job and wake reason names repeat thousands of
 * times. [lastEventId] is the id of the last history event stored with the tag, 0 for none: once
 * the ring's oldest event is newer, no history event uses the tag (ADR-0051). Kernel log events
 * share the table and are checked by their own rows.
 */
@Entity(tableName = "history_tag", indices = [Index("tag", unique = true)])
public data class HistoryTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tag: String,
    @ColumnInfo(defaultValue = "0") val lastEventId: Long = 0,
)

/**
 * A stretch of history not known event by event (ADR-0014, ADR-0015). Analyses that need every
 * event, such as wakeup counts, treat the stretch as unknown rather than quiet. A gap is found in
 * the history clock and can span a reboot, so it keeps that clock's times as read and both ends'
 * boots.
 */
@Entity(tableName = "history_gap", indices = [Index("endBootSeq", "endElapsedNs")])
public data class HistoryGapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMonotonicMs: Long,
    val endMonotonicMs: Long,
    val startBootSeq: Int,
    val startElapsedNs: Long,
    val endBootSeq: Int,
    val endElapsedNs: Long,
    val reason: HistoryGapReason,
)

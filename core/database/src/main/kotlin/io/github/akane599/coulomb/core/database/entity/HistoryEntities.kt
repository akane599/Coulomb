// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import io.github.akane599.coulomb.core.model.HistoryGapReason
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind

/**
 * SPEC 8 `HistoryEvent`, kept 30 days (SPEC 7.7). [type], [change] and [baseline] are the parsed
 * record's (ADR-0014): a [baseline] START restates a state that was already on. [elapsedMs] is
 * mapped from the platform's history clock with [BootEntity.historyOffsetMs], which also maps it
 * back. It is in milliseconds, the history clock's own unit, so it takes fewer bytes (ADR-0021).
 *
 * The table is kept small (ADR-0015): [type] and [change] are stored as [HistoryCodes]; [tagId] is
 * a [HistoryTagEntity] id without a foreign key; and there is no time index. Events are written in
 * the order they happened, so [id] order is time order, and [SnapshotEntity.historyEventId] marks
 * which ids each snapshot's read had stored: a time span maps to an id range.
 */
@Entity(tableName = "history_event")
@TypeConverters(HistoryCodes::class)
public data class HistoryEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bootSeq: Int,
    val elapsedMs: Long,
    val type: HistoryKind,
    val change: HistoryChange,
    val baseline: Boolean,
    val uid: Int?,
    val tagId: Long?,
    val value: Long?,
)

/** A history tag stored once (ADR-0015): wakelock, job and wake reason names repeat thousands of times. */
@Entity(tableName = "history_tag", indices = [Index("tag", unique = true)])
public data class HistoryTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tag: String,
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

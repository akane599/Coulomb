// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowStatEntity
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.database.entity.WindowWakeEntity
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.WindowKind
import kotlinx.coroutines.flow.Flow

/** The per-UID records of one window. Their `windowId` is ignored on insert. */
public data class WindowRows(
    val uids: List<WindowUidEntity> = emptyList(),
    val wakes: List<WindowWakeEntity> = emptyList(),
    val stats: List<WindowStatEntity> = emptyList(),
)

/** Drain windows and their per-UID records (SPEC 6.2, 6.5). */
@Dao
public abstract class WindowDao {
    /** Stores [window] and its [rows] in one transaction and returns the window's id. */
    @Transaction
    public open suspend fun insert(
        window: WindowResultEntity,
        rows: WindowRows = WindowRows(),
    ): Long {
        val id = insertWindow(window)
        insertUids(rows.uids.map { it.copy(windowId = id) })
        insertWakes(rows.wakes.map { it.copy(windowId = id) })
        insertStats(rows.stats.map { it.copy(windowId = id) })
        return id
    }

    @Query("SELECT * FROM window_result WHERE id = :id")
    public abstract suspend fun window(id: Long): WindowResultEntity?

    /** The window's records; empty once [WindowResultEntity.detailPruned] is set. */
    @Transaction
    public open suspend fun rows(windowId: Long): WindowRows = WindowRows(uids(windowId), wakes(windowId), stats(windowId))

    @Query("SELECT * FROM window_result WHERE sessionId = :sessionId ORDER BY id DESC LIMIT 1")
    public abstract suspend fun forSession(sessionId: Long): WindowResultEntity?

    /**
     * Windows of [kind] that end after `(fromBootSeq, fromElapsedNs)` and no later than
     * `(toBootSeq, toElapsedNs)`, in time order. Consecutive windows chain, so they add up to any
     * span between two heavy snapshots.
     */
    @Query(
        "SELECT w.* FROM window_result w JOIN snapshot s ON s.id = w.toSnapshotId WHERE w.kind = :kind " +
            "AND (s.bootSeq > :fromBootSeq OR (s.bootSeq = :fromBootSeq AND s.elapsedNs > :fromElapsedNs)) " +
            "AND (s.bootSeq < :toBootSeq OR (s.bootSeq = :toBootSeq AND s.elapsedNs <= :toElapsedNs)) " +
            "ORDER BY s.bootSeq, s.elapsedNs",
    )
    public abstract suspend fun windowsEndingIn(
        kind: WindowKind,
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<WindowResultEntity>

    /** The latest [limit] windows of [kind], newest first. */
    @Query(
        "SELECT w.* FROM window_result w JOIN snapshot s ON s.id = w.toSnapshotId WHERE w.kind = :kind " +
            "ORDER BY s.bootSeq DESC, s.elapsedNs DESC LIMIT :limit",
    )
    public abstract fun observeRecent(
        kind: WindowKind,
        limit: Int,
    ): Flow<List<WindowResultEntity>>

    /** One UID's shares across the windows that still have them, for app detail (SPEC 10). */
    @Query("SELECT * FROM window_uid WHERE uid = :uid ORDER BY windowId")
    public abstract fun observeForUid(uid: Int): Flow<List<WindowUidEntity>>

    /** Windows of [kinds] that end before the cut and still have their records. */
    @Query(
        "SELECT w.id FROM window_result w JOIN snapshot s ON s.id = w.toSnapshotId " +
            "WHERE w.detailPruned = 0 AND w.kind IN (:kinds) AND $TO_BEFORE_CUT",
    )
    public abstract suspend fun detailBefore(
        kinds: List<WindowKind>,
        cutBootSeq: Int,
        cutElapsedNs: Long,
    ): List<Long>

    /** As [detailBefore], for the session windows of [sessionKinds]. */
    @Query(
        "SELECT w.id FROM window_result w JOIN snapshot s ON s.id = w.toSnapshotId " +
            "JOIN session x ON x.id = w.sessionId " +
            "WHERE w.detailPruned = 0 AND w.kind = 'SESSION' AND x.kind IN (:sessionKinds) AND $TO_BEFORE_CUT",
    )
    public abstract suspend fun sessionDetailBefore(
        sessionKinds: List<SessionKind>,
        cutBootSeq: Int,
        cutElapsedNs: Long,
    ): List<Long>

    /**
     * Retention (ADR-0015): deletes the records of [windowIds] and marks them
     * [WindowResultEntity.detailPruned]. The window rows stay.
     */
    @Transaction
    public open suspend fun pruneDetail(windowIds: List<Long>) {
        windowIds.chunked(MAX_IDS).forEach { chunk ->
            deleteUids(chunk)
            deleteWakes(chunk)
            deleteStats(chunk)
            markDetailPruned(chunk)
        }
    }

    @Insert
    protected abstract suspend fun insertWindow(window: WindowResultEntity): Long

    @Insert
    protected abstract suspend fun insertUids(rows: List<WindowUidEntity>)

    @Insert
    protected abstract suspend fun insertWakes(rows: List<WindowWakeEntity>)

    @Insert
    protected abstract suspend fun insertStats(rows: List<WindowStatEntity>)

    @Query("SELECT * FROM window_uid WHERE windowId = :windowId")
    protected abstract suspend fun uids(windowId: Long): List<WindowUidEntity>

    @Query("SELECT * FROM window_wake WHERE windowId = :windowId ORDER BY id")
    protected abstract suspend fun wakes(windowId: Long): List<WindowWakeEntity>

    @Query("SELECT * FROM window_stat WHERE windowId = :windowId")
    protected abstract suspend fun stats(windowId: Long): List<WindowStatEntity>

    @Query("DELETE FROM window_uid WHERE windowId IN (:ids)")
    protected abstract suspend fun deleteUids(ids: List<Long>)

    @Query("DELETE FROM window_wake WHERE windowId IN (:ids)")
    protected abstract suspend fun deleteWakes(ids: List<Long>)

    @Query("DELETE FROM window_stat WHERE windowId IN (:ids)")
    protected abstract suspend fun deleteStats(ids: List<Long>)

    @Query("UPDATE window_result SET detailPruned = 1 WHERE id IN (:ids)")
    protected abstract suspend fun markDetailPruned(ids: List<Long>)

    private companion object {
        /** The window's end snapshot `s` is keyed before `(:cutBootSeq, :cutElapsedNs)`. */
        const val TO_BEFORE_CUT =
            "(s.bootSeq < :cutBootSeq OR (s.bootSeq = :cutBootSeq AND s.elapsedNs < :cutElapsedNs))"

        const val MAX_IDS = 500
    }
}

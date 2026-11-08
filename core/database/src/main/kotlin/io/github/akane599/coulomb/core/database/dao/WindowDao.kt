// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.TypeConverters
import io.github.akane599.coulomb.core.database.entity.DetailCodes
import io.github.akane599.coulomb.core.database.entity.StatKeyEntity
import io.github.akane599.coulomb.core.database.entity.WakeTagEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowStatEntity
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.database.entity.WindowWakeEntity
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import kotlinx.coroutines.flow.Flow

/** A [WindowWakeEntity] with its tag as text. */
public data class WindowWake(
    val uid: Int?,
    val tag: String,
    val kind: WakeKind,
    val totalMs: Long,
    val count: Int,
    val screenOffMs: Long?,
    val wakeups: Long? = null,
)

/** A [WindowStatEntity] with its key as text. */
@TypeConverters(DetailCodes::class)
public data class WindowStat(
    val uid: Int,
    val key: String,
    val value: Long,
    val provenance: Provenance,
)

/**
 * The sums over a chain of CONSECUTIVE windows (ADR-0036). Consecutive windows split at every plug
 * and screen change that took a heavy snapshot, so a window that started on battery is a discharge.
 */
public data class ChainTotals(
    val windows: Int,
    /** Drain summed over the windows that started on battery; null when none had a measured value. */
    val dischargeMah: Double?,
    val dischargeWindows: Int,
    /** Of [dischargeWindows], those measured by the charge counter (Measured); the rest are Estimated. */
    val counterWindows: Int,
    /** Of [dischargeWindows], those with no measured value: the sum leaves them out. */
    val unmeasuredWindows: Int,
    /** Windows that started plugged in; their counter change is charge, not drain. */
    val pluggedWindows: Int,
    /** Windows whose start snapshot has no plug state. */
    val unknownWindows: Int,
    val modeledMah: Double?,
    /** Windows without a modeled total; the sum leaves them out. */
    val unmodeledWindows: Int,
    val screenOnMs: Long,
    val screenOffMs: Long,
)

/** One UID's modeled mAh for one component, summed over a chain. */
public data class ChainUid(
    val uid: Int,
    val component: String,
    val mAh: Double,
)

/**
 * One wakelock, kernel source or wakeup reason summed over a chain. [screenOffMs] and [screenOnMs]
 * add its time in windows that were wholly screen-off or wholly screen-on; time in a window with
 * both is in neither.
 */
public data class ChainWake(
    val uid: Int?,
    val tag: String,
    val kind: WakeKind,
    val totalMs: Long,
    val count: Long,
    val screenOffMs: Long,
    val screenOnMs: Long,
    val wakeups: Long?,
)

/** One UID's counter change summed over a chain. */
public data class ChainStat(
    val uid: Int,
    val value: Long,
)

/** The per-UID records of one window. The uid rows' `windowId` is ignored on insert. */
public data class WindowRows(
    val uids: List<WindowUidEntity> = emptyList(),
    val wakes: List<WindowWake> = emptyList(),
    val stats: List<WindowStat> = emptyList(),
)

/**
 * Drain windows and their per-UID records (SPEC 6.2, 6.5). Stat keys and wake tags are stored once
 * in `stat_key` and `wake_tag` (ADR-0021); this DAO reads and writes them as text.
 */
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
        if (rows.wakes.isNotEmpty()) {
            val tags = rows.wakes.mapTo(LinkedHashSet()) { it.tag }.toList()
            insertWakeTags(tags.map { WakeTagEntity(tag = it) })
            val tagIds = HashMap<String, Long>(tags.size)
            tags.chunked(MAX_IDS).forEach { chunk -> wakeTags(chunk).forEach { tagIds[it.tag] = it.id } }
            insertWakes(
                rows.wakes.map {
                    WindowWakeEntity(0, id, it.uid, tagIds.getValue(it.tag), it.kind, it.totalMs, it.count, it.screenOffMs, it.wakeups)
                },
            )
        }
        if (rows.stats.isNotEmpty()) {
            val keys = rows.stats.mapTo(LinkedHashSet()) { it.key }.toList()
            insertStatKeys(keys.map { StatKeyEntity(name = it) })
            val keyIds = HashMap<String, Int>(keys.size)
            keys.chunked(MAX_IDS).forEach { chunk -> statKeys(chunk).forEach { keyIds[it.name] = it.id } }
            insertStats(rows.stats.map { WindowStatEntity(id, it.uid, keyIds.getValue(it.key), it.value, it.provenance) })
        }
        return id
    }

    @Query("SELECT * FROM window_result WHERE id = :id")
    public abstract suspend fun window(id: Long): WindowResultEntity?

    /** The window's records; empty once [WindowResultEntity.detailPruned] is set. */
    @Transaction
    public open suspend fun rows(windowId: Long): WindowRows = WindowRows(uids(windowId), wakes(windowId), stats(windowId))

    /** Every window of [kind], oldest first; for REFERENCE windows, at most one per reference (ADR-0032). */
    @Query("SELECT * FROM window_result WHERE kind = :kind ORDER BY id")
    public abstract suspend fun ofKind(kind: WindowKind): List<WindowResultEntity>

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

    /**
     * The totals of the CONSECUTIVE windows that end after `(fromBootSeq, fromElapsedNs)` and no
     * later than `(toBootSeq, toElapsedNs)`: the chain from one heavy snapshot to another.
     */
    @Query(
        "SELECT COUNT(*) AS windows, " +
            "SUM(CASE WHEN f.plugType = 0 THEN w.measuredMah END) AS dischargeMah, " +
            "COALESCE(SUM(f.plugType = 0), 0) AS dischargeWindows, " +
            "COALESCE(SUM(f.plugType = 0 AND w.measuredMah IS NOT NULL AND w.chargeSource = 'CHARGE_COUNTER'), 0) AS counterWindows, " +
            "COALESCE(SUM(f.plugType = 0 AND w.measuredMah IS NULL), 0) AS unmeasuredWindows, " +
            "COALESCE(SUM(f.plugType <> 0), 0) AS pluggedWindows, " +
            "COALESCE(SUM(f.plugType IS NULL), 0) AS unknownWindows, " +
            "SUM(w.modeledMah) AS modeledMah, " +
            "COALESCE(SUM(w.modeledMah IS NULL), 0) AS unmodeledWindows, " +
            "COALESCE(SUM(w.screenOnMs), 0) AS screenOnMs, COALESCE(SUM(w.screenOffMs), 0) AS screenOffMs " +
            "FROM window_result w JOIN snapshot s ON s.id = w.toSnapshotId JOIN snapshot f ON f.id = w.fromSnapshotId " +
            "WHERE w.kind = 'CONSECUTIVE' AND $TO_IN_CHAIN",
    )
    public abstract suspend fun chainTotals(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): ChainTotals

    /** As [chainTotals], each UID's modeled mAh per component (device rows included). */
    @Query(
        "SELECT u.uid, u.component, SUM(u.mAh) AS mAh FROM window_uid u " +
            "JOIN window_result w ON w.id = u.windowId JOIN snapshot s ON s.id = w.toSnapshotId " +
            "WHERE w.kind = 'CONSECUTIVE' AND $TO_IN_CHAIN GROUP BY u.uid, u.component",
    )
    public abstract suspend fun chainUids(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<ChainUid>

    /** As [chainTotals], each wakelock, kernel source and wakeup reason, with its screen-off time. */
    @Query(
        "SELECT k.uid, t.tag, k.kind, SUM(k.totalMs) AS totalMs, SUM(k.count) AS count, " +
            "SUM(CASE WHEN w.screenOnMs = 0 THEN k.totalMs ELSE 0 END) AS screenOffMs, " +
            "SUM(CASE WHEN w.screenOffMs = 0 THEN k.totalMs ELSE 0 END) AS screenOnMs, " +
            "SUM(k.wakeups) AS wakeups FROM window_wake k JOIN wake_tag t ON t.id = k.tagId " +
            "JOIN window_result w ON w.id = k.windowId JOIN snapshot s ON s.id = w.toSnapshotId " +
            "WHERE w.kind = 'CONSECUTIVE' AND $TO_IN_CHAIN GROUP BY k.uid, k.tagId, k.kind",
    )
    public abstract suspend fun chainWakes(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<ChainWake>

    /** As [chainTotals], each UID's change of the counter [key]. */
    @Query(
        "SELECT x.uid, SUM(x.value) AS value FROM window_stat x JOIN stat_key k ON k.id = x.keyId " +
            "JOIN window_result w ON w.id = x.windowId JOIN snapshot s ON s.id = w.toSnapshotId " +
            "WHERE k.name = :key AND w.kind = 'CONSECUTIVE' AND $TO_IN_CHAIN GROUP BY x.uid",
    )
    public abstract suspend fun chainStat(
        key: String,
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<ChainStat>

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

    /** Windows of [kinds] that end before the cut, for [deleteWindows]. */
    @Query(
        "SELECT w.id FROM window_result w JOIN snapshot s ON s.id = w.toSnapshotId " +
            "WHERE w.kind IN (:kinds) AND $TO_BEFORE_CUT",
    )
    public abstract suspend fun windowsBefore(
        kinds: List<WindowKind>,
        cutBootSeq: Int,
        cutElapsedNs: Long,
    ): List<Long>

    /**
     * Retention (ADR-0021): deletes [windowIds] with their records, which go by cascade; insights
     * keep their row and lose the link. Then drops the wake tags no window uses.
     */
    @Transaction
    public open suspend fun deleteWindows(windowIds: List<Long>) {
        windowIds.chunked(MAX_IDS).forEach { deleteWindowRows(it) }
        deleteUnusedWakeTags()
    }

    /**
     * Retention (ADR-0015): deletes the records of [windowIds] and marks them
     * [WindowResultEntity.detailPruned]. The window rows stay. Then drops the wake tags no window uses.
     */
    @Transaction
    public open suspend fun pruneDetail(windowIds: List<Long>) {
        windowIds.chunked(MAX_IDS).forEach { chunk ->
            deleteUids(chunk)
            deleteWakes(chunk)
            deleteStats(chunk)
            markDetailPruned(chunk)
        }
        deleteUnusedWakeTags()
    }

    @Insert
    protected abstract suspend fun insertWindow(window: WindowResultEntity): Long

    @Insert
    protected abstract suspend fun insertUids(rows: List<WindowUidEntity>)

    @Insert
    protected abstract suspend fun insertWakes(rows: List<WindowWakeEntity>)

    @Insert
    protected abstract suspend fun insertStats(rows: List<WindowStatEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertWakeTags(tags: List<WakeTagEntity>)

    @Query("SELECT * FROM wake_tag WHERE tag IN (:tags)")
    protected abstract suspend fun wakeTags(tags: List<String>): List<WakeTagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertStatKeys(keys: List<StatKeyEntity>)

    @Query("SELECT * FROM stat_key WHERE name IN (:names)")
    protected abstract suspend fun statKeys(names: List<String>): List<StatKeyEntity>

    @Query("SELECT * FROM window_uid WHERE windowId = :windowId")
    protected abstract suspend fun uids(windowId: Long): List<WindowUidEntity>

    @Query(
        "SELECT w.uid, t.tag, w.kind, w.totalMs, w.count, w.screenOffMs, w.wakeups FROM window_wake w " +
            "JOIN wake_tag t ON t.id = w.tagId WHERE w.windowId = :windowId ORDER BY w.id",
    )
    protected abstract suspend fun wakes(windowId: Long): List<WindowWake>

    @Query(
        "SELECT s.uid, k.name AS `key`, s.value, s.provenance FROM window_stat s " +
            "JOIN stat_key k ON k.id = s.keyId WHERE s.windowId = :windowId",
    )
    protected abstract suspend fun stats(windowId: Long): List<WindowStat>

    @Query("DELETE FROM window_uid WHERE windowId IN (:ids)")
    protected abstract suspend fun deleteUids(ids: List<Long>)

    @Query("DELETE FROM window_wake WHERE windowId IN (:ids)")
    protected abstract suspend fun deleteWakes(ids: List<Long>)

    @Query("DELETE FROM window_stat WHERE windowId IN (:ids)")
    protected abstract suspend fun deleteStats(ids: List<Long>)

    @Query("UPDATE window_result SET detailPruned = 1 WHERE id IN (:ids)")
    protected abstract suspend fun markDetailPruned(ids: List<Long>)

    @Query("DELETE FROM window_result WHERE id IN (:ids)")
    protected abstract suspend fun deleteWindowRows(ids: List<Long>)

    /** `tagId` has no index; `NOT IN` reads the wakes once rather than once per tag. */
    @Query("DELETE FROM wake_tag WHERE id NOT IN (SELECT tagId FROM window_wake)")
    protected abstract suspend fun deleteUnusedWakeTags()

    private companion object {
        /** The window's end snapshot `s` is keyed before `(:cutBootSeq, :cutElapsedNs)`. */
        const val TO_BEFORE_CUT =
            "(s.bootSeq < :cutBootSeq OR (s.bootSeq = :cutBootSeq AND s.elapsedNs < :cutElapsedNs))"

        /** The window's end snapshot `s` is keyed after `(:fromBootSeq, :fromElapsedNs)` and no later than `(:toBootSeq, :toElapsedNs)`. */
        const val TO_IN_CHAIN =
            "(s.bootSeq > :fromBootSeq OR (s.bootSeq = :fromBootSeq AND s.elapsedNs > :fromElapsedNs)) " +
                "AND (s.bootSeq < :toBootSeq OR (s.bootSeq = :toBootSeq AND s.elapsedNs <= :toElapsedNs))"

        const val MAX_IDS = 500
    }
}

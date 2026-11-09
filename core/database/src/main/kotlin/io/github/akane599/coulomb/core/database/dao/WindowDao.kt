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
import io.github.akane599.coulomb.core.database.entity.DetailCodes
import io.github.akane599.coulomb.core.database.entity.KernelCodes
import io.github.akane599.coulomb.core.database.entity.PowerColumns
import io.github.akane599.coulomb.core.database.entity.RecordCodes
import io.github.akane599.coulomb.core.database.entity.StatKeyEntity
import io.github.akane599.coulomb.core.database.entity.WakeTagEntity
import io.github.akane599.coulomb.core.database.entity.WindowComponentEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowStatEntity
import io.github.akane599.coulomb.core.database.entity.WindowUid
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.database.entity.WindowWakeEntity
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage
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
    /** The time of the windows that started on battery with a measured value: the hours [dischargeMah] covers. */
    val measuredMs: Long,
    /** Windows S4 broke down (ADR-0035 §5): each has a `window_component` device row. */
    val coveredWindows: Int = 0,
    /** The covered windows' modeled total from S5; null when none is covered. */
    val coveredModeledMah: Double? = null,
    /** Windows not broken down because Component breakdown was off (ADR-0035 §2). */
    val offWindows: Int = 0,
)

/** One `window_component` UID's columns summed over a chain's covered windows (ADR-0035 §7), in dC. */
public data class ChainComponent(
    val uid: Int,
    @Embedded val power: PowerColumns,
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

/**
 * One consecutive window of a chain, in order: what its measured drain came from, whether it started
 * on battery, the levels at its ends, and which records it kept. A span's Estimated total takes its
 * range from these, and its wake records their coverage (ADR-0028 §3).
 */
public data class ChainStep(
    val chargeSource: ChargeSource?,
    val measuredMah: Double?,
    /** The start snapshot's plug type; 0 on battery, null when unknown. */
    val plugType: Int?,
    val fromLevel: Int?,
    val toLevel: Int?,
    val detailPruned: Boolean = false,
    /** Null without S5's device section at both ends, and so without S5's records. */
    val modeledMah: Double? = null,
    @TypeConverters(KernelCodes::class)
    val kernelCoverage: KernelCoverage? = null,
)

/** The per-UID records of one window. The uid and component rows' `windowId` is ignored on insert. */
public data class WindowRows(
    val uids: List<WindowUid> = emptyList(),
    val wakes: List<WindowWake> = emptyList(),
    val stats: List<WindowStat> = emptyList(),
    /** S4's breakdown (ADR-0035 §6); only on consecutive windows that passed every rule. */
    val components: List<WindowComponentEntity> = emptyList(),
)

/**
 * Drain windows and their per-UID records (SPEC 6.2, 6.5). Stat keys, `window_uid`'s component and
 * process-state names and wake tags are stored once in `stat_key` and `wake_tag` (ADR-0021,
 * ADR-0035); this DAO reads and writes them as text.
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
        if (rows.uids.isNotEmpty()) {
            val names = keyIds(rows.uids.flatMapTo(LinkedHashSet()) { listOf(it.component, it.processState) })
            insertUids(
                rows.uids.map {
                    WindowUidEntity(
                        id,
                        it.uid,
                        names.getValue(it.component),
                        names.getValue(it.processState),
                        it.process,
                        it.mAh,
                        it.provenance,
                    )
                },
            )
        }
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
            val keyIds = keyIds(rows.stats.mapTo(LinkedHashSet()) { it.key })
            insertStats(rows.stats.map { WindowStatEntity(id, it.uid, keyIds.getValue(it.key), it.value, it.provenance) })
        }
        insertComponents(rows.components.map { it.copy(windowId = id) })
        return id
    }

    /** The `stat_key` ids of [names], adding the names not stored yet. */
    private suspend fun keyIds(names: Set<String>): Map<String, Int> {
        val list = names.toList()
        insertStatKeys(list.map { StatKeyEntity(name = it) })
        val ids = HashMap<String, Int>(list.size)
        list.chunked(MAX_IDS).forEach { chunk -> statKeys(chunk).forEach { ids[it.name] = it.id } }
        return ids
    }

    @Query("SELECT * FROM window_result WHERE id = :id")
    public abstract suspend fun window(id: Long): WindowResultEntity?

    /** The window's records; empty once [WindowResultEntity.detailPruned] is set. */
    @Transaction
    public open suspend fun rows(windowId: Long): WindowRows =
        WindowRows(uids(windowId), wakes(windowId), stats(windowId), components(windowId))

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
            "COALESCE(SUM(f.plugType = 0 AND w.measuredMah IS NOT NULL AND w.chargeSource = ${RecordCodes.CHARGE_COUNTER}), 0) " +
            "AS counterWindows, " +
            "COALESCE(SUM(f.plugType = 0 AND w.measuredMah IS NULL), 0) AS unmeasuredWindows, " +
            "COALESCE(SUM(f.plugType <> 0), 0) AS pluggedWindows, " +
            "COALESCE(SUM(f.plugType IS NULL), 0) AS unknownWindows, " +
            "SUM(w.modeledMah) AS modeledMah, " +
            "COALESCE(SUM(w.modeledMah IS NULL), 0) AS unmodeledWindows, " +
            "COALESCE(SUM(w.screenOnMs), 0) AS screenOnMs, COALESCE(SUM(w.screenOffMs), 0) AS screenOffMs, " +
            "COALESCE(SUM(CASE WHEN f.plugType = 0 AND w.measuredMah IS NOT NULL THEN w.screenOnMs + w.screenOffMs END), 0) " +
            "AS measuredMs, " +
            "COALESCE(SUM(w.breakdown = $BROKEN_DOWN), 0) AS coveredWindows, " +
            "SUM(CASE WHEN w.breakdown = $BROKEN_DOWN THEN w.modeledMah END) AS coveredModeledMah, " +
            "COALESCE(SUM(w.breakdown = $S4_OFF), 0) AS offWindows " +
            "FROM window_result w JOIN snapshot s ON s.id = w.toSnapshotId JOIN snapshot f ON f.id = w.fromSnapshotId " +
            "WHERE w.kind = ${RecordCodes.WINDOW_CONSECUTIVE} AND $TO_IN_CHAIN",
    )
    public abstract suspend fun chainTotals(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): ChainTotals

    /** As [chainTotals], each window in time order ([ChainStep]). */
    @Query(
        "SELECT w.chargeSource, w.measuredMah, f.plugType, f.levelPct AS fromLevel, s.levelPct AS toLevel, " +
            "w.detailPruned, w.modeledMah, w.kernelCoverage " +
            "FROM window_result w JOIN snapshot s ON s.id = w.toSnapshotId JOIN snapshot f ON f.id = w.fromSnapshotId " +
            "WHERE w.kind = ${RecordCodes.WINDOW_CONSECUTIVE} AND $TO_IN_CHAIN ORDER BY s.bootSeq, s.elapsedNs",
    )
    public abstract suspend fun chainSteps(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<ChainStep>

    /** As [chainTotals], each UID's modeled mAh per component (device rows included). */
    @Query(
        "SELECT u.uid, c.name AS component, SUM(u.mAh) AS mAh FROM window_uid u JOIN stat_key c ON c.id = u.componentId " +
            "JOIN window_result w ON w.id = u.windowId JOIN snapshot s ON s.id = w.toSnapshotId " +
            "WHERE w.kind = ${RecordCodes.WINDOW_CONSECUTIVE} AND $TO_IN_CHAIN GROUP BY u.uid, u.componentId",
    )
    public abstract suspend fun chainUids(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<ChainUid>

    /**
     * As [chainTotals], each UID's S4 columns summed over the broken-down windows (ADR-0035 §7). Only
     * those windows have `window_component` rows, and each has a device row.
     */
    @Query(
        "SELECT c.uid, $SUM_POWER_COLUMNS FROM window_component c JOIN window_result w ON w.id = c.windowId " +
            "JOIN snapshot s ON s.id = w.toSnapshotId WHERE w.kind = ${RecordCodes.WINDOW_CONSECUTIVE} AND $TO_IN_CHAIN GROUP BY c.uid",
    )
    public abstract suspend fun chainComponents(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<ChainComponent>

    /** As [chainTotals], each wakelock, kernel source and wakeup reason, with its screen-off time. */
    @Query(
        "SELECT k.uid, t.tag, k.kind, SUM(k.totalMs) AS totalMs, SUM(k.count) AS count, " +
            "SUM(CASE WHEN w.screenOnMs = 0 THEN k.totalMs ELSE 0 END) AS screenOffMs, " +
            "SUM(CASE WHEN w.screenOffMs = 0 THEN k.totalMs ELSE 0 END) AS screenOnMs, " +
            "SUM(k.wakeups) AS wakeups FROM window_wake k JOIN wake_tag t ON t.id = k.tagId " +
            "JOIN window_result w ON w.id = k.windowId JOIN snapshot s ON s.id = w.toSnapshotId " +
            "WHERE w.kind = ${RecordCodes.WINDOW_CONSECUTIVE} AND $TO_IN_CHAIN GROUP BY k.uid, k.tagId, k.kind",
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
            "WHERE k.name = :key AND w.kind = ${RecordCodes.WINDOW_CONSECUTIVE} AND $TO_IN_CHAIN GROUP BY x.uid",
    )
    public abstract suspend fun chainStat(
        key: String,
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<ChainStat>

    /** One UID's shares across the windows that still have them, for app detail (SPEC 10). */
    @Query("$UID_ROWS WHERE u.uid = :uid ORDER BY u.windowId")
    public abstract fun observeForUid(uid: Int): Flow<List<WindowUid>>

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
            "WHERE w.detailPruned = 0 AND w.kind = ${RecordCodes.WINDOW_SESSION} AND x.kind IN (:sessionKinds) AND $TO_BEFORE_CUT",
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
            deleteComponents(chunk)
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

    @Insert
    protected abstract suspend fun insertComponents(rows: List<WindowComponentEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertWakeTags(tags: List<WakeTagEntity>)

    @Query("SELECT * FROM wake_tag WHERE tag IN (:tags)")
    protected abstract suspend fun wakeTags(tags: List<String>): List<WakeTagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertStatKeys(keys: List<StatKeyEntity>)

    @Query("SELECT * FROM stat_key WHERE name IN (:names)")
    protected abstract suspend fun statKeys(names: List<String>): List<StatKeyEntity>

    @Query("$UID_ROWS WHERE u.windowId = :windowId")
    protected abstract suspend fun uids(windowId: Long): List<WindowUid>

    @Query("SELECT * FROM window_component WHERE windowId = :windowId ORDER BY uid")
    protected abstract suspend fun components(windowId: Long): List<WindowComponentEntity>

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

    @Query("DELETE FROM window_component WHERE windowId IN (:ids)")
    protected abstract suspend fun deleteComponents(ids: List<Long>)

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

        /** `Breakdown.BROKEN_DOWN.code` and `Breakdown.S4_OFF.code`, frozen (schema v4). */
        const val BROKEN_DOWN = "0"
        const val S4_OFF = "1"

        /** [PowerColumns]' columns summed, each under its own name. */
        const val SUM_POWER_COLUMNS =
            "SUM(c.screen) AS screen, SUM(c.cpu) AS cpu, SUM(c.bluetooth) AS bluetooth, SUM(c.camera) AS camera, " +
                "SUM(c.audio) AS audio, SUM(c.video) AS video, SUM(c.flashlight) AS flashlight, " +
                "SUM(c.mobileRadio) AS mobileRadio, SUM(c.sensors) AS sensors, SUM(c.gnss) AS gnss, SUM(c.wifi) AS wifi, " +
                "SUM(c.wakelock) AS wakelock, SUM(c.phone) AS phone, SUM(c.ambientDisplay) AS ambientDisplay, " +
                "SUM(c.custom) AS custom, SUM(c.other) AS other, SUM(c.foreground) AS foreground, " +
                "SUM(c.background) AS background, SUM(c.foregroundService) AS foregroundService, SUM(c.cached) AS cached"

        /** `window_uid` rows with their names as text ([WindowUid]). */
        const val UID_ROWS =
            "SELECT u.windowId, u.uid, c.name AS component, p.name AS processState, u.process, u.mAh, u.provenance " +
                "FROM window_uid u JOIN stat_key c ON c.id = u.componentId JOIN stat_key p ON p.id = u.processStateId"
    }
}

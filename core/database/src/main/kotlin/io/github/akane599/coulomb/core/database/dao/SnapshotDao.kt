// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import io.github.akane599.coulomb.core.database.entity.AlarmStatEntity
import io.github.akane599.coulomb.core.database.entity.DeviceStatEntity
import io.github.akane599.coulomb.core.database.entity.JobStatEntity
import io.github.akane599.coulomb.core.database.entity.NetStatEntity
import io.github.akane599.coulomb.core.database.entity.RailEnergyEntity
import io.github.akane599.coulomb.core.database.entity.ReferenceEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.UidStatEntity
import io.github.akane599.coulomb.core.database.entity.WakeStatEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.ReferenceOrigin
import kotlinx.coroutines.flow.Flow

/**
 * The counters of one snapshot. Their `snapshotId` is ignored on insert: [SnapshotDao.insert] sets
 * it to the new snapshot's id.
 */
public data class SnapshotRows(
    val uidPower: List<UidPowerEntity> = emptyList(),
    val wakeStats: List<WakeStatEntity> = emptyList(),
    val alarmStats: List<AlarmStatEntity> = emptyList(),
    val jobStats: List<JobStatEntity> = emptyList(),
    val netStats: List<NetStatEntity> = emptyList(),
    val railEnergy: List<RailEnergyEntity> = emptyList(),
    val uidStats: List<UidStatEntity> = emptyList(),
    val deviceStats: List<DeviceStatEntity> = emptyList(),
)

/** Snapshots, their counters and the references that pin them (SPEC 7.2, 7.6, 7.7). */
@Dao
public abstract class SnapshotDao {
    /** Stores [snapshot] and its [rows] in one transaction and returns the snapshot's id. */
    @Transaction
    public open suspend fun insert(
        snapshot: SnapshotEntity,
        rows: SnapshotRows = SnapshotRows(),
    ): Long {
        val id = insertSnapshot(snapshot)
        insertUidPower(rows.uidPower.map { it.copy(snapshotId = id) })
        insertWakeStats(rows.wakeStats.map { it.copy(snapshotId = id) })
        insertAlarmStats(rows.alarmStats.map { it.copy(snapshotId = id) })
        insertJobStats(rows.jobStats.map { it.copy(snapshotId = id) })
        insertNetStats(rows.netStats.map { it.copy(snapshotId = id) })
        insertRailEnergy(rows.railEnergy.map { it.copy(snapshotId = id) })
        insertUidStats(rows.uidStats.map { it.copy(snapshotId = id) })
        insertDeviceStats(rows.deviceStats.map { it.copy(snapshotId = id) })
        return id
    }

    @Query("SELECT * FROM snapshot WHERE id = :id")
    public abstract suspend fun snapshot(id: Long): SnapshotEntity?

    @Query("SELECT * FROM snapshot ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1")
    public abstract suspend fun latest(): SnapshotEntity?

    /** The latest heavy snapshot: where the next consecutive window starts. */
    @Query("SELECT * FROM snapshot WHERE kind = 'HEAVY' ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1")
    public abstract suspend fun latestHeavy(): SnapshotEntity?

    /**
     * Heavy snapshots strictly between `(fromBootSeq, fromElapsedNs)` and `(toBootSeq, toElapsedNs)`
     * whose counters are still stored, in time order: the links of a window's chain (ADR-0018).
     */
    @Query(
        "SELECT * FROM snapshot WHERE kind = 'HEAVY' AND countersPruned = 0 " +
            "AND (bootSeq > :fromBootSeq OR (bootSeq = :fromBootSeq AND elapsedNs > :fromElapsedNs)) " +
            "AND (bootSeq < :toBootSeq OR (bootSeq = :toBootSeq AND elapsedNs < :toElapsedNs)) " +
            "ORDER BY bootSeq, elapsedNs",
    )
    public abstract suspend fun heavyWithCountersBetween(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<SnapshotEntity>

    /** The latest snapshot of [bootSeq]: where a session closed by a reboot ends. */
    @Query("SELECT * FROM snapshot WHERE bootSeq = :bootSeq ORDER BY elapsedNs DESC LIMIT 1")
    public abstract suspend fun latestInBoot(bootSeq: Int): SnapshotEntity?

    /** The latest heavy snapshot of [bootSeq]: where the window of a session closed by a reboot ends. */
    @Query("SELECT * FROM snapshot WHERE bootSeq = :bootSeq AND kind = 'HEAVY' ORDER BY elapsedNs DESC LIMIT 1")
    public abstract suspend fun latestHeavyInBoot(bootSeq: Int): SnapshotEntity?

    /** The latest snapshot whose history read set a cursor: where the next read starts (ADR-0014). */
    @Query("SELECT * FROM snapshot WHERE historyCursorMs IS NOT NULL ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1")
    public abstract suspend fun latestWithHistoryCursor(): SnapshotEntity?

    /**
     * The snapshot's counters. Lists are empty for a source that was not read (see
     * [SnapshotEntity.sourceStatusJson]) or once [SnapshotEntity.countersPruned] is set.
     */
    @Transaction
    public open suspend fun rows(snapshotId: Long): SnapshotRows =
        SnapshotRows(
            uidPower = uidPower(snapshotId),
            wakeStats = wakeStats(snapshotId),
            alarmStats = alarmStats(snapshotId),
            jobStats = jobStats(snapshotId),
            netStats = netStats(snapshotId),
            railEnergy = railEnergy(snapshotId),
            uidStats = uidStats(snapshotId),
            deviceStats = deviceStats(snapshotId),
        )

    /** A reference the user named (SPEC 7.6). */
    public suspend fun insertUserReference(
        name: String,
        snapshotId: Long,
        createdWall: Long,
    ): Long = insertReference(ReferenceEntity(0, name, snapshotId, ReferenceOrigin.USER, null, createdWall))

    /**
     * Moves the automatic reference [kind] to [snapshotId], creating it the first time. Moving
     * rather than adding keeps one row per kind, so old snapshots do not stay pinned.
     */
    @Transaction
    public open suspend fun moveAutoReference(
        kind: AutoReference,
        snapshotId: Long,
        createdWall: Long,
    ) {
        if (moveReference(kind, snapshotId, createdWall) == 0) {
            insertReference(ReferenceEntity(0, null, snapshotId, ReferenceOrigin.AUTO, kind, createdWall))
        }
    }

    @Query("SELECT * FROM reference WHERE autoKind = :kind")
    public abstract suspend fun autoReference(kind: AutoReference): ReferenceEntity?

    @Query("DELETE FROM reference WHERE id = :id AND origin = 'USER'")
    public abstract suspend fun deleteUserReference(id: Long): Int

    @Query("SELECT * FROM reference ORDER BY createdWall DESC, id DESC")
    public abstract fun observeReferences(): Flow<List<ReferenceEntity>>

    /**
     * Retention (ADR-0015): deletes the counters of heavy snapshots taken before
     * `(cutBootSeq, cutElapsedNs)` that no window can start at any more. Kept: the latest heavy
     * snapshot, every referenced snapshot and the start of every open session. The snapshot rows
     * stay, marked [SnapshotEntity.countersPruned]. Returns the snapshots pruned.
     */
    @Transaction
    public open suspend fun pruneCounters(
        cutBootSeq: Int,
        cutElapsedNs: Long,
    ): Int {
        val ids = prunableCounters(cutBootSeq, cutElapsedNs)
        ids.chunked(MAX_IDS).forEach { chunk ->
            deleteUidPower(chunk)
            deleteWakeStats(chunk)
            deleteAlarmStats(chunk)
            deleteJobStats(chunk)
            deleteNetStats(chunk)
            deleteRailEnergy(chunk)
            deleteUidStats(chunk)
            deleteDeviceStats(chunk)
            markCountersPruned(chunk)
        }
        return ids.size
    }

    /**
     * Retention (ADR-0021): deletes the snapshot rows taken before `(cutBootSeq, cutElapsedNs)` that
     * nothing needs any more, and returns how many went. Kept:
     * - every snapshot a session, a reference or a window points to;
     * - snapshots that still have counters or a blob path;
     * - the last snapshot of each boot, which retention counts elapsed time back from;
     * - the latest heavy snapshot and the latest with a history cursor;
     * - the last one whose history read had ended before the cut, which [HistoryDao.deleteBefore]
     *   cuts the history at. Run this after that call, with the same cut.
     */
    @Query(
        "DELETE FROM snapshot WHERE $BEFORE_CUT AND blobPath IS NULL AND (kind = 'LIGHT' OR countersPruned = 1) " +
            "AND id NOT IN (SELECT startSnapshotId FROM session WHERE startSnapshotId IS NOT NULL) " +
            "AND id NOT IN (SELECT endSnapshotId FROM session WHERE endSnapshotId IS NOT NULL) " +
            "AND id NOT IN (SELECT snapshotId FROM reference) " +
            "AND id NOT IN (SELECT fromSnapshotId FROM window_result) " +
            "AND id NOT IN (SELECT toSnapshotId FROM window_result) " +
            "AND EXISTS (SELECT 1 FROM snapshot n WHERE n.bootSeq = snapshot.bootSeq AND n.elapsedNs > snapshot.elapsedNs) " +
            "AND id NOT IN (SELECT id FROM snapshot WHERE kind = 'HEAVY' ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1) " +
            "AND id NOT IN (SELECT id FROM snapshot WHERE historyCursorMs IS NOT NULL " +
            "ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1) " +
            "AND id NOT IN (SELECT id FROM snapshot WHERE historyEventId IS NOT NULL AND (bootSeq < :cutBootSeq " +
            "OR (bootSeq = :cutBootSeq AND elapsedNs + durationMs * 1000000 < :cutElapsedNs)) " +
            "ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1)",
    )
    public abstract suspend fun deleteUnneeded(
        cutBootSeq: Int,
        cutElapsedNs: Long,
    ): Int

    /**
     * Clears the blob path of snapshots taken before the cut (SPEC 7.7: 30 days) and returns the
     * paths, for the caller to delete after the transaction. Files that a crash left behind are
     * found by comparing the blob folder with [blobPaths].
     */
    @Transaction
    public open suspend fun pruneBlobs(
        cutBootSeq: Int,
        cutElapsedNs: Long,
    ): List<String> {
        val blobs = blobsBefore(cutBootSeq, cutElapsedNs)
        clearBlobsBefore(cutBootSeq, cutElapsedNs)
        return blobs
    }

    @Query("SELECT blobPath FROM snapshot WHERE blobPath IS NOT NULL")
    public abstract suspend fun blobPaths(): List<String>

    /** Clears [paths] from their snapshots, for blobs deleted to keep the folder under its cap (ADR-0018). */
    @Query("UPDATE snapshot SET blobPath = NULL WHERE blobPath IN (:paths)")
    public abstract suspend fun clearBlobPaths(paths: List<String>)

    @Insert
    protected abstract suspend fun insertSnapshot(snapshot: SnapshotEntity): Long

    @Insert
    protected abstract suspend fun insertReference(reference: ReferenceEntity): Long

    @Query("UPDATE reference SET snapshotId = :snapshotId, createdWall = :createdWall WHERE autoKind = :kind")
    protected abstract suspend fun moveReference(
        kind: AutoReference,
        snapshotId: Long,
        createdWall: Long,
    ): Int

    @Insert
    protected abstract suspend fun insertUidPower(rows: List<UidPowerEntity>)

    @Insert
    protected abstract suspend fun insertWakeStats(rows: List<WakeStatEntity>)

    @Insert
    protected abstract suspend fun insertAlarmStats(rows: List<AlarmStatEntity>)

    @Insert
    protected abstract suspend fun insertJobStats(rows: List<JobStatEntity>)

    @Insert
    protected abstract suspend fun insertNetStats(rows: List<NetStatEntity>)

    @Insert
    protected abstract suspend fun insertRailEnergy(rows: List<RailEnergyEntity>)

    @Insert
    protected abstract suspend fun insertUidStats(rows: List<UidStatEntity>)

    @Insert
    protected abstract suspend fun insertDeviceStats(rows: List<DeviceStatEntity>)

    @Query("SELECT * FROM uid_power WHERE snapshotId = :snapshotId")
    protected abstract suspend fun uidPower(snapshotId: Long): List<UidPowerEntity>

    @Query("SELECT * FROM wake_stat WHERE snapshotId = :snapshotId ORDER BY id")
    protected abstract suspend fun wakeStats(snapshotId: Long): List<WakeStatEntity>

    @Query("SELECT * FROM alarm_stat WHERE snapshotId = :snapshotId")
    protected abstract suspend fun alarmStats(snapshotId: Long): List<AlarmStatEntity>

    @Query("SELECT * FROM job_stat WHERE snapshotId = :snapshotId")
    protected abstract suspend fun jobStats(snapshotId: Long): List<JobStatEntity>

    @Query("SELECT * FROM net_stat WHERE snapshotId = :snapshotId")
    protected abstract suspend fun netStats(snapshotId: Long): List<NetStatEntity>

    @Query("SELECT * FROM rail_energy WHERE snapshotId = :snapshotId")
    protected abstract suspend fun railEnergy(snapshotId: Long): List<RailEnergyEntity>

    @Query("SELECT * FROM uid_stat WHERE snapshotId = :snapshotId")
    protected abstract suspend fun uidStats(snapshotId: Long): List<UidStatEntity>

    @Query("SELECT * FROM device_stat WHERE snapshotId = :snapshotId")
    protected abstract suspend fun deviceStats(snapshotId: Long): List<DeviceStatEntity>

    @Query(
        "SELECT id FROM snapshot WHERE kind = 'HEAVY' AND countersPruned = 0 AND $BEFORE_CUT " +
            "AND id NOT IN (SELECT snapshotId FROM reference) " +
            "AND id NOT IN (SELECT startSnapshotId FROM session " +
            "WHERE closeReason IS NULL AND startSnapshotId IS NOT NULL) " +
            "AND id NOT IN (SELECT id FROM snapshot WHERE kind = 'HEAVY' " +
            "ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1)",
    )
    protected abstract suspend fun prunableCounters(
        cutBootSeq: Int,
        cutElapsedNs: Long,
    ): List<Long>

    @Query("DELETE FROM uid_power WHERE snapshotId IN (:ids)")
    protected abstract suspend fun deleteUidPower(ids: List<Long>)

    @Query("DELETE FROM wake_stat WHERE snapshotId IN (:ids)")
    protected abstract suspend fun deleteWakeStats(ids: List<Long>)

    @Query("DELETE FROM alarm_stat WHERE snapshotId IN (:ids)")
    protected abstract suspend fun deleteAlarmStats(ids: List<Long>)

    @Query("DELETE FROM job_stat WHERE snapshotId IN (:ids)")
    protected abstract suspend fun deleteJobStats(ids: List<Long>)

    @Query("DELETE FROM net_stat WHERE snapshotId IN (:ids)")
    protected abstract suspend fun deleteNetStats(ids: List<Long>)

    @Query("DELETE FROM rail_energy WHERE snapshotId IN (:ids)")
    protected abstract suspend fun deleteRailEnergy(ids: List<Long>)

    @Query("DELETE FROM uid_stat WHERE snapshotId IN (:ids)")
    protected abstract suspend fun deleteUidStats(ids: List<Long>)

    @Query("DELETE FROM device_stat WHERE snapshotId IN (:ids)")
    protected abstract suspend fun deleteDeviceStats(ids: List<Long>)

    @Query("UPDATE snapshot SET countersPruned = 1 WHERE id IN (:ids)")
    protected abstract suspend fun markCountersPruned(ids: List<Long>)

    @Query("SELECT blobPath FROM snapshot WHERE blobPath IS NOT NULL AND $BEFORE_CUT")
    protected abstract suspend fun blobsBefore(
        cutBootSeq: Int,
        cutElapsedNs: Long,
    ): List<String>

    @Query("UPDATE snapshot SET blobPath = NULL WHERE blobPath IS NOT NULL AND $BEFORE_CUT")
    protected abstract suspend fun clearBlobsBefore(
        cutBootSeq: Int,
        cutElapsedNs: Long,
    )

    private companion object {
        /** Rows keyed before `(:cutBootSeq, :cutElapsedNs)`. */
        const val BEFORE_CUT = "(bootSeq < :cutBootSeq OR (bootSeq = :cutBootSeq AND elapsedNs < :cutElapsedNs))"

        /** Ids per `IN (...)`, well below SQLite's bound-parameter limit. */
        const val MAX_IDS = 500
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.akane599.coulomb.core.database.entity.AlarmStatEntity
import io.github.akane599.coulomb.core.database.entity.DeviceStatEntity
import io.github.akane599.coulomb.core.database.entity.HistoryTagEntity
import io.github.akane599.coulomb.core.database.entity.JobStatEntity
import io.github.akane599.coulomb.core.database.entity.KernelCodes
import io.github.akane599.coulomb.core.database.entity.KernelLogEventEntity
import io.github.akane599.coulomb.core.database.entity.KernelReadEntity
import io.github.akane599.coulomb.core.database.entity.NetStatEntity
import io.github.akane599.coulomb.core.database.entity.RailEnergyEntity
import io.github.akane599.coulomb.core.database.entity.ReferenceEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.UidStatEntity
import io.github.akane599.coulomb.core.database.entity.WakeStatEntity
import io.github.akane599.coulomb.core.database.entity.WakeupSourceStatEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.ReferenceOrigin
import io.github.akane599.coulomb.core.model.kernel.KernelLogRecord
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

/**
 * What one snapshot read from the root kernel sources (ADR-0029 §3). [events] are the S27 records of
 * the read, all under [read]'s kernel boot; the suspend entry and exit lines are not stored
 * ([KernelCodes.encodeEvent]). Their ids, like the rows' `snapshotId`, are set on insert.
 */
public data class KernelRows(
    val read: KernelReadEntity,
    val wakeupSources: List<WakeupSourceStatEntity> = emptyList(),
    val events: List<KernelLogRecord> = emptyList(),
)

/** A stored S27 event with its tag as text, before decoding. */
public data class StoredKernelEvent(
    val seq: Long,
    val monotonicUs: Long,
    val kind: Int,
    val irq: Int?,
    val tag: String?,
    val errno: Int?,
)

/** Snapshots, their counters and the references that pin them (SPEC 7.2, 7.6, 7.7). */
@Dao
public abstract class SnapshotDao {
    /** Stores [snapshot], its [rows] and its [kernel] reads in one transaction and returns the snapshot's id. */
    @Transaction
    public open suspend fun insert(
        snapshot: SnapshotEntity,
        rows: SnapshotRows = SnapshotRows(),
        kernel: KernelRows? = null,
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
        kernel?.let { insertKernel(id, it) }
        return id
    }

    private suspend fun insertKernel(
        snapshotId: Long,
        kernel: KernelRows,
    ) {
        insertKernelRead(kernel.read.copy(snapshotId = snapshotId))
        insertWakeupSources(kernel.wakeupSources.map { it.copy(snapshotId = snapshotId) })
        val stored = kernel.events.mapNotNull { record -> KernelCodes.encodeEvent(record.event)?.let { record to it } }
        if (stored.isEmpty()) return
        val tags = stored.mapNotNullTo(LinkedHashSet()) { it.second.tag }.toList()
        insertTags(tags.map { HistoryTagEntity(tag = it) })
        val tagIds = HashMap<String, Long>(tags.size)
        tags.chunked(MAX_IDS).forEach { chunk -> tagIds(chunk).forEach { tagIds[it.tag] = it.id } }
        insertKernelEvents(
            stored.map { (record, event) ->
                KernelLogEventEntity(
                    kernelBoot = kernel.read.kernelBoot,
                    seq = record.seq,
                    monotonicUs = record.monotonicUs,
                    kind = event.kind,
                    irq = event.irq,
                    tagId = event.tag?.let(tagIds::getValue),
                    errno = event.errno,
                    readSnapshotId = snapshotId,
                )
            },
        )
    }

    @Query("SELECT * FROM kernel_read WHERE snapshotId = :snapshotId")
    public abstract suspend fun kernelRead(snapshotId: Long): KernelReadEntity?

    /** The snapshot's S9 sources; empty when S9 was not read or the counters were pruned. */
    @Query("SELECT * FROM wakeup_source_stat WHERE snapshotId = :snapshotId ORDER BY instance")
    public abstract suspend fun wakeupSources(snapshotId: Long): List<WakeupSourceStatEntity>

    /**
     * The next S27 read's cursor (ADR-0029 §5): the furthest record a stored read covered under
     * [kernelBoot]. Never taken from memory and never across boots.
     */
    @Query("SELECT MAX(kmsgLastSeq) FROM kernel_read WHERE kernelBoot = :kernelBoot")
    public abstract suspend fun kmsgCursor(kernelBoot: Long): Long?

    /**
     * The kernel boot of the latest stored S27 read, for a log read whose kernel files gave no boot.
     * The worker applies the cursor only when the kernel has not rebooted since (ADR-0029 §5).
     */
    @Query(
        "SELECT k.kernelBoot FROM kernel_read k JOIN snapshot s ON s.id = k.snapshotId WHERE k.kmsgGaps IS NOT NULL " +
            "ORDER BY s.bootSeq DESC, s.elapsedNs DESC LIMIT 1",
    )
    public abstract suspend fun latestKmsgBoot(): Long?

    /**
     * The S27 reads of the snapshots after `(fromBootSeq, fromElapsedNs)` up to and including
     * `(toBootSeq, toElapsedNs)`, oldest first, for a window's coverage (ADR-0029 §4).
     */
    @Query(
        "SELECT k.* FROM kernel_read k JOIN snapshot s ON s.id = k.snapshotId WHERE k.kmsgGaps IS NOT NULL " +
            "AND (s.bootSeq > :fromBootSeq OR (s.bootSeq = :fromBootSeq AND s.elapsedNs > :fromElapsedNs)) " +
            "AND (s.bootSeq < :toBootSeq OR (s.bootSeq = :toBootSeq AND s.elapsedNs <= :toElapsedNs)) " +
            "ORDER BY s.bootSeq, s.elapsedNs",
    )
    public abstract suspend fun kmsgReadsBetween(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<KernelReadEntity>

    /**
     * The stored S27 records of [kernelBoot] with `afterSeq < seq <= lastSeq`, in order; a null
     * [afterSeq] starts at the first record.
     */
    public suspend fun kernelLog(
        kernelBoot: Long,
        afterSeq: Long?,
        lastSeq: Long,
    ): List<KernelLogRecord> =
        kernelEvents(kernelBoot, afterSeq ?: -1, lastSeq).map {
            KernelLogRecord(it.seq, it.monotonicUs, KernelCodes.decodeEvent(KernelCodes.StoredEvent(it.kind, it.irq, it.tag, it.errno)))
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

    /**
     * Every heavy snapshot strictly between the two points, counters pruned or not, in time order:
     * the S9 steps of a long window, which must see where its rows are gone (ADR-0029 §4).
     */
    @Query(
        "SELECT * FROM snapshot WHERE kind = 'HEAVY' " +
            "AND (bootSeq > :fromBootSeq OR (bootSeq = :fromBootSeq AND elapsedNs > :fromElapsedNs)) " +
            "AND (bootSeq < :toBootSeq OR (bootSeq = :toBootSeq AND elapsedNs < :toElapsedNs)) " +
            "ORDER BY bootSeq, elapsedNs",
    )
    public abstract suspend fun heavyBetween(
        fromBootSeq: Int,
        fromElapsedNs: Long,
        toBootSeq: Int,
        toElapsedNs: Long,
    ): List<SnapshotEntity>

    /**
     * The first heavy snapshot at or after [wallMs], in time order: where a span that starts at a
     * wall time can start (ADR-0036). Wall time only picks the start; order stays by boot and elapsed
     * time (SPEC 7.8).
     */
    @Query("SELECT * FROM snapshot WHERE kind = 'HEAVY' AND wallMs >= :wallMs ORDER BY bootSeq, elapsedNs LIMIT 1")
    public abstract suspend fun firstHeavyFrom(wallMs: Long): SnapshotEntity?

    /** The snapshot of [bootSeq] read at [elapsedNs]: the one a session opened at. */
    @Query("SELECT * FROM snapshot WHERE bootSeq = :bootSeq AND elapsedNs = :elapsedNs LIMIT 1")
    public abstract suspend fun at(
        bootSeq: Int,
        elapsedNs: Long,
    ): SnapshotEntity?

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
            deleteWakeupSources(chunk)
            clearSuspendReads(chunk)
            deleteEmptyKernelReads(chunk)
            markCountersPruned(chunk)
        }
        return ids.size
    }

    /**
     * Retention (ADR-0029 §3): the S27 ring. Clears the spans of the reads before
     * `(cutBootSeq, cutElapsedNs)` and deletes the events those reads stored, except events a kept
     * span still covers: a retried read covers records whose rows an earlier read owns. So no
     * window built later can count a range as covered without its events. Run it before
     * [HistoryDao.deleteBefore], whose tag prune then sees the events gone.
     */
    @Transaction
    public open suspend fun pruneKernelLog(
        cutBootSeq: Int,
        cutElapsedNs: Long,
    ) {
        clearKmsgReadsBefore(cutBootSeq, cutElapsedNs)
        deleteUncoveredKernelEventsBefore(cutBootSeq, cutElapsedNs)
        deleteEmptyKernelReadsBefore(cutBootSeq, cutElapsedNs)
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

    @Insert
    protected abstract suspend fun insertKernelRead(read: KernelReadEntity)

    @Insert
    protected abstract suspend fun insertWakeupSources(rows: List<WakeupSourceStatEntity>)

    /** A record read twice keeps its first row (ADR-0029 §5). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertKernelEvents(rows: List<KernelLogEventEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTags(tags: List<HistoryTagEntity>)

    @Query("SELECT * FROM history_tag WHERE tag IN (:tags)")
    protected abstract suspend fun tagIds(tags: List<String>): List<HistoryTagEntity>

    @Query(
        "SELECT e.seq, e.monotonicUs, e.kind, e.irq, t.tag, e.errno FROM kernel_log_event e " +
            "LEFT JOIN history_tag t ON t.id = e.tagId " +
            "WHERE e.kernelBoot = :kernelBoot AND e.seq > :afterSeq AND e.seq <= :lastSeq ORDER BY e.seq",
    )
    protected abstract suspend fun kernelEvents(
        kernelBoot: Long,
        afterSeq: Long,
        lastSeq: Long,
    ): List<StoredKernelEvent>

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

    @Query("DELETE FROM wakeup_source_stat WHERE snapshotId IN (:ids)")
    protected abstract suspend fun deleteWakeupSources(ids: List<Long>)

    @Query(
        "UPDATE kernel_read SET suspendSuccess = NULL, suspendFail = NULL, failedFreeze = NULL, failedPrepare = NULL, " +
            "failedSuspend = NULL, failedSuspendLate = NULL, failedSuspendNoirq = NULL, failedResume = NULL, " +
            "failedResumeEarly = NULL, failedResumeNoirq = NULL, lastFailedDev = NULL, lastFailedErrno = NULL, " +
            "lastFailedStep = NULL, resumeReason = NULL, suspendResumeNs = NULL, sleepNs = NULL WHERE snapshotId IN (:ids)",
    )
    protected abstract suspend fun clearSuspendReads(ids: List<Long>)

    @Query("DELETE FROM kernel_read WHERE snapshotId IN (:ids) AND $KERNEL_READ_EMPTY")
    protected abstract suspend fun deleteEmptyKernelReads(ids: List<Long>)

    @Query(
        "DELETE FROM kernel_log_event WHERE readSnapshotId IN (SELECT id FROM snapshot WHERE $BEFORE_CUT) " +
            "AND NOT EXISTS (SELECT 1 FROM kernel_read k WHERE k.kmsgGaps IS NOT NULL " +
            "AND k.kernelBoot = kernel_log_event.kernelBoot AND kernel_log_event.seq <= k.kmsgLastSeq " +
            "AND IFNULL(k.kmsgAfterSeq, k.kmsgFirstSeq - 1) < kernel_log_event.seq)",
    )
    protected abstract suspend fun deleteUncoveredKernelEventsBefore(
        cutBootSeq: Int,
        cutElapsedNs: Long,
    )

    @Query(
        "UPDATE kernel_read SET kmsgAfterSeq = NULL, kmsgFirstSeq = NULL, kmsgLastSeq = NULL, kmsgGaps = NULL " +
            "WHERE snapshotId IN (SELECT id FROM snapshot WHERE $BEFORE_CUT)",
    )
    protected abstract suspend fun clearKmsgReadsBefore(
        cutBootSeq: Int,
        cutElapsedNs: Long,
    )

    @Query("DELETE FROM kernel_read WHERE snapshotId IN (SELECT id FROM snapshot WHERE $BEFORE_CUT) AND $KERNEL_READ_EMPTY")
    protected abstract suspend fun deleteEmptyKernelReadsBefore(
        cutBootSeq: Int,
        cutElapsedNs: Long,
    )

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

        /**
         * A kernel read whose sources were all cleared. S25 and S26 are cleared together; S9 rows
         * need the row's kernel boot while they last.
         */
        const val KERNEL_READ_EMPTY =
            "suspendSuccess IS NULL AND resumeReason IS NULL AND kmsgGaps IS NULL " +
                "AND NOT EXISTS (SELECT 1 FROM wakeup_source_stat w WHERE w.snapshotId = kernel_read.snapshotId)"

        /** Ids per `IN (...)`, well below SQLite's bound-parameter limit. */
        const val MAX_IDS = 500
    }
}

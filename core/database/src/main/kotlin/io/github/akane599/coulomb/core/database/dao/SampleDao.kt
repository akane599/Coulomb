// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.SampleAggregateEntity
import kotlinx.coroutines.flow.Flow

/**
 * Battery samples and their compacted aggregates (SPEC 7.3, 7.7). Rows order by boot, then elapsed
 * time. Retention cuts are a boot and elapsed time, never a wall time, so a clock that is unset
 * after boot or jumps cannot compact the wrong rows (ADR-0015).
 */
@Dao
public abstract class SampleDao {
    /**
     * Stores [sample] unless one with its key exists, so a repeated sample cannot abort the
     * snapshot written with it (SPEC 7.5). Returns -1 when it was ignored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public abstract suspend fun insert(sample: BatterySampleEntity): Long

    @Query("SELECT * FROM battery_sample ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1")
    public abstract fun observeLatest(): Flow<BatterySampleEntity?>

    @Query("SELECT * FROM battery_sample ORDER BY bootSeq DESC, elapsedNs DESC LIMIT 1")
    public abstract suspend fun latest(): BatterySampleEntity?

    /** Samples of [bootSeq] in `[fromElapsedNs, toElapsedNs)`, as a session reads them. */
    @Query(
        "SELECT * FROM battery_sample WHERE bootSeq = :bootSeq " +
            "AND elapsedNs >= :fromElapsedNs AND elapsedNs < :toElapsedNs ORDER BY elapsedNs",
    )
    public abstract suspend fun samples(
        bootSeq: Int,
        fromElapsedNs: Long,
        toElapsedNs: Long,
    ): List<BatterySampleEntity>

    /** Samples taken in `[fromWall, toWall)` for a chart; the wall clock picks them, the key orders them. */
    @Query(
        "SELECT * FROM battery_sample WHERE wallMs >= :fromWall AND wallMs < :toWall " +
            "ORDER BY bootSeq, elapsedNs",
    )
    public abstract suspend fun samplesBetween(
        fromWall: Long,
        toWall: Long,
    ): List<BatterySampleEntity>

    @Query(
        "SELECT * FROM sample_aggregate WHERE resolutionS = :resolutionS " +
            "AND startWallMs >= :fromWall AND startWallMs < :toWall ORDER BY bootSeq, startElapsedNs",
    )
    public abstract suspend fun aggregatesBetween(
        resolutionS: Int,
        fromWall: Long,
        toWall: Long,
    ): List<SampleAggregateEntity>

    /**
     * How long the level was at or above [level] in `[fromWall, toWall)`, and how long Coulomb
     * watched, in ms (ADR-0044 §2). The level holds from each sample to the next of its boot:
     * a level change is always sampled (ADR-0037 §1). Minute aggregates stand in for compacted
     * samples, at their last level. A gap longer than [maxGapMs] counts as neither, as when the
     * process was not running. Spans run on the elapsed clock, so a wall-clock change does not stretch
     * or drop them; the wall clock only picks the window (gate review m3).
     */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN level >= :level THEN span ELSE 0 END), 0) AS atOrAboveMs, " +
            "COALESCE(SUM(span), 0) AS watchedMs FROM (" +
            "SELECT level, (nextNs - elapsedNs) / 1000000 AS span FROM (" +
            "SELECT level, elapsedNs, LEAD(elapsedNs) OVER (PARTITION BY bootSeq ORDER BY elapsedNs) AS nextNs FROM (" +
            "SELECT bootSeq, elapsedNs, wallMs, levelPct AS level FROM battery_sample " +
            "WHERE wallMs >= :fromWall AND wallMs < :toWall " +
            "UNION ALL SELECT bootSeq, startElapsedNs AS elapsedNs, startWallMs AS wallMs, levelLastPct AS level " +
            "FROM sample_aggregate WHERE resolutionS = $MINUTE_S AND startWallMs >= :fromWall AND startWallMs < :toWall)) " +
            "WHERE nextNs IS NOT NULL AND nextNs > elapsedNs AND (nextNs - elapsedNs) / 1000000 <= :maxGapMs)",
    )
    public abstract suspend fun timeAtOrAbove(
        level: Int,
        fromWall: Long,
        toWall: Long,
        maxGapMs: Long,
    ): LevelTime

    /**
     * Replaces the raw samples before `(cutBootSeq, cutElapsedNs)` with [resolutionS] aggregates.
     * The cut is moved back to a bucket boundary, so a bucket is built once, from all its samples.
     * Works through at most [chunkRows] samples per transaction. Returns the samples removed.
     */
    public open suspend fun compactSamples(
        cutBootSeq: Int,
        cutElapsedNs: Long,
        resolutionS: Int,
        chunkRows: Int = CHUNK_ROWS,
    ): Int {
        var removed = 0
        do {
            val step = compactSampleChunk(cutBootSeq, cutElapsedNs, resolutionS, chunkRows)
            removed += step
        } while (step > 0)
        return removed
    }

    /** As [compactSamples], for the [fromResolutionS] aggregates becoming coarser [toResolutionS] ones. */
    public open suspend fun compactAggregates(
        cutBootSeq: Int,
        cutElapsedNs: Long,
        fromResolutionS: Int,
        toResolutionS: Int,
        chunkRows: Int = CHUNK_ROWS,
    ): Int {
        require(toResolutionS % fromResolutionS == 0) { "$toResolutionS s buckets cannot hold $fromResolutionS s ones" }
        var removed = 0
        do {
            val step = compactAggregateChunk(cutBootSeq, cutElapsedNs, fromResolutionS, toResolutionS, chunkRows)
            removed += step
        } while (step > 0)
        return removed
    }

    /** One transaction of [compactSamples]: the oldest boot's samples, up to [chunkRows]. */
    @Transaction
    protected open suspend fun compactSampleChunk(
        cutBootSeq: Int,
        cutElapsedNs: Long,
        resolutionS: Int,
        chunkRows: Int,
    ): Int {
        val bootSeq = oldestSampleBoot() ?: return 0
        if (bootSeq > cutBootSeq) return 0
        var end = if (bootSeq < cutBootSeq) Long.MAX_VALUE else SampleBuckets.startOf(cutElapsedNs, resolutionS)
        var rows = samplesBefore(bootSeq, end, chunkRows + 1)
        if (rows.size > chunkRows) {
            // The chunk ends inside a bucket: stop before it, or take the whole bucket if it is the first.
            val first = SampleBuckets.startOf(rows.first().elapsedNs, resolutionS)
            end = SampleBuckets.startOf(rows.last().elapsedNs, resolutionS)
            if (end == first) end = first + SampleBuckets.lengthNs(resolutionS)
            rows = samplesBefore(bootSeq, end, Int.MAX_VALUE)
        }
        if (rows.isEmpty()) return 0
        insertAggregates(SampleBuckets.ofSamples(rows, resolutionS))
        return deleteSamplesBefore(bootSeq, end)
    }

    @Transaction
    protected open suspend fun compactAggregateChunk(
        cutBootSeq: Int,
        cutElapsedNs: Long,
        fromResolutionS: Int,
        toResolutionS: Int,
        chunkRows: Int,
    ): Int {
        val bootSeq = oldestAggregateBoot(fromResolutionS) ?: return 0
        if (bootSeq > cutBootSeq) return 0
        var end = if (bootSeq < cutBootSeq) Long.MAX_VALUE else SampleBuckets.startOf(cutElapsedNs, toResolutionS)
        var rows = aggregatesBefore(fromResolutionS, bootSeq, end, chunkRows + 1)
        if (rows.size > chunkRows) {
            val first = SampleBuckets.startOf(rows.first().startElapsedNs, toResolutionS)
            end = SampleBuckets.startOf(rows.last().startElapsedNs, toResolutionS)
            if (end == first) end = first + SampleBuckets.lengthNs(toResolutionS)
            rows = aggregatesBefore(fromResolutionS, bootSeq, end, Int.MAX_VALUE)
        }
        if (rows.isEmpty()) return 0
        insertAggregates(SampleBuckets.ofAggregates(rows, toResolutionS))
        return deleteAggregatesBefore(fromResolutionS, bootSeq, end)
    }

    @Query("SELECT MIN(bootSeq) FROM battery_sample")
    protected abstract suspend fun oldestSampleBoot(): Int?

    @Query("SELECT MIN(bootSeq) FROM sample_aggregate WHERE resolutionS = :resolutionS")
    protected abstract suspend fun oldestAggregateBoot(resolutionS: Int): Int?

    @Query(
        "SELECT * FROM battery_sample WHERE bootSeq = :bootSeq AND elapsedNs < :endElapsedNs " +
            "ORDER BY elapsedNs LIMIT :limit",
    )
    protected abstract suspend fun samplesBefore(
        bootSeq: Int,
        endElapsedNs: Long,
        limit: Int,
    ): List<BatterySampleEntity>

    @Query(
        "SELECT * FROM sample_aggregate WHERE resolutionS = :resolutionS AND bootSeq = :bootSeq " +
            "AND startElapsedNs < :endElapsedNs ORDER BY startElapsedNs LIMIT :limit",
    )
    protected abstract suspend fun aggregatesBefore(
        resolutionS: Int,
        bootSeq: Int,
        endElapsedNs: Long,
        limit: Int,
    ): List<SampleAggregateEntity>

    /** A bucket is built once; a second insert of its key is a bug and aborts the transaction. */
    @Insert
    protected abstract suspend fun insertAggregates(aggregates: List<SampleAggregateEntity>)

    @Query("DELETE FROM battery_sample WHERE bootSeq = :bootSeq AND elapsedNs < :endElapsedNs")
    protected abstract suspend fun deleteSamplesBefore(
        bootSeq: Int,
        endElapsedNs: Long,
    ): Int

    @Query(
        "DELETE FROM sample_aggregate WHERE resolutionS = :resolutionS AND bootSeq = :bootSeq " +
            "AND startElapsedNs < :endElapsedNs",
    )
    protected abstract suspend fun deleteAggregatesBefore(
        resolutionS: Int,
        bootSeq: Int,
        endElapsedNs: Long,
    ): Int

    private companion object {
        const val CHUNK_ROWS = 5_000

        /** The aggregates that replace raw samples after 14 days (SPEC 7.7). */
        const val MINUTE_S = 60
    }
}

/** The result of [SampleDao.timeAtOrAbove]. */
public data class LevelTime(
    val atOrAboveMs: Long,
    val watchedMs: Long,
)

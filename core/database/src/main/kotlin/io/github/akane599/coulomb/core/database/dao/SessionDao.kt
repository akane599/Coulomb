// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import io.github.akane599.coulomb.core.database.entity.CapacityEstimateEntity
import io.github.akane599.coulomb.core.database.entity.ChargeDetailEntity
import io.github.akane599.coulomb.core.database.entity.ChargerProfileEntity
import io.github.akane599.coulomb.core.database.entity.HealthSummaryEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.SessionKind
import kotlinx.coroutines.flow.Flow

/** Sessions, their charge details, chargers, capacity estimates and daily health (SPEC 6.3, 7.7). */
@Dao
public interface SessionDao {
    @Insert
    public suspend fun insert(session: SessionEntity): Long

    @Update
    public suspend fun update(session: SessionEntity)

    /** Sessions not yet closed, oldest first. */
    @Query("SELECT * FROM session WHERE closeReason IS NULL ORDER BY bootSeq, startElapsedNs")
    public suspend fun open(): List<SessionEntity>

    /** Sessions of [kinds] still open or ending after [fromWall], in time order (ADR-0032 read models). */
    @Query(
        "SELECT * FROM session WHERE kind IN (:kinds) AND (endWall IS NULL OR endWall > :fromWall) " +
            "ORDER BY bootSeq, startElapsedNs",
    )
    public suspend fun overlapping(
        kinds: List<SessionKind>,
        fromWall: Long,
    ): List<SessionEntity>

    /**
     * The longest closed session of [kind] lasting at least [minNs] that ended at or after
     * [sinceWall], with both end snapshots: Drain's "Last night" (ADR-0036). A session lies within
     * one boot, so its elapsed times compare.
     */
    @Query(
        "SELECT * FROM session WHERE kind = :kind AND closeReason IS NOT NULL AND endWall >= :sinceWall " +
            "AND startSnapshotId IS NOT NULL AND endSnapshotId IS NOT NULL AND endElapsedNs - startElapsedNs >= :minNs " +
            "ORDER BY endElapsedNs - startElapsedNs DESC, bootSeq DESC LIMIT 1",
    )
    public suspend fun longestClosed(
        kind: SessionKind,
        sinceWall: Long,
        minNs: Long,
    ): SessionEntity?

    @Query("SELECT * FROM session WHERE kind = :kind ORDER BY bootSeq DESC, startElapsedNs DESC LIMIT :limit")
    public fun observeRecent(
        kind: SessionKind,
        limit: Int,
    ): Flow<List<SessionEntity>>

    @Upsert
    public suspend fun upsertChargeDetail(detail: ChargeDetailEntity)

    @Query("SELECT * FROM charge_detail WHERE sessionId = :sessionId")
    public suspend fun chargeDetail(sessionId: Long): ChargeDetailEntity?

    @Insert
    public suspend fun insertChargerProfile(profile: ChargerProfileEntity): Long

    @Update
    public suspend fun updateChargerProfile(profile: ChargerProfileEntity)

    @Query("DELETE FROM charger_profile WHERE id = :id")
    public suspend fun deleteChargerProfile(id: Long)

    @Query("SELECT * FROM charger_profile ORDER BY lastSeenWall DESC")
    public fun observeChargerProfiles(): Flow<List<ChargerProfileEntity>>

    @Insert
    public suspend fun insertCapacityEstimate(estimate: CapacityEstimateEntity): Long

    /** The latest [limit] estimates, newest first, in the order they were made (SPEC 6.3). */
    @Query("SELECT * FROM capacity_estimate ORDER BY id DESC LIMIT :limit")
    public suspend fun latestCapacityEstimates(limit: Int): List<CapacityEstimateEntity>

    /** The running sum of SPEC 6.4's cycle equivalents over the sessions of [kind] (ADR-0037 §4). */
    @Query("SELECT COALESCE(SUM(cycleEquivalents), 0) FROM session WHERE kind = :kind")
    public suspend fun cycleEquivalentsSum(kind: SessionKind): Double

    @Upsert
    public suspend fun upsertHealthSummary(summary: HealthSummaryEntity)

    @Query("SELECT * FROM health_summary ORDER BY epochDay")
    public fun observeHealthSummaries(): Flow<List<HealthSummaryEntity>>
}

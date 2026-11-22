// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.akane599.coulomb.core.database.entity.ActionLogEntity
import io.github.akane599.coulomb.core.database.entity.InsightEntity
import io.github.akane599.coulomb.core.model.InsightState
import kotlinx.coroutines.flow.Flow

/** Insights (SPEC 9) and the action log (SPEC 13). Order that decides behaviour follows ids. */
@Dao
public interface InsightDao {
    @Insert
    public suspend fun insert(insight: InsightEntity): Long

    @Update
    public suspend fun update(insight: InsightEntity)

    @Query(
        "UPDATE insight SET state = :state, stateChangedWall = :wall, snoozeUntilWall = :snoozeUntilWall " +
            "WHERE id = :id",
    )
    public suspend fun setState(
        id: Long,
        state: InsightState,
        wall: Long,
        snoozeUntilWall: Long? = null,
    )

    @Query("SELECT * FROM insight WHERE state IN (:states) ORDER BY createdWall DESC, id DESC")
    public fun observeInState(states: List<InsightState>): Flow<List<InsightEntity>>

    /**
     * The latest insight of [ruleId] about [subjectKey] (null: the device), for the one-open rule
     * and the 24 h after dismissal (SPEC 9.1).
     */
    @Query("SELECT * FROM insight WHERE ruleId = :ruleId AND subjectKey IS :subjectKey ORDER BY id DESC LIMIT 1")
    public suspend fun latest(
        ruleId: String,
        subjectKey: String?,
    ): InsightEntity?

    @Insert
    public suspend fun insertAction(action: ActionLogEntity): Long

    @Update
    public suspend fun updateAction(action: ActionLogEntity)

    /** Removes a row whose command changed nothing (ADR-0060 §21). */
    @Delete
    public suspend fun deleteAction(action: ActionLogEntity)

    @Query("SELECT * FROM action_log WHERE id = :id")
    public suspend fun action(id: Long): ActionLogEntity?

    /**
     * The newest [limit] actions, newest first: the order they ran in, whatever the clock said.
     * A screen reads a page, not the whole log, which is kept for good (audit PERF-007).
     */
    @Query("SELECT * FROM action_log ORDER BY id DESC LIMIT :limit")
    public fun observeActions(limit: Int): Flow<List<ActionLogEntity>>

    /** As [unreverted], observed. */
    @Query("SELECT * FROM action_log WHERE revertedWall IS NULL AND actionType != 'automation_call' ORDER BY id DESC")
    public fun observeUnreverted(): Flow<List<ActionLogEntity>>

    /** The newest action that is not an automation call's own row: it moves when a command runs or ends. */
    @Query("SELECT * FROM action_log WHERE actionType != 'automation_call' ORDER BY id DESC LIMIT 1")
    public fun observeLatestAction(): Flow<ActionLogEntity?>

    /**
     * Actions not reverted, newest first: the order "revert all" undoes them in (SPEC 13). An
     * automation call's own row has nothing to revert and there can be many, so they stay out.
     */
    @Query("SELECT * FROM action_log WHERE revertedWall IS NULL AND actionType != 'automation_call' ORDER BY id DESC")
    public suspend fun unreverted(): List<ActionLogEntity>

    /** The rows automation wrote after [sinceWall], newest first: what its limits count (ADR-0065 §8). */
    @Query("SELECT * FROM action_log WHERE origin = 'AUTOMATION' AND executedWall > :sinceWall ORDER BY id DESC")
    public suspend fun automationSince(sinceWall: Long): List<ActionLogEntity>

    /** The Doze profile Coulomb applied that is still in force, if there is one. */
    @Query(
        "SELECT * FROM action_log WHERE actionType = 'doze_profile' AND revertedWall IS NULL AND externalDriftWall IS NULL " +
            "ORDER BY id DESC LIMIT 1",
    )
    public suspend fun appliedDozeProfile(): ActionLogEntity?

    /** Every time [profile] was applied by the user or from an insight, newest first: never by automation. */
    @Query("SELECT * FROM action_log WHERE actionType = 'doze_profile' AND origin != 'AUTOMATION' AND target = :profile ORDER BY id DESC")
    public suspend fun userDozeProfiles(profile: String): List<ActionLogEntity>
}

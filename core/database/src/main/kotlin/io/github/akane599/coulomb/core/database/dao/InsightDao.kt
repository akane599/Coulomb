// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.dao

import androidx.room.Dao
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

    /** Every action, newest first: the order they ran in, whatever the clock said. */
    @Query("SELECT * FROM action_log ORDER BY id DESC")
    public fun observeActions(): Flow<List<ActionLogEntity>>

    /** Actions not reverted, newest first: the order "revert all" undoes them in (SPEC 13). */
    @Query("SELECT * FROM action_log WHERE revertedWall IS NULL ORDER BY id DESC")
    public suspend fun unreverted(): List<ActionLogEntity>
}

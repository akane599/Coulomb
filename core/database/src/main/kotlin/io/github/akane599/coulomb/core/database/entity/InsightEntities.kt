// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.ActionOrigin
import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.model.InsightState

/**
 * SPEC 8 `Insight` (SPEC 9). [ruleId] is the rule's id such as `R07`. [subjectKey] names what the
 * insight is about, such as `uid:10123`, `wakelock:<tag>`, `charger:3` or `session:42`, so a rule
 * keeps one open insight per subject; [subjectUid] repeats an app subject's UID for the UI.
 * [stateChangedWall] is when [state] last changed, which the SPEC 9.1 "24 h after dismissal" rule
 * needs. An insight outlives the window it came from: deleting the window clears [windowId].
 */
@Entity(
    tableName = "insight",
    foreignKeys = [
        ForeignKey(
            entity = WindowResultEntity::class,
            parentColumns = ["id"],
            childColumns = ["windowId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("ruleId", "state"), Index("windowId"), Index("ruleId", "subjectKey")],
)
public data class InsightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: String,
    val windowId: Long?,
    val severity: InsightSeverity,
    val subjectKey: String?,
    val subjectUid: Int?,
    val evidenceJson: String,
    val state: InsightState,
    val createdWall: Long,
    val stateChangedWall: Long,
    val snoozeUntilWall: Long?,
)

/**
 * SPEC 8 `ActionLog` (SPEC 13): every action Coulomb ran, kept for good (SPEC 7.7). Revert order is
 * the reverse of [id], never of the wall times.
 */
@Entity(
    tableName = "action_log",
    foreignKeys = [
        ForeignKey(
            entity = InsightEntity::class,
            parentColumns = ["id"],
            childColumns = ["insightId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("insightId")],
)
public data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionType: String,
    val target: String,
    val beforeValue: String?,
    val afterValue: String,
    val tier: AccessTier,
    val executedWall: Long,
    val revertedWall: Long?,
    val origin: ActionOrigin,
    /** The insight the action came from, for [ActionOrigin.INSIGHT]. */
    val insightId: Long?,
    /** When something outside Coulomb was seen changing the value back, and the value it set. */
    val externalDriftWall: Long?,
    val externalDriftValue: String?,
)

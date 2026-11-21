// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.model.InsightState

/** The latest stored insight of a rule about a subject, as far as the lifecycle needs it. */
public data class StoredInsight(
    val id: Long,
    val severity: InsightSeverity,
    val state: InsightState,
    val stateChangedWallMs: Long,
    val snoozeUntilWallMs: Long?,
)

/** What becomes of a candidate (SPEC 9.1, 9.4). */
public sealed interface Decision {
    /** Nothing changes: the subject already has this insight, or the user asked not to see it yet. */
    public data object Keep : Decision

    /** A new insight is stored as `NEW`. */
    public data object Open : Decision

    /** The open insight [id] takes the higher severity and the new evidence, and is `NEW` again. */
    public data class Raise(
        val id: Long,
    ) : Decision
}

/**
 * SPEC 9.1: one open insight per rule and subject; a finding comes back only 24 h after it was
 * dismissed or acted on, or when its severity rises. SPEC 9.4: a snooze hides it for its time.
 *
 * - No insight yet: open one.
 * - `NEW` or `SEEN`: it is the open one. A higher severity raises it; anything else changes nothing.
 * - `SNOOZED`: quiet until the snooze ends. Then, or at once when the severity is higher, a finding
 *   opens a new insight; the snoozed one stays as history.
 * - `DISMISSED` or `ACTED`: a new insight after 24 h, or at once when the severity is higher.
 */
public object InsightLifecycle {
    public const val RETRIGGER_AFTER_MS: Long = 24 * 60 * 60 * 1000L

    public fun decide(
        candidate: InsightSeverity,
        latest: StoredInsight?,
        nowWallMs: Long,
    ): Decision {
        if (latest == null) return Decision.Open
        val rises = candidate > latest.severity
        return when (latest.state) {
            InsightState.NEW, InsightState.SEEN -> {
                if (rises) Decision.Raise(latest.id) else Decision.Keep
            }

            InsightState.SNOOZED -> {
                val over = nowWallMs >= (latest.snoozeUntilWallMs ?: latest.stateChangedWallMs)
                if (rises || over) Decision.Open else Decision.Keep
            }

            InsightState.DISMISSED, InsightState.ACTED -> {
                if (rises || nowWallMs - latest.stateChangedWallMs >= RETRIGGER_AFTER_MS) Decision.Open else Decision.Keep
            }
        }
    }
}

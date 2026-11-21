// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import io.github.akane599.coulomb.core.analytics.DozeChange
import io.github.akane599.coulomb.core.analytics.DozeProfile
import io.github.akane599.coulomb.core.model.ActionOrigin
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket

/**
 * The kinds of action the log holds (SPEC 13.2 as ADR-0060 keeps it). [id] is what the action log
 * stores and never changes. A [stateful] action left a value that can be put back; a [watched]
 * one is re-read at each heavy snapshot for drift.
 */
public enum class ActionType(
    public val id: String,
    public val stateful: Boolean,
    public val watched: Boolean,
) {
    FORCE_STOP("force_stop", stateful = false, watched = false),
    RESTRICT_BACKGROUND("restrict_background", stateful = true, watched = true),
    STANDBY_BUCKET("standby_bucket", stateful = true, watched = true),
    DOZE_ALLOWLIST("doze_allowlist", stateful = true, watched = true),
    DOZE_PROFILE("doze_profile", stateful = true, watched = true),

    /** No write: the value found on the phone is taken as the baseline (ADR-0060 §19). */
    DOZE_TAKEOVER("doze_takeover", stateful = true, watched = true),

    /** Plugging in and a restart clear it by design, so it is not watched (ADR-0060 §13). */
    BATTERY_SAVER("battery_saver", stateful = true, watched = false),
    RESET_STATS("reset_stats", stateful = false, watched = false),

    /**
     * A call through the Automation API (SPEC 12.7) that is not an action of its own: a snapshot
     * asked for, the charge target set, or a call refused. The target is the call's name
     * (ADR-0065 §8); a Doze profile applied by a call is a [DOZE_PROFILE] row with the origin.
     */
    AUTOMATION_CALL("automation_call", stateful = false, watched = false),
    ;

    public companion object {
        public fun of(id: String): ActionType? = entries.firstOrNull { it.id == id }
    }
}

public enum class ActionRisk { LOW, MEDIUM, HIGH }

/** What the user asks the Action Center to do. */
public sealed interface ActionRequest {
    public val type: ActionType

    public data class ForceStop(
        val packageName: String,
    ) : ActionRequest {
        override val type: ActionType get() = ActionType.FORCE_STOP
    }

    public data class RestrictBackground(
        val packageName: String,
    ) : ActionRequest {
        override val type: ActionType get() = ActionType.RESTRICT_BACKGROUND
    }

    /** [bucket] is Rare or Restricted (SPEC 13.2). */
    public data class SetStandbyBucket(
        val packageName: String,
        val bucket: StandbyBucket,
    ) : ActionRequest {
        override val type: ActionType get() = ActionType.STANDBY_BUCKET
    }

    public data class DozeAllowlist(
        val packageName: String,
        val listed: Boolean,
    ) : ActionRequest {
        override val type: ActionType get() = ActionType.DOZE_ALLOWLIST
    }

    public data class ApplyDozeProfile(
        val profile: DozeProfile,
    ) : ActionRequest {
        override val type: ActionType get() = ActionType.DOZE_PROFILE
    }

    /** Takes the Doze value found on the phone as Coulomb's baseline, under [name] (SPEC 13.3 "Imported"). */
    public data class TakeOverDoze(
        val name: String,
    ) : ActionRequest {
        override val type: ActionType get() = ActionType.DOZE_TAKEOVER
    }

    public data class BatterySaver(
        val on: Boolean,
    ) : ActionRequest {
        override val type: ActionType get() = ActionType.BATTERY_SAVER
    }

    public data object ResetBatteryStats : ActionRequest {
        override val type: ActionType get() = ActionType.RESET_STATS
    }
}

/** Why an action cannot run, or was not run. Each is a sentence on the screen. */
public enum class ActionBlock {
    /** The Action Center is off (SPEC 13). */
    OFF,

    /** No worker: the manager is stopped or not authorized. */
    NO_WORKER,

    /** The current value could not be read, so nothing is written over it. */
    UNREADABLE,

    /** Coulomb itself, the manager it runs through, or a system app. */
    PROTECTED,
    NOT_INSTALLED,

    /** The value is already what the action would set. */
    ALREADY,

    /** Restrict background: the app has no plain "allow" to go back to, or a UID-wide mode decides. */
    NOT_PLAIN_ALLOW,

    /** Standby bucket: Android exempts the app, or it is already further out than asked. */
    BUCKET_FIXED,

    /** Doze allowlist: a system entry, which no command removes. */
    SYSTEM_LISTED,

    /** Battery saver cannot change while the phone charges. */
    CHARGING,

    /** Doze: the value was set by something else and was not taken over (SPEC 13.1). */
    MANAGED_EXTERNALLY,

    /** Doze: the value on the phone is not a list of pairs, so it cannot be rewritten safely. */
    MALFORMED,

    /** Doze: the profile changes nothing on this phone. */
    NOTHING_TO_CHANGE,

    /** Doze: a profile is applied; return to stock before applying another, or before releasing the baseline. */
    PROFILE_ACTIVE,

    /** Doze: there is no outside value to take over. */
    NOTHING_TO_TAKE_OVER,

    /** Coulomb changed this already and the change is in force: revert it before changing it again. */
    IN_FORCE,

    /** Revert: the row is reverted already, has drifted, or holds nothing to put back. */
    NOT_ACTIVE,
}

/**
 * An action before it runs (SPEC 13.1): the exact command, the value now and the value after, and
 * the command that undoes it. With a [block] it cannot run, and the rest says what would have.
 */
public data class ActionPreview(
    val request: ActionRequest,
    val risk: ActionRisk,
    /** The command as one `adb shell` line; null for an action that writes nothing. */
    val command: String?,
    /** The undoing command; null where there is nothing to undo. */
    val revertCommand: String?,
    /** The value now, in the log's form ([ActionValues]); null when unset or unread. */
    val before: String?,
    val after: String?,
    val block: ActionBlock?,
    /** For a Doze profile: each key it changes. */
    val dozeChanges: List<DozeChange> = emptyList(),
)

/** How an action, or a revert, ended. */
public sealed interface ActionResult {
    /** Written and read back. [value] is what the phone now holds, which Android may have set short of what was asked. */
    public data class Done(
        val id: Long,
        val value: String,
    ) : ActionResult

    /** Not run. */
    public data class Blocked(
        val reason: ActionBlock,
    ) : ActionResult

    /** The command ran and the value did not change; [value] is what the phone holds. Nothing is logged. */
    public data class Unchanged(
        val value: String?,
    ) : ActionResult

    /** The command ran and the value could not be read back. The row stays, so the change is on record. */
    public data class Unverified(
        val id: Long,
    ) : ActionResult

    /** Revert only: the value is no longer the one Coulomb left, so it was not overwritten (SPEC 13.1). */
    public data class Drifted(
        val id: Long,
        val value: String?,
    ) : ActionResult

    /** The command failed; [detail] is what it printed. */
    public data class Failed(
        val detail: String?,
    ) : ActionResult
}

/** One row of the action log as the screens read it. */
public data class ActionRecord(
    val id: Long,
    /** Null for a row written by a build that knew a type this one does not. */
    val type: ActionType?,
    /** The package, the profile's name or the imported value's name. */
    val target: String,
    val before: String?,
    val after: String,
    val executedWallMs: Long,
    val revertedWallMs: Long?,
    val origin: ActionOrigin,
    val driftWallMs: Long?,
    val driftValue: String?,
    /** The command that puts [before] back, for the day the worker is not there (ADR-0060 §24). */
    val revertCommand: String?,
) {
    /** Still in force as far as Coulomb knows: something to revert, and to watch. */
    val active: Boolean get() = type?.stateful == true && revertedWallMs == null && driftWallMs == null
}

/**
 * How an [ActionType.AUTOMATION_CALL] row is written (ADR-0065 §8). The target is the call's name
 * and, where the caller shared it, the caller's package after a space. "Before" is the argument or
 * the value replaced; "after" is the new value, [ACCEPTED], or [REFUSED] followed by the reason.
 */
public object AutomationCalls {
    public const val SNAPSHOT_NOW: String = "snapshot_now"
    public const val SET_CHARGE_TARGET: String = "set_charge_target"
    public const val APPLY_DOZE_PROFILE: String = "apply_doze_profile"

    /** A snapshot that was queued. */
    public const val ACCEPTED: String = "accepted"
    public const val REFUSED: String = "refused:"

    /** "After" of a call that returned the phone to its own Doze values. */
    public const val STOCK: String = "stock"

    public fun target(
        name: String,
        caller: String?,
    ): String = if (caller.isNullOrBlank()) name else "$name $caller"

    public fun name(target: String): String = target.substringBefore(' ')

    public fun caller(target: String): String? = target.substringAfter(' ', "").ifEmpty { null }

    /** The reason of a refused call; null for a call that was carried out. */
    public fun refusal(after: String): String? = after.removePrefix(REFUSED).takeIf { after.startsWith(REFUSED) }
}

/** The values the action log stores, by action (ADR-0060 §21). */
public object ActionValues {
    public const val LISTED: String = "listed"
    public const val UNLISTED: String = "unlisted"
    public const val ON: String = "on"
    public const val OFF: String = "off"
    public const val STOPPED: String = "stopped"
    public const val RESET: String = "reset"

    public fun listed(listed: Boolean): String = if (listed) LISTED else UNLISTED

    public fun on(on: Boolean): String = if (on) ON else OFF
}

/** Who holds `device_idle_constants` (SPEC 13.1 "External managers"; ADR-0060 §19). */
public enum class DozeOwner {
    /** Unset or empty: nothing to preserve. */
    NOBODY,

    /** A value Coulomb did not write and has not taken over. */
    EXTERNAL,

    /** The value found, taken over as the baseline; no profile on top. */
    BASELINE,

    /** A Coulomb profile is applied. */
    PROFILE,
}

/** The Doze section of the Action Center. */
public data class DozeState(
    val owner: DozeOwner,
    /** The setting now; null when unset. */
    val value: String?,
    /** The applied profile under [DozeOwner.PROFILE]. */
    val profile: DozeProfile?,
    /** The imported value's name under [DozeOwner.BASELINE] or [DozeOwner.PROFILE] over one. */
    val importedName: String?,
    /** How soon after the screen goes off the phone can be in deep idle, by its values now; null when not listed. */
    val idleAfterMs: Long?,
)

/** What a screen of actions shows: each request against the phone as it is now, and the Doze setting when asked for. */
public data class ActionBoard(
    val previews: List<ActionPreview>,
    val doze: DozeState?,
)

/** Whether the Action Center is on (SPEC 14.4). The engine asks before every action (ADR-0060 §4). */
public fun interface ActionGate {
    public suspend fun enabled(): Boolean
}

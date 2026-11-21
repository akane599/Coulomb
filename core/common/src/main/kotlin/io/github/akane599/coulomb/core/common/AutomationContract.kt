// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.common

/**
 * The Automation API's names and numbers, version 1 (SPEC 12.7, ADR-0065), as other apps use
 * them; `docs/automation-api.md` describes them and `docs/automation-api-v1.json` pins them.
 * Every name starts with the application id, so a build with another id does not answer for this
 * one. A version only ever gains names; a change of meaning is a new version beside it.
 */
public object AutomationContract {
    /** In `/v1/info` and in every broadcast's result extras. */
    public const val API_VERSION: Int = 1

    /** The permission a caller holds, defined by Coulomb as `dangerous`: the user grants it per app. */
    public fun permission(applicationId: String): String = "$applicationId.permission.AUTOMATE"

    public fun actionSnapshotNow(applicationId: String): String = "$applicationId.action.SNAPSHOT_NOW"

    public fun actionSetChargeTarget(applicationId: String): String = "$applicationId.action.SET_CHARGE_TARGET"

    public fun actionApplyDozeProfile(applicationId: String): String = "$applicationId.action.APPLY_DOZE_PROFILE"

    /** An int from 50 to 100, or the same as a decimal string. */
    public fun extraChargeTargetPct(applicationId: String): String = "$applicationId.extra.CHARGE_TARGET_PCT"

    /** One of [DOZE_PROFILES]. */
    public fun extraDozeProfile(applicationId: String): String = "$applicationId.extra.DOZE_PROFILE"

    /** In the result extras: [API_VERSION]. */
    public fun extraApiVersion(applicationId: String): String = "$applicationId.extra.API_VERSION"

    /** In the result extras of a call refused for its rate: the seconds until the next may be accepted. */
    public fun extraRetryAfterS(applicationId: String): String = "$applicationId.extra.RETRY_AFTER_S"

    public const val DOZE_STOCK: String = "stock"
    public const val DOZE_BALANCED: String = "balanced"
    public const val DOZE_AGGRESSIVE: String = "aggressive"
    public val DOZE_PROFILES: List<String> = listOf(DOZE_STOCK, DOZE_BALANCED, DOZE_AGGRESSIVE)

    /**
     * An ordered broadcast's result code; the result data is the reason, one of [Reason]'s ids or
     * the Action Center's own. Never 0, which is what a broadcast nobody answered leaves.
     */
    public object Result {
        /** Carried out and, for a change to the phone, read back. */
        public const val DONE: Int = 1

        /** A snapshot was asked for and taken, unless one was taken in the last minute. */
        public const val ACCEPTED: Int = 2

        /** Already so; nothing was changed. */
        public const val UNCHANGED: Int = 3

        /** An extra is missing or outside its range. Nothing was changed. */
        public const val INVALID: Int = 10
        public const val RATE_LIMITED: Int = 11

        /** Refused; the reason says why. */
        public const val BLOCKED: Int = 12
        public const val FAILED: Int = 13

        /** The command ran and the value could not be read back. */
        public const val UNVERIFIED: Int = 14
    }

    /** The reasons the API itself gives, as result data. */
    public object Reason {
        public const val OK: String = "ok"
        public const val OFF: String = "off"
        public const val UNKNOWN_ACTION: String = "unknown_action"
        public const val BAD_TARGET: String = "bad_target"
        public const val BAD_PROFILE: String = "bad_profile"
        public const val RATE_LIMITED: String = "rate_limited"
        public const val SCREEN_OFF: String = "screen_off"

        /** A Doze profile is applied by a call only after the user has applied the same change here. */
        public const val NEEDS_FIRST_APPLY: String = "needs_first_apply"

        /** Another profile is applied; "stock" first. */
        public const val PROFILE_ACTIVE: String = "profile_active"

        /** The Action Center, which applies a Doze profile, is switched off in Coulomb. */
        public const val ACTION_CENTER_OFF: String = "action_center_off"
        public const val DRIFTED: String = "drifted"
        public const val FAILED: String = "failed"
        public const val UNVERIFIED: String = "unverified"

        /** The answer was due before the work ended; Coulomb's action log has the outcome. */
        public const val TIMEOUT: String = "timeout"
    }

    /** The read-only provider: `content://<authority>/v1/<path>`. */
    public fun authority(applicationId: String): String = "$applicationId.automation"

    public const val PATH_INFO: String = "v1/info"
    public const val PATH_LEVEL: String = "v1/level"
    public const val PATH_RATES: String = "v1/rates"
    public const val PATH_NIGHT: String = "v1/night"
    public const val PATH_DRAINS: String = "v1/drains"
    public val PATHS: List<String> = listOf(PATH_INFO, PATH_LEVEL, PATH_RATES, PATH_NIGHT, PATH_DRAINS)

    /**
     * Every path answers with the same columns, one row per figure. A figure Coulomb does not have
     * is a row with a null [VALUE] and a [STATE] other than [STATE_OK], never a missing row.
     */
    public const val METRIC: String = "metric"

    /** 1 to 3 for the drains; 0 elsewhere. */
    public const val RANK: String = "rank"

    /** Kept for a later version; null in every row of version 1. */
    public const val SUBJECT: String = "subject"

    /** For a drain: the app's name. */
    public const val SUBJECT_LABEL: String = "subject_label"
    public const val VALUE: String = "value"

    /** A figure that is a range has [LOW] and [HIGH] and no [VALUE]. */
    public const val LOW: String = "low"
    public const val HIGH: String = "high"

    /** A figure that is a word, such as the power state. */
    public const val TEXT: String = "text"
    public const val UNIT: String = "unit"

    /** `measured`, `modeled`, `derived` or `estimated` (SPEC 1.1). */
    public const val PROVENANCE: String = "provenance"

    /** 0 to 1 where the figure has one. */
    public const val QUALITY: String = "quality"
    public const val STATE: String = "state"

    /** The span the figure covers, where it has one. */
    public const val FROM_WALL_MS: String = "from_wall_ms"
    public const val TO_WALL_MS: String = "to_wall_ms"

    /** When Coulomb read it: figures are as of the last stored snapshot, not of the query. */
    public const val AS_OF_WALL_MS: String = "as_of_wall_ms"
    public val COLUMNS: List<String> =
        listOf(
            METRIC,
            RANK,
            SUBJECT,
            SUBJECT_LABEL,
            VALUE,
            LOW,
            HIGH,
            TEXT,
            UNIT,
            PROVENANCE,
            QUALITY,
            STATE,
            FROM_WALL_MS,
            TO_WALL_MS,
            AS_OF_WALL_MS,
        )

    public const val STATE_OK: String = "ok"

    /** Not enough has been recorded yet (SPEC 6.2). */
    public const val STATE_COLLECTING: String = "collecting"
    public const val STATE_UNAVAILABLE: String = "unavailable"

    /** The figure has no meaning now, as a discharge rate has on the charger. */
    public const val STATE_NOT_APPLICABLE: String = "not_applicable"

    public const val UNIT_PCT: String = "pct"
    public const val UNIT_PCT_PER_H: String = "pct_per_h"
    public const val UNIT_MIN: String = "min"

    /** A drain's share of everything used since the phone was unplugged. */
    public const val UNIT_PCT_OF_WINDOW: String = "pct_of_window"

    /** The metrics of each path, in the order of their rows. */
    public val METRICS: Map<String, List<String>> =
        mapOf(
            PATH_INFO to listOf("api_version", "app_version", "monitoring_mode"),
            PATH_LEVEL to listOf("level", "power_state"),
            PATH_RATES to listOf("rate_screen_on", "rate_screen_off", "time_left"),
            PATH_NIGHT to
                listOf("duration", "level_start", "level_end", "rate", "deep_sleep", "deep_doze_reached", "deep_doze_after"),
            PATH_DRAINS to listOf("drain_share", "drain_share", "drain_share"),
        )
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.actions

import androidx.annotation.StringRes
import io.github.akane599.coulomb.core.analytics.DozeProfile
import io.github.akane599.coulomb.core.collect.actions.ActionBlock
import io.github.akane599.coulomb.core.collect.actions.ActionRequest
import io.github.akane599.coulomb.core.collect.actions.ActionRisk
import io.github.akane599.coulomb.core.collect.actions.ActionType
import io.github.akane599.coulomb.core.collect.actions.ActionValues
import io.github.akane599.coulomb.core.model.cmd.AppOpMode
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket

/** The words for the Action Center's actions, values and refusals. */
internal object ActionWords {
    /** What the action is called where it is offered. */
    @StringRes
    fun title(request: ActionRequest): Int =
        when (request) {
            is ActionRequest.ForceStop -> {
                R.string.actions_force_stop
            }

            is ActionRequest.RestrictBackground -> {
                R.string.actions_restrict
            }

            is ActionRequest.SetStandbyBucket -> {
                if (request.bucket ==
                    StandbyBucket.RARE
                ) {
                    R.string.actions_bucket_rare
                } else {
                    R.string.actions_bucket_restricted
                }
            }

            is ActionRequest.DozeAllowlist -> {
                if (request.listed) R.string.actions_allowlist_add else R.string.actions_allowlist_remove
            }

            is ActionRequest.ApplyDozeProfile -> {
                profile(request.profile)
            }

            is ActionRequest.TakeOverDoze -> {
                R.string.actions_doze_take_over
            }

            is ActionRequest.BatterySaver -> {
                if (request.on) R.string.actions_saver_turn_on else R.string.actions_saver_turn_off
            }

            ActionRequest.ResetBatteryStats -> {
                R.string.actions_reset
            }
        }

    /** What it does and what it can cost (SPEC 13.1 "the expected effect"). */
    @StringRes
    fun effect(request: ActionRequest): Int =
        when (request) {
            is ActionRequest.ForceStop -> {
                R.string.actions_force_stop_effect
            }

            is ActionRequest.RestrictBackground -> {
                R.string.actions_restrict_effect
            }

            is ActionRequest.SetStandbyBucket -> {
                if (request.bucket ==
                    StandbyBucket.RARE
                ) {
                    R.string.actions_bucket_rare_effect
                } else {
                    R.string.actions_bucket_restricted_effect
                }
            }

            is ActionRequest.DozeAllowlist -> {
                if (request.listed) R.string.actions_allowlist_add_effect else R.string.actions_allowlist_remove_effect
            }

            is ActionRequest.ApplyDozeProfile -> {
                R.string.actions_profile_effect
            }

            is ActionRequest.TakeOverDoze -> {
                R.string.actions_take_over_effect
            }

            is ActionRequest.BatterySaver -> {
                if (request.on) R.string.actions_saver_on_detail else R.string.actions_saver_off_detail
            }

            ActionRequest.ResetBatteryStats -> {
                R.string.actions_reset_effect
            }
        }

    /** A log row's name: the kind of action, whatever its direction. */
    @StringRes
    fun logTitle(type: ActionType?): Int =
        when (type) {
            ActionType.FORCE_STOP -> R.string.actions_force_stop
            ActionType.RESTRICT_BACKGROUND -> R.string.actions_restrict
            ActionType.STANDBY_BUCKET -> R.string.actions_log_bucket
            ActionType.DOZE_ALLOWLIST -> R.string.actions_log_allowlist
            ActionType.DOZE_PROFILE -> R.string.actions_log_profile
            ActionType.DOZE_TAKEOVER -> R.string.actions_log_take_over
            ActionType.BATTERY_SAVER -> R.string.actions_saver
            ActionType.RESET_STATS -> R.string.actions_reset
            null -> R.string.actions_unknown
        }

    @StringRes
    fun profile(profile: DozeProfile): Int =
        when (profile) {
            DozeProfile.BALANCED -> R.string.actions_doze_balanced
            DozeProfile.AGGRESSIVE -> R.string.actions_doze_aggressive
        }

    @StringRes
    fun risk(risk: ActionRisk): Int =
        when (risk) {
            ActionRisk.LOW -> R.string.actions_risk_low
            ActionRisk.MEDIUM -> R.string.actions_risk_medium
            ActionRisk.HIGH -> R.string.actions_risk_high
        }

    /** Why it cannot run. [ActionBlock.NOTHING_TO_CHANGE] has a sentence with the phone's own time where that is known. */
    @StringRes
    fun block(block: ActionBlock): Int =
        when (block) {
            ActionBlock.OFF -> R.string.actions_block_off
            ActionBlock.NO_WORKER -> R.string.actions_block_no_worker
            ActionBlock.UNREADABLE -> R.string.actions_block_unreadable
            ActionBlock.PROTECTED -> R.string.actions_block_protected
            ActionBlock.NOT_INSTALLED -> R.string.actions_block_not_installed
            ActionBlock.ALREADY -> R.string.actions_block_already
            ActionBlock.NOT_PLAIN_ALLOW -> R.string.actions_block_not_plain_allow
            ActionBlock.BUCKET_FIXED -> R.string.actions_block_bucket_fixed
            ActionBlock.SYSTEM_LISTED -> R.string.actions_block_system_listed
            ActionBlock.CHARGING -> R.string.actions_block_charging
            ActionBlock.MANAGED_EXTERNALLY -> R.string.actions_block_external
            ActionBlock.MALFORMED -> R.string.actions_block_malformed
            ActionBlock.NOTHING_TO_CHANGE -> R.string.actions_block_nothing_plain
            ActionBlock.PROFILE_ACTIVE -> R.string.actions_block_profile_active
            ActionBlock.NOTHING_TO_TAKE_OVER -> R.string.actions_block_nothing_to_take
            ActionBlock.IN_FORCE -> R.string.actions_block_in_force
            ActionBlock.NOT_ACTIVE -> R.string.actions_block_not_active
        }

    /**
     * The word for a value of the action log, or null for one that is shown as it is: a Doze
     * setting, or an app-op mode Coulomb never writes.
     */
    @StringRes
    fun value(
        type: ActionType?,
        value: String?,
    ): Int? =
        when (type) {
            ActionType.RESTRICT_BACKGROUND -> {
                MODES[value]
            }

            ActionType.STANDBY_BUCKET -> {
                BUCKETS[value]
            }

            ActionType.DOZE_ALLOWLIST -> {
                if (value ==
                    ActionValues.LISTED
                ) {
                    R.string.actions_value_listed
                } else {
                    R.string.actions_value_unlisted
                }
            }

            ActionType.BATTERY_SAVER -> {
                if (value == ActionValues.ON) R.string.actions_value_on else R.string.actions_value_off
            }

            ActionType.FORCE_STOP -> {
                R.string.actions_value_stopped.takeIf { value == ActionValues.STOPPED }
            }

            ActionType.RESET_STATS -> {
                R.string.actions_value_reset.takeIf { value == ActionValues.RESET }
            }

            ActionType.DOZE_PROFILE, ActionType.DOZE_TAKEOVER -> {
                R.string.actions_value_unset.takeIf { value.isNullOrEmpty() }
            }

            null -> {
                null
            }
        }

    private val MODES =
        mapOf(
            AppOpMode.ALLOW.cliName to R.string.actions_value_allow,
            AppOpMode.IGNORE.cliName to R.string.actions_value_ignore,
        )

    private val BUCKETS =
        mapOf(
            StandbyBucket.EXEMPTED.cliName to R.string.actions_bucket_exempted,
            StandbyBucket.ACTIVE.cliName to R.string.actions_bucket_active,
            StandbyBucket.WORKING_SET.cliName to R.string.actions_bucket_working_set,
            StandbyBucket.FREQUENT.cliName to R.string.actions_bucket_frequent,
            StandbyBucket.RARE.cliName to R.string.actions_bucket_rare_name,
            StandbyBucket.RESTRICTED.cliName to R.string.actions_bucket_restricted_name,
            StandbyBucket.NEVER.cliName to R.string.actions_bucket_never,
        )
}

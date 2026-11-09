// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import androidx.annotation.StringRes
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.Capability
import io.github.akane599.coulomb.core.model.CapabilityId
import io.github.akane599.coulomb.core.model.CapabilityState
import io.github.akane599.coulomb.core.model.FixAction
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeState
import io.github.akane599.coulomb.core.model.WorkerFailure
import io.github.akane599.coulomb.core.model.WorkerStatus

/** What the Privileges screen shows (SPEC 14.3, ADR-0047 §7). */
internal data class PrivilegesUiState(
    val privilege: PrivilegeState = PrivilegeState(),
    val capabilities: List<Capability> = emptyList(),
    val adbCommands: List<String> = emptyList(),
    val probing: Boolean = false,
    /** The last "Re-run probes" failed; the matrix shown is the one stored before it. */
    val probeFailed: Boolean = false,
    val granting: Boolean = false,
    /** The last "Grant through Shizuku" left a grant missing or failed outright. */
    val grantFailed: Boolean = false,
    val export: ExportResult? = null,
) {
    /** When the stored matrix was probed: the latest of its rows, or null before the first run. */
    val probedAtWallMs: Long? by lazy(LazyThreadSafetyMode.NONE) { capabilities.maxOfOrNull { it.probedAtWallMs } }

    /** The matrix by the lowest tier each capability works at (SPEC 4.1), each group in id order. */
    val groups: List<Pair<AccessTier, List<Capability>>> by lazy(LazyThreadSafetyMode.NONE) {
        AccessTier.entries.mapNotNull { tier ->
            capabilities
                .filter { it.tier == tier }
                .sortedBy { it.id.ordinal }
                .takeIf { it.isNotEmpty() }
                ?.let { tier to it }
        }
    }

    /** The bootstrap can run: Shizuku answers and has allowed Coulomb (SPEC 4.4). */
    val canBootstrap: Boolean
        get() = privilege.manager == ManagerStatus.RUNNING && privilege.permission == ManagerPermission.GRANTED
}

internal enum class ExportResult { SAVED, FAILED }

/**
 * Whether what [this] fix asks for is already true now. The matrix is stored, so a fix probed
 * before Shizuku connected (or a grant came back) would otherwise contradict the facts above it.
 */
internal fun FixAction.doneIn(state: PrivilegeState): Boolean =
    when (this) {
        FixAction.CONNECT_SHIZUKU -> state.manager == ManagerStatus.RUNNING || state.manager == ManagerStatus.NOT_RUNNING
        FixAction.START_SHIZUKU -> state.manager == ManagerStatus.RUNNING
        FixAction.GRANT_SHIZUKU_PERMISSION -> state.permission == ManagerPermission.GRANTED
        FixAction.RUN_BOOTSTRAP -> state.grants.durableCore
        FixAction.GRANT_USAGE_ACCESS -> state.grants.usageStats
        FixAction.NEEDS_ROOT -> state.workerUsable && state.server?.isRoot == true
        FixAction.GRANT_NOTIFICATIONS, FixAction.NONE -> false
    }

/** The access tier in plain words. */
@get:StringRes
internal val AccessTier.label: Int
    get() =
        when (this) {
            AccessTier.T3_ROOT -> R.string.privileges_tier_root
            AccessTier.T2_PRIVILEGED_WORKER -> R.string.privileges_tier_worker
            AccessTier.T1_DURABLE_GRANTS -> R.string.privileges_tier_grants
            AccessTier.T0_UNPRIVILEGED -> R.string.privileges_tier_basic
        }

/** A tier's heading in the Capability Matrix. */
@get:StringRes
internal val AccessTier.group: Int
    get() =
        when (this) {
            AccessTier.T0_UNPRIVILEGED -> R.string.privileges_tier_group_t0
            AccessTier.T1_DURABLE_GRANTS -> R.string.privileges_tier_group_t1
            AccessTier.T2_PRIVILEGED_WORKER -> R.string.privileges_tier_group_t2
            AccessTier.T3_ROOT -> R.string.privileges_tier_group_t3
        }

@get:StringRes
internal val CapabilityState.label: Int
    get() =
        when (this) {
            CapabilityState.AVAILABLE -> R.string.privileges_state_available
            CapabilityState.DEGRADED -> R.string.privileges_state_degraded
            CapabilityState.UNAVAILABLE -> R.string.privileges_state_unavailable
        }

/** The helper process (SPEC 4.3) as one phrase; a manager that cannot start it says it is not running. */
@StringRes
internal fun PrivilegeState.helperLabel(): Int =
    when {
        worker == WorkerStatus.UNAVAILABLE -> {
            when (workerFailure) {
                WorkerFailure.BIND_TIMEOUT -> R.string.privileges_helper_timeout
                WorkerFailure.SERVER_TOO_OLD -> R.string.privileges_helper_too_old
                WorkerFailure.VERSION_MISMATCH -> R.string.privileges_helper_mismatch
                WorkerFailure.WORKER_DIED -> R.string.privileges_helper_died
                null -> R.string.privileges_helper_unavailable
            }
        }

        !workerUsable -> {
            R.string.privileges_helper_off
        }

        worker == WorkerStatus.BINDING -> {
            R.string.privileges_helper_starting
        }

        // Idle binds on the next call, so it is as ready as a bound one.
        else -> {
            R.string.privileges_helper_ready
        }
    }

/** A capability's plain name (SPEC 4.6). */
@get:StringRes
internal val CapabilityId.label: Int
    get() =
        when (this) {
            CapabilityId.BATTERY_CHANGED -> R.string.capability_battery_changed
            CapabilityId.CURRENT_NOW -> R.string.capability_current_now
            CapabilityId.CHARGE_COUNTER -> R.string.capability_charge_counter
            CapabilityId.HEALTH_PROPERTIES -> R.string.capability_health_properties
            CapabilityId.BATTERYSTATS_USAGE -> R.string.capability_batterystats_usage
            CapabilityId.BATTERYSTATS_FLAGS -> R.string.capability_batterystats_flags
            CapabilityId.BATTERYSTATS_PROTO -> R.string.capability_batterystats_proto
            CapabilityId.BATTERYSTATS_CHECKIN -> R.string.capability_batterystats_checkin
            CapabilityId.BATTERYSTATS_HISTORY -> R.string.capability_batterystats_history
            CapabilityId.KERNEL_WAKELOCKS -> R.string.capability_kernel_wakelocks
            CapabilityId.WAKEUP_SOURCES -> R.string.capability_wakeup_sources
            CapabilityId.ALARM_DUMP -> R.string.capability_alarm
            CapabilityId.DEVICEIDLE_DUMP -> R.string.capability_deviceidle
            CapabilityId.POWER_DUMP -> R.string.capability_power
            CapabilityId.JOBSCHEDULER_DUMP -> R.string.capability_jobscheduler
            CapabilityId.USAGE_STATS -> R.string.capability_usage_stats
            CapabilityId.STANDBY_BUCKETS -> R.string.capability_standby_buckets
            CapabilityId.POWER_MONITORS -> R.string.capability_power_monitors
            CapabilityId.UID_HEALTH_STATS -> R.string.capability_uid_health_stats
            CapabilityId.THERMAL_DUMP -> R.string.capability_thermal
            CapabilityId.BATTERY_DUMP -> R.string.capability_battery_dump
            CapabilityId.PROTECTION_SETTINGS -> R.string.capability_protection_settings
            CapabilityId.POWER_SUPPLY_SYSFS -> R.string.capability_power_supply
            CapabilityId.SUSPEND_STATS -> R.string.capability_suspend_stats
            CapabilityId.WAKEUP_REASONS -> R.string.capability_wakeup_reasons
            CapabilityId.KERNEL_LOG -> R.string.capability_kernel_log
            CapabilityId.PROMOTED_NOTIFICATIONS -> R.string.capability_promoted_notifications
            CapabilityId.WORKER_BIND -> R.string.capability_worker_bind
        }

/** What would make an unavailable or limited capability work, in a short sentence. */
@get:StringRes
internal val FixAction.label: Int
    get() =
        when (this) {
            FixAction.CONNECT_SHIZUKU -> R.string.fix_connect_shizuku
            FixAction.START_SHIZUKU -> R.string.fix_start_shizuku
            FixAction.GRANT_SHIZUKU_PERMISSION -> R.string.fix_grant_shizuku
            FixAction.RUN_BOOTSTRAP -> R.string.fix_bootstrap
            FixAction.GRANT_USAGE_ACCESS -> R.string.fix_usage_access
            FixAction.GRANT_NOTIFICATIONS -> R.string.fix_notifications
            FixAction.NEEDS_ROOT -> R.string.fix_root
            FixAction.NONE -> R.string.fix_none
        }

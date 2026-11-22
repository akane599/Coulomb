// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.capability

import io.github.akane599.coulomb.core.model.Capability
import io.github.akane599.coulomb.core.model.CapabilityId
import io.github.akane599.coulomb.core.model.CapabilityState
import io.github.akane599.coulomb.core.model.FixAction
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeState

/**
 * The root-only kernel rows of the matrix (S9, S25, S26, S27) and when a new connection must probe
 * them again (ADR-0029 §5). Without root every row is unavailable for a reason the state alone
 * gives, so no file is touched; as root the files are read only when the stored rows were found
 * without it (audit BUG-024).
 */
internal object KernelRows {
    val IDS: List<CapabilityId> =
        listOf(CapabilityId.WAKEUP_SOURCES, CapabilityId.SUSPEND_STATS, CapabilityId.WAKEUP_REASONS, CapabilityId.KERNEL_LOG)

    const val NO_CONNECTION: String = "no Shizuku connection"
    const val NEEDS_ROOT: String = "needs Shizuku started as root"

    /** The reason and fix every kernel row has under [state] without root; null as root. */
    fun withoutRoot(state: PrivilegeState): Pair<String, FixAction>? =
        when {
            !state.workerUsable -> NO_CONNECTION to managerFix(state)
            state.server?.isRoot != true -> NEEDS_ROOT to FixAction.NEEDS_ROOT
            else -> null
        }

    /**
     * Whether the stored kernel rows no longer fit [state]: without root, unless each already says
     * what the state gives; as root, when some were found without it. None stored: the first
     * probe has not run, and it covers them.
     */
    fun stale(
        stored: List<Capability>,
        state: PrivilegeState,
    ): Boolean {
        val rows = stored.filter { it.id in IDS }
        if (rows.isEmpty()) return false
        val known = withoutRoot(state) ?: return rows.any { it.reason == NO_CONNECTION || it.reason == NEEDS_ROOT }
        return rows.size != IDS.size ||
            rows.any { it.state != CapabilityState.UNAVAILABLE || it.reason != known.first || it.fixAction != known.second }
    }
}

/** The fix for a missing or unusable manager connection. */
internal fun managerFix(state: PrivilegeState): FixAction =
    when {
        state.manager == ManagerStatus.NOT_INSTALLED -> FixAction.CONNECT_SHIZUKU
        state.manager != ManagerStatus.RUNNING -> FixAction.START_SHIZUKU
        state.permission != ManagerPermission.GRANTED -> FixAction.GRANT_SHIZUKU_PERMISSION
        !state.grants.durableCore -> FixAction.RUN_BOOTSTRAP
        else -> FixAction.NONE
    }

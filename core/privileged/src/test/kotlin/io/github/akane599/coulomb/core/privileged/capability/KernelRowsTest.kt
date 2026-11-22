// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.capability

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.Capability
import io.github.akane599.coulomb.core.model.CapabilityState
import io.github.akane599.coulomb.core.model.FixAction
import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeState
import io.github.akane599.coulomb.core.model.ServerInfo
import org.junit.Test

/** Audit BUG-024: the kernel rows are probed again when the connection no longer fits them. */
class KernelRowsTest {
    private val durable =
        GrantStatus(batteryStats = true, dump = true, usageStats = true, usageStatsPermission = true, interactAcrossUsers = true)
    private val root =
        PrivilegeState(
            manager = ManagerStatus.RUNNING,
            server = ServerInfo(13, ServerInfo.ROOT_UID, null, false, "fork", "1"),
            permission = ManagerPermission.GRANTED,
            grants = durable,
            connection = 1,
        )
    private val shell = root.copy(server = ServerInfo(13, ServerInfo.SHELL_UID, null, false, "fork", "1"), connection = 2)
    private val stopped = PrivilegeState(manager = ManagerStatus.NOT_RUNNING, grants = durable)

    private fun rows(
        state: CapabilityState,
        reason: String? = null,
        fix: FixAction? = null,
    ) = KernelRows.IDS.map { Capability(it, state, AccessTier.T3_ROOT, reason, fix, 0, "lab", emptyMap()) }

    @Test
    fun rootRowsAreStaleUnderShellOrWithoutAConnection() {
        val found = rows(CapabilityState.AVAILABLE)
        assertThat(KernelRows.stale(found, shell)).isTrue()
        assertThat(KernelRows.stale(found, stopped)).isTrue()
        // A new root connection reads the same files: nothing to do.
        assertThat(KernelRows.stale(found, root.copy(connection = 3))).isFalse()
    }

    @Test
    fun rowsFoundWithoutRootAreReadAgainAsRoot() {
        val needsRoot = rows(CapabilityState.UNAVAILABLE, KernelRows.NEEDS_ROOT, FixAction.NEEDS_ROOT)
        assertThat(KernelRows.stale(needsRoot, shell)).isFalse()
        assertThat(KernelRows.stale(needsRoot, root)).isTrue()
        // A lost connection changes the fix, which the state gives without reading anything.
        assertThat(KernelRows.stale(needsRoot, stopped)).isTrue()
        assertThat(
            KernelRows.stale(rows(CapabilityState.UNAVAILABLE, KernelRows.NO_CONNECTION, FixAction.START_SHIZUKU), stopped),
        ).isFalse()
    }

    @Test
    fun beforeTheFirstProbeNothingIsStale() {
        assertThat(KernelRows.stale(emptyList(), shell)).isFalse()
    }
}

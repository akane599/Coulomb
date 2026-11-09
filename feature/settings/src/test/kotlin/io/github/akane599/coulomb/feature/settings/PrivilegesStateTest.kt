// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import com.google.common.truth.Truth.assertThat
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
import org.junit.Test

class PrivilegesStateTest {
    @Test
    fun theMatrixGroupsByTierInTierThenIdOrderAndSkipsEmptyTiers() {
        val state =
            PrivilegesUiState(
                capabilities =
                    listOf(
                        cap(CapabilityId.KERNEL_LOG, AccessTier.T3_ROOT, 5L),
                        cap(CapabilityId.CURRENT_NOW, AccessTier.T0_UNPRIVILEGED, 9L),
                        cap(CapabilityId.BATTERY_CHANGED, AccessTier.T0_UNPRIVILEGED, 7L),
                    ),
            )
        assertThat(state.groups.map { (tier, rows) -> tier to rows.map { it.id } })
            .containsExactly(
                AccessTier.T0_UNPRIVILEGED to listOf(CapabilityId.BATTERY_CHANGED, CapabilityId.CURRENT_NOW),
                AccessTier.T3_ROOT to listOf(CapabilityId.KERNEL_LOG),
            ).inOrder()
        assertThat(state.probedAtWallMs).isEqualTo(9L)
        assertThat(PrivilegesUiState().probedAtWallMs).isNull()
    }

    @Test
    fun theHelperSaysWhyItIsUnavailableAndIdleCountsAsReady() {
        val usable = PrivilegeState(manager = ManagerStatus.RUNNING, permission = ManagerPermission.GRANTED)
        assertThat(usable.helperLabel()).isEqualTo(R.string.privileges_helper_ready)
        assertThat(usable.copy(worker = WorkerStatus.BINDING).helperLabel()).isEqualTo(R.string.privileges_helper_starting)
        assertThat(usable.copy(worker = WorkerStatus.UNAVAILABLE, workerFailure = WorkerFailure.BIND_TIMEOUT).helperLabel())
            .isEqualTo(R.string.privileges_helper_timeout)
        assertThat(PrivilegeState(manager = ManagerStatus.NOT_RUNNING).helperLabel()).isEqualTo(R.string.privileges_helper_off)
    }

    @Test
    fun theBootstrapNeedsARunningManagerThatAllowedCoulomb() {
        val running = PrivilegeState(manager = ManagerStatus.RUNNING, permission = ManagerPermission.GRANTED)
        assertThat(PrivilegesUiState(privilege = running).canBootstrap).isTrue()
        assertThat(PrivilegesUiState(privilege = running.copy(permission = ManagerPermission.DENIED)).canBootstrap).isFalse()
        assertThat(PrivilegesUiState(privilege = running.copy(manager = ManagerStatus.NOT_RUNNING)).canBootstrap).isFalse()
    }

    @Test
    fun aFixTheLiveStateHasMetIsDone() {
        val connected = PrivilegeState(manager = ManagerStatus.RUNNING, permission = ManagerPermission.GRANTED)
        assertThat(FixAction.CONNECT_SHIZUKU.doneIn(connected)).isTrue()
        assertThat(FixAction.GRANT_SHIZUKU_PERMISSION.doneIn(connected)).isTrue()
        assertThat(FixAction.RUN_BOOTSTRAP.doneIn(connected)).isFalse()
        assertThat(FixAction.NEEDS_ROOT.doneIn(connected)).isFalse()
        assertThat(FixAction.CONNECT_SHIZUKU.doneIn(PrivilegeState(manager = ManagerStatus.NOT_INSTALLED))).isFalse()
        assertThat(FixAction.NONE.doneIn(connected)).isFalse()
    }

    private fun cap(
        id: CapabilityId,
        tier: AccessTier,
        at: Long,
    ) = Capability(id, CapabilityState.AVAILABLE, tier, null, null, at, "f")
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.onboarding

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.model.PrivilegeState
import org.junit.Test

class OnboardingStateTest {
    @Test
    fun theStageFollowsTheManager() {
        assertThat(stage(ManagerStatus.UNKNOWN)).isEqualTo(ShizukuStage.CHECKING)
        assertThat(stage(ManagerStatus.NOT_INSTALLED)).isEqualTo(ShizukuStage.NOT_INSTALLED)
        assertThat(stage(ManagerStatus.NOT_RUNNING)).isEqualTo(ShizukuStage.STOPPED)
        assertThat(stage(ManagerStatus.RUNNING, ManagerPermission.NOT_REQUESTED)).isEqualTo(ShizukuStage.ASK)
        assertThat(stage(ManagerStatus.RUNNING, ManagerPermission.PENDING)).isEqualTo(ShizukuStage.WAITING)
        assertThat(stage(ManagerStatus.RUNNING, ManagerPermission.UNKNOWN)).isEqualTo(ShizukuStage.CHECKING)
    }

    @Test
    fun aHangADenialOrAnOldServerIsAProblemUntilCoulombIsAllowed() {
        assertThat(
            stage(ManagerStatus.RUNNING, ManagerPermission.PENDING, PrivilegeIssue.AUTHORIZATION_HANG),
        ).isEqualTo(ShizukuStage.PROBLEM)
        assertThat(stage(ManagerStatus.RUNNING, ManagerPermission.DENIED)).isEqualTo(ShizukuStage.PROBLEM)
        assertThat(
            stage(ManagerStatus.RUNNING, ManagerPermission.NOT_REQUESTED, PrivilegeIssue.SERVER_TOO_OLD),
        ).isEqualTo(ShizukuStage.PROBLEM)
        // The binder route still grants with an old server, so a granted permission moves on.
        assertThat(stage(ManagerStatus.RUNNING, ManagerPermission.GRANTED, PrivilegeIssue.SERVER_TOO_OLD)).isEqualTo(ShizukuStage.GRANTING)
        assertThat(stage(ManagerStatus.RUNNING, ManagerPermission.GRANTED, done = true)).isEqualTo(ShizukuStage.CONNECTED)
    }

    @Test
    fun basicModeSkipsShizukuAndCountsThreeSteps() {
        assertThat(next(OnboardingStep.PERMISSIONS, OnboardingPath.BASIC)).isEqualTo(OnboardingStep.RESULT)
        assertThat(previous(OnboardingStep.RESULT, OnboardingPath.BASIC)).isEqualTo(OnboardingStep.PERMISSIONS)
        assertThat(next(OnboardingStep.PERMISSIONS, OnboardingPath.SHIZUKU)).isEqualTo(OnboardingStep.SHIZUKU)
        assertThat(previous(OnboardingStep.WELCOME, OnboardingPath.SHIZUKU)).isNull()
        assertThat(next(OnboardingStep.RESULT, OnboardingPath.SHIZUKU)).isNull()
        val basic = OnboardingUiState(step = OnboardingStep.RESULT, path = OnboardingPath.BASIC)
        assertThat(basic.stepNumber to basic.stepCount).isEqualTo(3 to 3)
        val shizuku = OnboardingUiState(step = OnboardingStep.RESULT)
        assertThat(shizuku.stepNumber to shizuku.stepCount).isEqualTo(4 to 4)
    }

    private fun stage(
        manager: ManagerStatus,
        permission: ManagerPermission = ManagerPermission.UNKNOWN,
        issue: PrivilegeIssue? = null,
        done: Boolean = false,
    ) = shizukuStage(PrivilegeState(manager = manager, permission = permission, issue = issue), bootstrapDone = done)
}

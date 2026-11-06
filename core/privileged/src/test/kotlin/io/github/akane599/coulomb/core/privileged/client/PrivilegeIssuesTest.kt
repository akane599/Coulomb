// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.model.WorkerFailure
import org.junit.Test

class PrivilegeIssuesTest {
    private val none = GrantStatus()
    private val all =
        GrantStatus(batteryStats = true, dump = true, usageStats = true, usageStatsPermission = true, interactAcrossUsers = true)

    private fun derive(
        manager: ManagerStatus = ManagerStatus.RUNNING,
        permission: ManagerPermission = ManagerPermission.GRANTED,
        hanging: Boolean = false,
        failure: WorkerFailure? = null,
        grants: GrantStatus = all,
        verifiedBefore: Boolean = true,
    ) = PrivilegeIssues.derive(manager, permission, hanging, failure, grants, verifiedBefore)

    @Test
    fun healthyStateShowsNothing() {
        assertThat(derive()).isNull()
        // A worker that died repeatedly is not the user's problem while T1 still works.
        assertThat(derive(failure = WorkerFailure.WORKER_DIED)).isNull()
    }

    @Test
    fun revokedGrantsComeFirst() {
        assertThat(derive(manager = ManagerStatus.NOT_RUNNING, grants = none)).isEqualTo(PrivilegeIssue.GRANTS_REVOKED)
        assertThat(derive(grants = none, verifiedBefore = false)).isNull()
    }

    @Test
    fun eachSpecRowMapsToItsIssue() {
        assertThat(derive(permission = ManagerPermission.PENDING, hanging = true)).isEqualTo(PrivilegeIssue.AUTHORIZATION_HANG)
        assertThat(derive(permission = ManagerPermission.PENDING)).isNull()
        assertThat(derive(failure = WorkerFailure.BIND_TIMEOUT)).isEqualTo(PrivilegeIssue.AUTHORIZATION_HANG)
        assertThat(derive(failure = WorkerFailure.SERVER_TOO_OLD)).isEqualTo(PrivilegeIssue.SERVER_TOO_OLD)
        assertThat(derive(permission = ManagerPermission.DENIED)).isEqualTo(PrivilegeIssue.PERMISSION_DENIED)
        assertThat(
            derive(manager = ManagerStatus.NOT_RUNNING, permission = ManagerPermission.UNKNOWN),
        ).isEqualTo(PrivilegeIssue.MANAGER_STOPPED)
        assertThat(
            derive(manager = ManagerStatus.NOT_INSTALLED, permission = ManagerPermission.UNKNOWN, grants = none, verifiedBefore = false),
        ).isEqualTo(PrivilegeIssue.MANAGER_NOT_INSTALLED)
    }
}

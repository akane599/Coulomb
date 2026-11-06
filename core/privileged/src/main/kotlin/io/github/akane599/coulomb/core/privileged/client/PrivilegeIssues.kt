// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.model.WorkerFailure

/** Picks the one SPEC 4.5 row to show. Order: what the user can fix fastest and what costs most data. */
internal object PrivilegeIssues {
    fun derive(
        manager: ManagerStatus,
        permission: ManagerPermission,
        permissionRequestHanging: Boolean,
        workerFailure: WorkerFailure?,
        grants: GrantStatus,
        grantsVerifiedBefore: Boolean,
    ): PrivilegeIssue? =
        when {
            grantsVerifiedBefore && !grants.durableCore -> PrivilegeIssue.GRANTS_REVOKED
            permission == ManagerPermission.PENDING && permissionRequestHanging -> PrivilegeIssue.AUTHORIZATION_HANG
            workerFailure == WorkerFailure.BIND_TIMEOUT -> PrivilegeIssue.AUTHORIZATION_HANG
            workerFailure == WorkerFailure.SERVER_TOO_OLD -> PrivilegeIssue.SERVER_TOO_OLD
            permission == ManagerPermission.DENIED -> PrivilegeIssue.PERMISSION_DENIED
            manager == ManagerStatus.NOT_RUNNING -> PrivilegeIssue.MANAGER_STOPPED
            manager == ManagerStatus.NOT_INSTALLED -> PrivilegeIssue.MANAGER_NOT_INSTALLED
            else -> null
        }
}

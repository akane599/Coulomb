// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.akane599.coulomb.core.collect.CapabilityStore
import io.github.akane599.coulomb.core.common.AppScope
import io.github.akane599.coulomb.core.datastore.SettingsStore
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeState
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class CoulombApplication : Application() {
    @Inject lateinit var privileged: PrivilegedAccess

    @Inject lateinit var capabilities: CapabilityStore

    @Inject lateinit var settings: SettingsStore

    @Inject lateinit var scope: AppScope

    override fun onCreate() {
        super.onCreate()
        // SPEC 4.1: T1 grants are re-verified at every start; Shizuku listeners attach here.
        privileged.start()
        scope.launch {
            // Onboarding's result step runs the first probe. After it, an OS update re-probes once the
            // manager's state has settled, so the matrix reflects the access Coulomb has (SPEC 4.6, ADR-0047 §5).
            if (!settings.settings.first().onboarded) return@launch
            privileged.state.first { it.settled() }
            capabilities.probeIfStale()
        }
    }
}

/** Detection is over: no manager, a stopped one (reported only after the boot grace), or a resolved permission. */
private fun PrivilegeState.settled(): Boolean =
    when (manager) {
        ManagerStatus.UNKNOWN -> false
        ManagerStatus.RUNNING -> permission != ManagerPermission.UNKNOWN && permission != ManagerPermission.PENDING
        else -> true
    }

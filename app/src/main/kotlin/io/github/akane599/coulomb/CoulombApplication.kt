// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import io.github.akane599.coulomb.core.collect.CapabilityStore
import io.github.akane599.coulomb.core.common.AppScope
import io.github.akane599.coulomb.core.data.DataTransfer
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.datastore.SettingsStore
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeState
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.ui.format.DisplayUnits
import io.github.akane599.coulomb.surface.PrivilegeNotifications
import io.github.akane599.coulomb.surface.WidgetPreviews
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import javax.inject.Inject

@HiltAndroidApp
class CoulombApplication : Application() {
    @Inject lateinit var privileged: PrivilegedAccess

    @Inject lateinit var capabilities: CapabilityStore

    @Inject lateinit var settings: SettingsStore

    @Inject lateinit var scope: AppScope

    @Inject lateinit var privilegeNotifications: PrivilegeNotifications

    @Inject lateinit var previews: WidgetPreviews

    @Inject lateinit var transfer: DataTransfer

    override fun onCreate() {
        // Read before injection, which is in super.onCreate(): nothing has opened the database yet.
        val firstStart = FirstStart.check(noBackupFilesDir)
        super.onCreate()
        if (firstStart) {
            // Settings found at an install's first start came from a backup of another one (ADR-0062 §15).
            // Once per install, and before the first screen reads `onboarded`.
            runBlocking {
                try {
                    settings.startedFromBackup()
                } catch (e: IOException) {
                    Log.w(TAG, "restored settings not reset", e)
                }
            }
        }
        // SPEC 4.1: T1 grants are re-verified at every start; Shizuku listeners attach here.
        privileged.start()
        scope.launch {
            // Onboarding's result step runs the first probe. After it, an OS update re-probes once the
            // manager's state has settled, so the matrix reflects the access Coulomb has (SPEC 4.6, ADR-0047 §5).
            if (!settings.settings.first().onboarded) return@launch
            privileged.state.first { it.settled() }
            capabilities.probeIfStale()
        }
        scope.launch {
            // SPEC 12.1: a lost access is told once. The state changes with the manager's own events; nothing polls.
            settings.settings.first { it.onboarded }
            privileged.state
                .filter { it.settled() }
                .map { it.issue }
                .distinctUntilChanged()
                .collect(privilegeNotifications::onIssue)
        }
        scope.launch { previews.publishIfNew() }
        // SPEC 14.4 "Units", for every formatter of the process: screens, notifications, widgets and the tile.
        scope.launch {
            settings.settings
                .map { it.fahrenheit }
                .distinctUntilChanged()
                .collect { DisplayUnits.fahrenheit = it }
        }
        // What a killed import or export left behind can be large; it goes at the next start (ADR-0062 §11).
        scope.launch { transfer.sweep() }
    }
}

/** Detection is over: no manager, a stopped one (reported only after the boot grace), or a resolved permission. */
private fun PrivilegeState.settled(): Boolean =
    when (manager) {
        ManagerStatus.UNKNOWN -> false
        ManagerStatus.RUNNING -> permission != ManagerPermission.UNKNOWN && permission != ManagerPermission.PENDING
        else -> true
    }

/**
 * Whether this is the first start of this install. The database and a mark live in the directory
 * Auto Backup never copies (SPEC 14.5), so on a phone restored from a backup neither is there
 * while the settings are; an install from before the mark has its database.
 */
internal object FirstStart {
    private const val MARK = "started"

    /** True once per install; leaves the mark. */
    fun check(noBackup: File): Boolean {
        val mark = File(noBackup, MARK)
        if (mark.exists()) return false
        val first = !File(noBackup, CoulombDatabase.FILE_NAME).exists()
        try {
            mark.createNewFile()
        } catch (e: IOException) {
            Log.w(TAG, "no first-start mark", e)
        }
        return first
    }
}

private const val TAG = "Coulomb"

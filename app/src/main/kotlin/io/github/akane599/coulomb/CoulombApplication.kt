// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb

import android.app.Application
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.HiltAndroidApp
import io.github.akane599.coulomb.automation.AutomationSwitch
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
import io.github.akane599.coulomb.surface.SurfaceObserver
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
            // The start counts as done only once the reset is stored: a failed one runs again at
            // the next start, before restored consents are used (audit DATA-008).
            runBlocking {
                try {
                    settings.startedFromBackup()
                    FirstStart.done(noBackupFilesDir)
                } catch (e: IOException) {
                    Log.w(TAG, "restored settings not reset; again at the next start", e)
                }
            }
            // A backup made before the surfaces' memory was excluded may still bring it: its digest
            // marker names insight ids of the old database and would silence the digest (audit BUG-014).
            // Cleared through the shared instance, which the surfaces may already hold.
            getSharedPreferences(SurfaceObserver.MEMORY, MODE_PRIVATE).edit(commit = true) { clear() }
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
        // SPEC 12.7: the Automation API's receiver and provider are open only while the setting is on.
        scope.launch {
            settings.settings
                .map { it.automation }
                .distinctUntilChanged()
                .collect { AutomationSwitch.apply(this@CoulombApplication, it) }
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
 * Whether this is the first start of this install. The database and the marks live in the directory
 * Auto Backup never copies (SPEC 14.5), so on a phone restored from a backup none is there while the
 * settings are; an install from before the marks has its database. A first start stays pending until
 * [done], so a reset that failed runs again (audit DATA-008).
 */
internal object FirstStart {
    private const val MARK = "started"
    private const val PENDING = "first-start-pending"

    /** True at an install's first start, and at each start after until [done]. */
    fun check(noBackup: File): Boolean {
        val mark = File(noBackup, MARK)
        if (mark.exists()) return false
        val pending = File(noBackup, PENDING)
        if (pending.exists()) return true
        val first = !File(noBackup, CoulombDatabase.FILE_NAME).exists()
        try {
            if (first) pending.createNewFile() else mark.createNewFile()
        } catch (e: IOException) {
            Log.w(TAG, "no first-start mark", e)
        }
        return first
    }

    /** The first start's reset is stored. */
    fun done(noBackup: File) {
        try {
            File(noBackup, MARK).createNewFile()
            File(noBackup, PENDING).delete()
        } catch (e: IOException) {
            Log.w(TAG, "no first-start mark", e)
        }
    }
}

private const val TAG = "Coulomb"

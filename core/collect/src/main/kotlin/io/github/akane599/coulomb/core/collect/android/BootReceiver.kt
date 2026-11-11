// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.android

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.akane599.coulomb.core.model.SnapshotTrigger

/**
 * Starts Full mode after a boot or an update of Coulomb (SPEC 7.2). Both broadcasts let the
 * receiver start a foreground service from the background:
 * - `BOOT_COMPLETED` allows `specialUse` (`FGS_BOOT_COMPLETED_RESTRICTIONS`, ActiveServices
 *   android-17.0.0_r1 L389-404, android-16.0.0_r4 L368-383);
 * - `MY_PACKAGE_REPLACED` is sent with a temporary FGS allowlist (BroadcastHelper
 *   android-17.0.0_r1 L714-720 and L288-298, android-16.0.0_r4 L734-740 and L288-298).
 *
 * Not exported: both come from system_server, whose own pid passes the component check
 * (BroadcastSkipPolicy android-17.0.0_r1 L136-145, android-16.0.0_r4 L133-142;
 * ActivityManagerService.checkComponentPermission android-17.0.0_r1 L6898-6902,
 * android-16.0.0_r4 L6235-6239).
 *
 * One `BOOT_COMPLETED` allows nothing: the one sent when a force-stopped Coulomb's process starts
 * again, for a broadcast or a provider call rather than a launch (`maybeSendBootCompletedLocked`,
 * ActivityManagerService android-17.0.0_r1 L5817-5901; android-16.0.0_r4 L5166-5251 behind the
 * `stayStopped` flag). It carries no allowlist, so the start is refused. Coulomb was force-stopped
 * then, so the receiver logs it and leaves the collector to the next launch (`MainActivity`) rather
 * than crashing. Seen on the phone on 2026-11-11, when Macrobenchmark woke a stopped build.
 */
public class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val trigger =
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED -> SnapshotTrigger.BOOT_COMPLETED
                Intent.ACTION_MY_PACKAGE_REPLACED -> SnapshotTrigger.PACKAGE_REPLACED
                else -> return
            }
        try {
            CollectorService.start(context, trigger)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "collector not started on ${intent.action}; it starts when Coulomb is opened", e)
        }
    }

    private companion object {
        const val TAG = "Coulomb:boot"
    }
}

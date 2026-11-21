// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.akane599.coulomb.core.model.SnapshotTrigger

/**
 * Starts Full mode after a boot or an update of Coulomb (SPEC 7.2); in Lite mode nothing starts
 * (SPEC 7.1, `Collector.start`). Both broadcasts let the
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
        // The mode is in the stored settings, read off this thread; the broadcast stays open until
        // the start is made (goAsync, BroadcastReceiver android-17.0.0_r1 L446, android-16.0.0_r4
        // L443). Both allowances are a temporary allowlist of the app for some seconds, not of this
        // call (UserController.getTemporaryAppAllowlistBroadcastOptions android-17.0.0_r1
        // L4293-4305, android-16.0.0_r4 L4323-4335).
        val pending: PendingResult? = goAsync()
        context.collector().start(trigger) { pending?.finish() }
    }
}

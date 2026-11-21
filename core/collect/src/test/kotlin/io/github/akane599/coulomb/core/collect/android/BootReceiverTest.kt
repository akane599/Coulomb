// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.android

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.Collector
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class BootReceiverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val snapshots = ArrayList<SnapshotTrigger>()

    @Test
    fun aBootOrAnUpdateStartsTheCollectorWithItsTrigger() {
        Collector.startIn(app, lite = false, SnapshotTrigger.BOOT_COMPLETED, snapshots::add)
        Collector.startIn(app, lite = false, SnapshotTrigger.PACKAGE_REPLACED, snapshots::add)
        assertThat(snapshots).isEmpty()
        val started = generateSequence { shadowOf(app).nextStartedService }.toList()
        assertThat(started.map { it.component?.className }).containsExactly(
            CollectorService::class.java.name,
            CollectorService::class.java.name,
        )
        assertThat(started.map { it.getStringExtra(CollectorService.EXTRA_TRIGGER) })
            .containsExactly(SnapshotTrigger.BOOT_COMPLETED.name, SnapshotTrigger.PACKAGE_REPLACED.name)
            .inOrder()
    }

    @Test
    fun aRefusedStartIsLoggedNotACrash() {
        // The BOOT_COMPLETED a force-stopped app gets when its process starts again carries no
        // allowlist, and Android refuses the foreground service (seen on the phone, 2026-11-11).
        var asked = 0
        val refusing =
            object : ContextWrapper(app) {
                override fun startForegroundService(service: Intent): ComponentName? {
                    asked++
                    throw ForegroundServiceStartNotAllowedException("startForegroundService() not allowed")
                }
            }
        Collector.startIn(refusing, lite = false, SnapshotTrigger.BOOT_COMPLETED, snapshots::add)
        assertThat(asked).isEqualTo(1)
    }

    /** SPEC 7.1: Lite runs no foreground service; only an app open takes a snapshot. */
    @Test
    fun liteModeStartsNoServiceAndSnapshotsOnlyWhenTheAppIsOpened() {
        for (trigger in listOf(SnapshotTrigger.BOOT_COMPLETED, SnapshotTrigger.PACKAGE_REPLACED, SnapshotTrigger.SERVICE_STARTED)) {
            Collector.startIn(app, lite = true, trigger, snapshots::add)
        }
        assertThat(snapshots).isEmpty()
        Collector.startIn(app, lite = true, SnapshotTrigger.APP_OPENED, snapshots::add)
        assertThat(snapshots).containsExactly(SnapshotTrigger.APP_OPENED)
        assertThat(shadowOf(app).nextStartedService).isNull()
    }

    @Test
    fun anyOtherActionIsIgnored() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_POWER_CONNECTED))
        assertThat(shadowOf(app).nextStartedService).isNull()
    }
}

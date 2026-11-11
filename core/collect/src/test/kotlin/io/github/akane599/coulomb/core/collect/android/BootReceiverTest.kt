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
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class BootReceiverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun aBootOrAnUpdateStartsTheCollectorWithItsTrigger() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
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
        BootReceiver().onReceive(refusing, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertThat(asked).isEqualTo(1)
    }

    @Test
    fun anyOtherActionIsIgnored() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_POWER_CONNECTED))
        assertThat(shadowOf(app).nextStartedService).isNull()
    }
}

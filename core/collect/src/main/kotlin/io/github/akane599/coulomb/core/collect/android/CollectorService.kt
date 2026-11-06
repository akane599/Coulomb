// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.akane599.coulomb.core.collect.Collector
import io.github.akane599.coulomb.core.collect.R
import io.github.akane599.coulomb.core.collect.SystemCollectorClock
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import javax.inject.Inject

/**
 * Full mode (SPEC 7.1): a `specialUse` foreground service, subtype `battery_monitoring`, that
 * turns the broadcasts the system sends anyway into collector triggers. Its receivers are
 * registered at runtime and not exported; system broadcasts still reach them (BroadcastSkipPolicy
 * android-17.0.0_r1 L577-590, android-16.0.0_r4 L580-593). It sets no alarms (SPEC 7.4).
 */
@AndroidEntryPoint
public class CollectorService : Service() {
    @Inject internal lateinit var collector: Collector

    private var lastLevel: Int? = null

    private val events =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                onBroadcast(intent)
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
            }
        ContextCompat.registerReceiver(this, events, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val packages =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addDataScheme("package")
            }
        ContextCompat.registerReceiver(this, events, packages, ContextCompat.RECEIVER_NOT_EXPORTED)
        collector.attach()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val trigger =
            intent?.getStringExtra(EXTRA_TRIGGER)?.let { name -> SnapshotTrigger.entries.firstOrNull { it.name == name } }
                ?: SnapshotTrigger.SERVICE_STARTED
        collector.post(trigger)
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(events)
        collector.detach()
        super.onDestroy()
    }

    private fun onBroadcast(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                screen(on = false, SnapshotTrigger.SCREEN_OFF)
            }

            Intent.ACTION_SCREEN_ON -> {
                screen(on = true, SnapshotTrigger.SCREEN_ON)
            }

            Intent.ACTION_USER_PRESENT -> {
                collector.post(SnapshotTrigger.USER_PRESENT)
            }

            Intent.ACTION_POWER_CONNECTED -> {
                plug(plugged = true, SnapshotTrigger.POWER_CONNECTED)
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                plug(plugged = false, SnapshotTrigger.POWER_DISCONNECTED)
            }

            Intent.ACTION_BATTERY_CHANGED -> {
                batteryChanged(intent)
            }

            Intent.ACTION_TIME_CHANGED -> {
                SystemCollectorClock.noteWallChange()
            }

            Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                // A replace also sends ADDED with EXTRA_REPLACING; one trigger is enough.
                if (intent.action != Intent.ACTION_PACKAGE_ADDED || !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    collector.post(SnapshotTrigger.PACKAGE_CHANGED)
                }
            }
        }
    }

    private fun screen(
        on: Boolean,
        trigger: SnapshotTrigger,
    ) {
        collector.noteDevice(screenOn = on)
        collector.post(trigger)
    }

    private fun plug(
        plugged: Boolean,
        trigger: SnapshotTrigger,
    ) {
        collector.noteDevice(plugged = plugged)
        collector.post(trigger)
    }

    /** SPEC 7.2: only a level change is a trigger; the first delivery (the sticky one) sets the baseline. */
    private fun batteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).takeIf { it >= 0 } ?: return
        val previous = lastLevel
        lastLevel = level
        if (previous != null && previous != level) collector.post(SnapshotTrigger.LEVEL_CHANGED)
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.collector_channel), NotificationManager.IMPORTANCE_MIN).apply {
                description = getString(R.string.collector_channel_description)
                setShowBadge(false)
            },
        )
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_collector)
                .setContentTitle(getString(R.string.collector_notification))
                .setOngoing(true)
                .setSilent(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    public companion object {
        /** A [SnapshotTrigger] name for the snapshot the start asks for. */
        public const val EXTRA_TRIGGER: String = "io.github.akane599.coulomb.collect.TRIGGER"
        private const val CHANNEL = "collector"
        private const val NOTIFICATION_ID = 1

        /** Starts the service in the foreground with [trigger] as its first snapshot. */
        public fun start(
            context: Context,
            trigger: SnapshotTrigger,
        ) {
            val intent = Intent(context, CollectorService::class.java).putExtra(EXTRA_TRIGGER, trigger.name)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

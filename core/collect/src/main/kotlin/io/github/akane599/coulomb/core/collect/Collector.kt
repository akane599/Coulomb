// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akane599.coulomb.core.collect.android.AndroidAppLabels
import io.github.akane599.coulomb.core.collect.android.AndroidDeviceIdentity
import io.github.akane599.coulomb.core.collect.android.AndroidLightSource
import io.github.akane599.coulomb.core.collect.android.FileBlobStore
import io.github.akane599.coulomb.core.collect.engine.CollectorActor
import io.github.akane599.coulomb.core.collect.engine.CollectorEngine
import io.github.akane599.coulomb.core.collect.engine.CollectorEvent
import io.github.akane599.coulomb.core.collect.engine.CollectorSettings
import io.github.akane599.coulomb.core.collect.engine.SamplerPolicy
import io.github.akane599.coulomb.core.collect.engine.VisibleScreen
import io.github.akane599.coulomb.core.collect.snapshot.HeavyReader
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/**
 * The collection engine's entry point (SPEC 7). The foreground service feeds it broadcasts while
 * it is attached; screens call [post] for app-open and refresh snapshots and set [visibleScreen]
 * for the sampling rate (SPEC 7.3).
 */
@Singleton
public class Collector
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        db: CoulombDatabase,
        private val access: PrivilegedAccess,
        settings: CollectorSettings,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val light = AndroidLightSource(context, SystemCollectorClock)
        private val engine =
            CollectorEngine(
                db = db,
                light = light,
                heavy = HeavyReader(access, Build.VERSION.SDK_INT),
                identity = AndroidDeviceIdentity(context),
                clock = SystemCollectorClock,
                blobs = FileBlobStore(context.filesDir),
                labels = AndroidAppLabels(context),
                settings = settings,
            )
        private val actor = CollectorActor(engine, scope)
        private val device = MutableStateFlow(DeviceFlags(plugged = false, screenOn = false))
        private val screen = MutableStateFlow(VisibleScreen.NONE)
        private var attached: Job? = null

        /** Which Coulomb screen is showing, for the sampling rate (SPEC 7.3). */
        public var visibleScreen: VisibleScreen
            get() = screen.value
            set(value) {
                screen.value = value
            }

        /** Queues a snapshot for [trigger]; the policy decides its kind (SPEC 7.2). */
        public fun post(trigger: SnapshotTrigger) {
            actor.post(CollectorEvent.Trigger(trigger))
        }

        /** The service is running: samplers and the screen-on snapshot timer start. */
        internal fun attach() {
            if (attached != null) return
            val first = light.read()
            device.value = DeviceFlags(first.plugged, first.screenOn)
            attached =
                scope.launch {
                    launch { sample() }
                    launch { screenOnSnapshots() }
                    launch { privilegeChanges() }
                }
        }

        internal fun detach() {
            attached?.cancel()
            attached = null
        }

        internal fun noteDevice(
            plugged: Boolean? = null,
            screenOn: Boolean? = null,
        ) {
            val now = device.value
            device.value = DeviceFlags(plugged ?: now.plugged, screenOn ?: now.screenOn)
        }

        /**
         * SPEC 7.3 rates. The delays run on the monotonic clock and stop while the CPU sleeps, so a
         * sampler never wakes the device; with the screen off and unplugged there is none at all.
         */
        private suspend fun sample() {
            val power = context.getSystemService(PowerManager::class.java)
            val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
            combine(device, screen) { d, s -> d.plugged to SamplerPolicy.intervalMs(d.plugged, d.screenOn, s) }
                .distinctUntilChanged()
                .collectLatest { (plugged, intervalMs) ->
                    if (intervalMs == null) return@collectLatest
                    while (true) {
                        // SPEC 7.3/7.4: a short wakelock only while plugged in, released in finally.
                        if (plugged) wakeLock.acquire(SamplerPolicy.WAKE_LOCK_MS)
                        try {
                            actor.post(CollectorEvent.Sample(light.read()))
                        } finally {
                            if (wakeLock.isHeld) wakeLock.release()
                        }
                        delay(intervalMs)
                    }
                }
        }

        private suspend fun screenOnSnapshots() {
            device
                .map { it.screenOn }
                .distinctUntilChanged()
                .collectLatest { screenOn ->
                    if (!screenOn) return@collectLatest
                    while (true) {
                        delay(SCREEN_ON_PERIOD)
                        post(SnapshotTrigger.SCREEN_ON_PERIODIC)
                    }
                }
        }

        private suspend fun privilegeChanges() {
            access.state
                .map { it.tier }
                .distinctUntilChanged()
                .drop(1)
                .collect { actor.post(CollectorEvent.PrivilegesChanged) }
        }

        internal data class DeviceFlags(
            val plugged: Boolean,
            val screenOn: Boolean,
        )

        private companion object {
            const val WAKE_LOCK_TAG = "Coulomb:sample"
            val SCREEN_ON_PERIOD = 30.minutes
        }
    }

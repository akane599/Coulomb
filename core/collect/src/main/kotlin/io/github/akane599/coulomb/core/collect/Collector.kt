// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akane599.coulomb.core.collect.android.AndroidAppLabels
import io.github.akane599.coulomb.core.collect.android.AndroidDeviceIdentity
import io.github.akane599.coulomb.core.collect.android.AndroidHealthSource
import io.github.akane599.coulomb.core.collect.android.AndroidLightSource
import io.github.akane599.coulomb.core.collect.android.AndroidRailSource
import io.github.akane599.coulomb.core.collect.android.CollectorService
import io.github.akane599.coulomb.core.collect.android.FileBlobStore
import io.github.akane599.coulomb.core.collect.android.LiteWorker
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
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.first
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
        private val settings: CollectorSettings,
        observers: Set<@JvmSuppressWildcards SnapshotObserver>,
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
                health = AndroidHealthSource(context, access),
                rails = AndroidRailSource(context),
            )
        private val actor = CollectorActor(engine, scope, observers)
        private val device = MutableStateFlow(DeviceFlags(plugged = false, screenOn = false))
        private val screen = MutableStateFlow(VisibleScreen.NONE)
        private var attached: Job? = null

        init {
            scope.launch { followMode() }
        }

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

        /**
         * [post], returning what the trigger did once it has been handled and what follows a
         * snapshot has run (audit BUG-001).
         */
        public suspend fun snapshot(trigger: SnapshotTrigger): SnapshotOutcome {
            val done = CompletableDeferred<SnapshotOutcome>()
            actor.post(CollectorEvent.Trigger(trigger, done))
            return done.await()
        }

        /**
         * Starts collecting for [trigger] in the user's monitoring mode (SPEC 7.1): Full starts the
         * foreground service with it; Lite runs no service and takes a snapshot only for an app
         * open. [onStarted] runs once that is decided and done, for a receiver that holds its
         * broadcast open meanwhile.
         */
        public fun start(
            trigger: SnapshotTrigger,
            onStarted: () -> Unit = {},
        ) {
            scope.launch {
                try {
                    startIn(context, settings.liteMode.first(), trigger, ::post)
                } finally {
                    onStarted()
                }
            }
        }

        /** Whether Lite mode is on, for the periodic job, which is left over when it is not. */
        internal suspend fun lite(): Boolean = settings.liteMode.first()

        /**
         * The mode as stored and every change of it. Lite stops the service and schedules its
         * 6-hour job; Full cancels the job. A change to Full starts the service too: it is made in
         * Settings, with Coulomb in front. At a process start in Full nothing is started here: that
         * is the launch's or the boot's, which Android allows to (SPEC 7.1, ADR-0064 §5).
         */
        private suspend fun followMode() {
            var stored = true
            settings.liteMode.distinctUntilChanged().collect { lite ->
                if (lite) {
                    context.stopService(Intent(context, CollectorService::class.java))
                    LiteWorker.schedule(context)
                } else if (!stored) {
                    LiteWorker.cancel(context)
                    startIn(context, lite = false, SnapshotTrigger.SERVICE_STARTED, ::post)
                }
                stored = false
            }
        }

        /** The service is running: samplers and the screen-on snapshot timer start. */
        internal fun attach() {
            if (attached != null) return
            // Before the service's first trigger, which it posts after this: from here its receivers watch.
            actor.post(CollectorEvent.Watching(on = true))
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
            // Lite can keep the process: the next snapshot reads what changed from the history.
            actor.post(CollectorEvent.Watching(on = false))
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
            combine(device, screen, settings.visibleSamplingMs) { d, s, visibleMs ->
                d.plugged to SamplerPolicy.intervalMs(d.plugged, d.screenOn, s, visibleMs)
            }.distinctUntilChanged()
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

        internal companion object {
            private const val TAG = "CoulombCollector"
            private const val WAKE_LOCK_TAG = "Coulomb:sample"
            private val SCREEN_ON_PERIOD = 30.minutes

            /**
             * [start] for a known mode. Android refuses a foreground service from the background
             * without an allowance (see `BootReceiver`); that is logged, and the collector starts
             * when Coulomb is next opened.
             */
            fun startIn(
                context: Context,
                lite: Boolean,
                trigger: SnapshotTrigger,
                snapshot: (SnapshotTrigger) -> Unit,
            ) {
                try {
                    when {
                        !lite -> CollectorService.start(context, trigger)
                        trigger == SnapshotTrigger.APP_OPENED -> snapshot(trigger)
                    }
                } catch (e: ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "collector not started for $trigger; it starts when Coulomb is opened", e)
                }
            }
        }
    }

/** What a trigger handed to [Collector.snapshot] did. */
public enum class SnapshotOutcome {
    /** A heavy snapshot was stored. */
    HEAVY,

    /** A light snapshot was stored: the privileged reads were not available. */
    LIGHT,

    /** None was stored: one was taken within the debounce (SPEC 7.2), or a sample was enough. */
    NONE,

    /** Taking or storing it failed: Room, a parser or the platform. */
    FAILED,
}

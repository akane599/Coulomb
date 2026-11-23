// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.collection.intSetOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.akane599.coulomb.core.common.OpenRequest
import io.github.akane599.coulomb.core.data.BatteryRepository
import io.github.akane599.coulomb.core.data.DrainRepository
import io.github.akane599.coulomb.core.data.HealthRepository
import io.github.akane599.coulomb.core.data.NightRepository
import io.github.akane599.coulomb.core.data.WindowDrains
import io.github.akane599.coulomb.core.datastore.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/** The four widgets of SPEC 12.4. */
internal enum class WidgetKind(
    val widget: GlanceAppWidget,
    val receiver: KClass<out GlanceAppWidgetReceiver>,
) {
    LEVEL(LevelWidget(), LevelWidgetReceiver::class),
    DRAINS(DrainsWidget(), DrainsWidgetReceiver::class),
    NIGHT(NightWidget(), NightWidgetReceiver::class),
    HEALTH(HealthWidget(), HealthWidgetReceiver::class),
    ;

    fun part(data: WidgetData): Any? =
        when (this) {
            LEVEL -> data.level
            DRAINS -> data.drains
            NIGHT -> data.night
            HEALTH -> data.health
        }
}

/**
 * What the widgets show, read once per stored snapshot (SPEC 12.4: updates come from snapshot
 * events; no periodic interval). The app calls [refresh] after a snapshot; a widget the launcher
 * asks for after a process start reads its own part once. Nothing is read while no widget is placed.
 */
@Singleton
class WidgetStore
    @Inject
    internal constructor(
        @ApplicationContext private val context: Context,
        private val battery: BatteryRepository,
        private val drains: DrainRepository,
        private val nights: NightRepository,
        private val health: HealthRepository,
        private val settings: SettingsStore,
    ) {
        private val data = MutableStateFlow(WidgetData())
        internal val state: StateFlow<WidgetData> = data.asStateFlow()
        private val lock = Mutex()
        private var loaded = emptySet<WidgetKind>()

        /** The window since unplugging as last read; only a heavy snapshot changes it. */
        private var window: WindowDrains? = null
        private var windowRead = false

        /**
         * A snapshot was stored. Any one may change the level, the last night and the health
         * estimate, which come from samples and sessions; only a [heavy] one changes the top drains
         * ([reloads]). Only widgets whose part changed are drawn again.
         */
        suspend fun refresh(heavy: Boolean) {
            val manager = GlanceAppWidgetManager(context)
            val placed = WidgetKind.entries.filter { manager.getGlanceIds(it.widget.javaClass).isNotEmpty() }
            if (placed.isEmpty()) return
            val before = data.value
            lock.withLock {
                if (heavy) windowRead = false
                placed.forEach { if (reloads(it, heavy, loaded)) load(it) }
            }
            val after = data.value
            placed.filter { it.part(before) != it.part(after) }.forEach { it.widget.updateAll(context) }
        }

        /** Before a widget's first composition in this process. */
        internal suspend fun ensure(kind: WidgetKind) = lock.withLock { if (kind !in loaded) load(kind) }

        private suspend fun load(kind: WidgetKind) {
            when (kind) {
                WidgetKind.LEVEL -> {
                    val reading = battery.reading.first()
                    val lite = settings.settings.first().liteMode
                    val level = reading?.let { WidgetFacts.level(it, window(), lite, System.currentTimeMillis()) }
                    data.update { it.copy(level = level) }
                }

                WidgetKind.DRAINS -> {
                    val facts = WidgetFacts.drains(window())
                    data.update { it.copy(drains = facts) }
                }

                WidgetKind.NIGHT -> {
                    // Newest first by the elapsed clock: a boot stamped in the future by a wrong clock
                    // is not the latest night (audit BUG-034).
                    val latest = nights.nights.first().firstOrNull()
                    data.update { it.copy(night = latest) }
                }

                WidgetKind.HEALTH -> {
                    val facts = WidgetFacts.health(health.report.first())
                    data.update { it.copy(health = facts) }
                }
            }
            loaded = loaded + kind
        }

        private suspend fun window(): WindowDrains? {
            if (!windowRead) {
                window = drains.sinceUnplugged.first()
                windowRead = true
            }
            return window
        }

        /**
         * Hands the launcher each widget's preview for its picker (SPEC 12.4: generated previews).
         * The platform limits how often an app may do this, so the app calls it once per version and
         * language. True when every preview was taken.
         */
        suspend fun publishPreviews(): Boolean {
            val manager = GlanceAppWidgetManager(context)
            return WidgetKind.entries
                .map { manager.setWidgetPreviews(it.receiver, intSetOf(HOME_SCREEN)) }
                .all { it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }
        }

        private companion object {
            /** `AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN`. */
            const val HOME_SCREEN = 1
        }
    }

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {
    fun store(): WidgetStore
}

/** A widget is created by the launcher's broadcast, not by Hilt, so it asks the application for the store. */
internal fun Context.widgetStore(): WidgetStore =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java).store()

/** The app's launch intent with [open] as its [OpenRequest]; the data keeps one pending intent per target. */
internal fun Context.openIntent(open: String): Intent =
    (packageManager.getLaunchIntentForPackage(packageName) ?: Intent(Intent.ACTION_MAIN).setPackage(packageName))
        .setData(Uri.fromParts("coulomb", open, null))
        .putExtra(OpenRequest.EXTRA, open)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/**
 * Whether [kind] is read again after a snapshot. Only the top drains need a heavy one: they are
 * attribution. The night and the health estimate change with light snapshots too, and without
 * platform dumps every snapshot is light (ADR-0058 §1 as amended, audit BUG-020).
 */
internal fun reloads(
    kind: WidgetKind,
    heavy: Boolean,
    loaded: Set<WidgetKind>,
): Boolean = heavy || kind != WidgetKind.DRAINS || kind !in loaded

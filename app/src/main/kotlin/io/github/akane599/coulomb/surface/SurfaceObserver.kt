// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.surface

import android.content.Context
import android.os.BatteryManager
import androidx.core.content.edit
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.akane599.coulomb.R
import io.github.akane599.coulomb.core.collect.SnapshotObserver
import io.github.akane599.coulomb.core.data.BatteryReading
import io.github.akane599.coulomb.core.data.BatteryRepository
import io.github.akane599.coulomb.core.data.ChargingRepository
import io.github.akane599.coulomb.core.datastore.SettingsStore
import io.github.akane599.coulomb.core.datastore.UserSettings
import io.github.akane599.coulomb.core.insights.InsightRunner
import io.github.akane599.coulomb.core.insights.InsightStore
import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.model.InsightState
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.model.insight.RuleId
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.formatsFor
import io.github.akane599.coulomb.core.ui.insight.InsightCopy
import io.github.akane599.coulomb.widget.WidgetStore
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the surfaces outside the app current (SPEC 12): after each stored snapshot it runs the
 * insight engine (SPEC 7.5, step 6), then updates the Monitor line, the Charging Live Update, the
 * alerts, the digest and the widgets. It runs on the collector's coroutine and reads stored state only, so it
 * adds no wakeup and no sampling of its own.
 */
@Singleton
class SurfaceObserver
    @Inject
    @Suppress("LongParameterList") // One source for each surface; Hilt supplies them.
    constructor(
        @ApplicationContext private val context: Context,
        private val notifier: SurfaceNotifier,
        private val battery: BatteryRepository,
        private val charging: ChargingRepository,
        private val settings: SettingsStore,
        private val runner: Lazy<InsightRunner>,
        private val insights: InsightStore,
        private val widgets: WidgetStore,
    ) : SnapshotObserver {
        private val chargingUpdates = ChargingUpdates()
        private val alerts = AlertRules()
        private val memory = context.getSharedPreferences(MEMORY, Context.MODE_PRIVATE)

        override suspend fun onSnapshot(heavy: Boolean) {
            val nowMs = System.currentTimeMillis()
            // Rules read windows, which only a heavy snapshot adds.
            val fresh = if (heavy) runner.get().run(nowMs) else emptyList()
            notifier.ensureChannels()
            val formats = formatsFor(context)
            val copy = InsightCopy(context.resources, formats)
            val set = settings.settings.first()
            val reading = battery.reading.first()
            notifier.monitor(reading?.let { monitorLine(it, formats) })
            // Lite gives up the Live Update and the two alarms (ADR-0064 §5): with no service, nothing
            // would end a charge's update at the unplug, and an alarm would ring hours late (ADR-0069).
            if (set.liteMode) {
                notifier.cancelCharging()
            } else {
                charge(nowMs, reading?.battery?.levelPct)
            }
            thermal(fresh, nowMs, copy)
            if (reading != null && !set.liteMode) alerts(reading, nowMs, set)
            digest(nowMs, set, copy)
            // Last: a widget that fails to draw must not hold back a notification.
            widgets.refresh(heavy)
        }

        /** "0.7 %/h · screen off 6 h" (SPEC 12.1): the rate of the state with the most to say, or the level on the charger. */
        private fun monitorLine(
            reading: BatteryReading,
            formats: Formats,
        ): String? {
            if (reading.battery.plugged) {
                // The words the widget and the tile use for the same states: plugged in is not always charging.
                val words =
                    when (reading.battery.status) {
                        BatteryManager.BATTERY_STATUS_FULL -> R.string.surface_charging_full
                        BatteryManager.BATTERY_STATUS_CHARGING -> R.string.surface_monitor_charging
                        else -> R.string.surface_charging_plugged
                    }
                return context.getString(words, formats.percent(reading.battery.levelPct))
            }
            val discharge = reading.discharge ?: return null
            val off = discharge.screenOff?.takeIf { it.ready }
            val on = discharge.screenOn?.takeIf { it.ready }
            val rate = off ?: on ?: return null
            val figure = "${formats.rate(rate.pctPerHour)} ${formats.rateUnit}"
            return context.getString(
                if (off !=
                    null
                ) {
                    R.string.surface_monitor_off
                } else {
                    R.string.surface_monitor_on
                },
                figure,
                formats.hours(rate.hours),
            )
        }

        private suspend fun charge(
            nowMs: Long,
            levelPct: Int?,
        ) {
            when (val step = chargingUpdates.next(charging.now.first(), nowMs, levelPct)) {
                ChargingStep.None -> Unit
                ChargingStep.Clear -> notifier.cancelCharging()
                is ChargingStep.Show -> notifier.charging(step.now)
                is ChargingStep.End -> notifier.chargeEnded(step.summary)
            }
        }

        private fun alerts(
            reading: BatteryReading,
            nowMs: Long,
            set: UserSettings,
        ) {
            alerts
                .next(
                    levelPct = reading.battery.levelPct,
                    plugged = reading.battery.plugged,
                    tempDeciC = reading.battery.tempDeciC,
                    atMs = nowMs,
                    targetPct = set.chargeTargetPct.takeIf { set.chargeAlarm },
                    hotFromDeciC = set.temperatureAlarmDeciC,
                ).forEach(notifier::alert)
        }

        /**
         * SPEC 9.4: an Act-level thermal insight (R11, R12) notifies at once, and the temperature
         * alarm then rests, so one heat is told once.
         */
        private fun thermal(
            fresh: List<Insight>,
            nowMs: Long,
            copy: InsightCopy,
        ) {
            val hot = fresh.filter { it.severity == InsightSeverity.ACT && it.rule in THERMAL }
            hot.forEach { notifier.insight(it.id, copy.text(it, null)) }
            if (hot.isNotEmpty()) alerts.rest(nowMs)
        }

        /**
         * The other insights wait for the day's one digest (SPEC 9.4), which posts with the first
         * snapshot from its set time on. The insights are read only when today's digest is still open.
         */
        private suspend fun digest(
            nowMs: Long,
            set: UserSettings,
            copy: InsightCopy,
        ) {
            val local = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
            val day = local.toLocalDate().toEpochDay()
            val open =
                DigestRules.open(
                    enabled = set.insightsDigest,
                    minuteOfDay = local.hour * MINUTES_PER_HOUR + local.minute,
                    fromMinute = set.insightsDigestMinute,
                    epochDay = day,
                    lastPostedEpochDay = memory.getLong(KEY_DIGEST_DAY, NONE).takeIf { it != NONE },
                )
            if (!open) return
            val new = insights.observeShown(DIGEST_MAX).first().filter { it.insight.state == InsightState.NEW }
            val named = DigestRules.fresh(new, memory.getLong(KEY_DIGEST_ID, NONE).takeIf { it != NONE }) { it.insight.id }
            if (named.isEmpty()) return
            // Blocked: the same insights wait for the next snapshot after posting is allowed again.
            if (!notifier.digest(named.map { copy.text(it.insight, it.appLabel) })) return
            memory.edit {
                putLong(KEY_DIGEST_DAY, day)
                putLong(KEY_DIGEST_ID, named.maxOf { it.insight.id })
            }
        }

        companion object {
            /** What the surfaces remember across process starts; not settings and not battery data. */
            const val MEMORY = "surfaces"
            const val KEY_DIGEST_DAY = "digest_day"
            const val KEY_DIGEST_ID = "digest_insight"
            const val NONE = Long.MIN_VALUE
            const val DIGEST_MAX = 20
            const val MINUTES_PER_HOUR = 60
            val THERMAL = setOf(RuleId.HOT_CHARGING, RuleId.HOT_BATTERY)
        }
    }

/**
 * Tells a lost access once (SPEC 12.1): the notification is posted when the issue appears and not
 * again until access is back, however often the process starts in between.
 */
@Singleton
class PrivilegeNotifications
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val notifier: SurfaceNotifier,
    ) {
        private val memory = context.getSharedPreferences(SurfaceObserver.MEMORY, Context.MODE_PRIVATE)

        /** [issue] of a settled privilege state; null is access as it should be. */
        fun onIssue(issue: PrivilegeIssue?) {
            if (issue == null) {
                notifier.privileges(null)
                memory.edit { remove(KEY_TOLD) }
                return
            }
            val post = PrivilegeNotices.toPost(issue, memory.getString(KEY_TOLD, null)) ?: return
            notifier.ensureChannels()
            // Told only once shown: a blocked notice is posted when the issue is next seen (audit BUG-013).
            if (notifier.privileges(post)) memory.edit { putString(KEY_TOLD, post.name) }
        }

        private companion object {
            const val KEY_TOLD = "privilege_issue_told"
        }
    }

/**
 * Gives the launcher the widgets' previews (SPEC 12.4) once per app version and language: the
 * platform limits how often an app may set them, and they change only with the layouts or the words.
 */
@Singleton
class WidgetPreviews
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val widgets: WidgetStore,
    ) {
        private val memory = context.getSharedPreferences(SurfaceObserver.MEMORY, Context.MODE_PRIVATE)

        suspend fun publishIfNew() {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            val made = "$version ${context.resources.configuration.locales.toLanguageTags()}"
            if (memory.getString(KEY_PREVIEWS, null) == made) return
            // Refused (the platform's limit): the next start tries again.
            if (widgets.publishPreviews()) memory.edit { putString(KEY_PREVIEWS, made) }
        }

        private companion object {
            const val KEY_PREVIEWS = "widget_previews"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
interface SurfaceModule {
    @Binds
    @IntoSet
    fun surfaces(observer: SurfaceObserver): SnapshotObserver
}

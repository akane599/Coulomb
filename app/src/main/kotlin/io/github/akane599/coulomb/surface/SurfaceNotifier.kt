// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.surface

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akane599.coulomb.MainActivity
import io.github.akane599.coulomb.R
import io.github.akane599.coulomb.core.collect.android.CollectorService
import io.github.akane599.coulomb.core.common.OpenRequest
import io.github.akane599.coulomb.core.data.ChargingNow
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.formatsFor
import io.github.akane599.coulomb.core.ui.insight.InsightText
import javax.inject.Inject
import javax.inject.Singleton
import io.github.akane599.coulomb.core.collect.R as CollectR

/**
 * Posts the notifications of SPEC 12.1 to 12.3. It decides nothing: [SurfaceObserver] says what
 * to post and when. Every notification opens the app; nothing here schedules anything.
 */
@Singleton
class SurfaceNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val manager = context.getSystemService(NotificationManager::class.java)

        /** The channels of SPEC 12.1; Monitor is the collector's own. Safe to call again: the user's changes stay. */
        fun ensureChannels() {
            manager.createNotificationChannels(
                listOf(
                    channel(
                        CHARGING,
                        R.string.surface_channel_charging,
                        R.string.surface_channel_charging_about,
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { setSound(null, null) },
                    channel(
                        ALERTS,
                        R.string.surface_channel_alerts,
                        R.string.surface_channel_alerts_about,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                    channel(
                        INSIGHTS,
                        R.string.surface_channel_insights,
                        R.string.surface_channel_insights_about,
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                    channel(
                        PRIVILEGES,
                        R.string.surface_channel_privileges,
                        R.string.surface_channel_privileges_about,
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                ),
            )
        }

        private fun channel(
            id: String,
            name: Int,
            about: Int,
            importance: Int,
        ) = NotificationChannel(id, context.getString(name), importance).apply {
            description = context.getString(about)
            setShowBadge(false)
        }

        /** The Monitor notification's line; only while the collector's service shows it. */
        fun monitor(text: String?) {
            if (CollectorService.running) manager.notify(CollectorService.NOTIFICATION_ID, CollectorService.notification(context, text))
        }

        /**
         * The Charging Live Update (SPEC 12.2): a progress bar from the charge's start level to full,
         * the stretch past the target quieter and the target a point on it. It asks to be promoted;
         * where the system or the user does not promote it, it is the same ongoing notification.
         *
         * Promotable means: the request, ongoing, a title, a `ProgressStyle`, no custom views, not a
         * group summary and not colorized. AOSP `core/java/android/app/Notification.java`,
         * `hasPromotableStyle` and `hasPromotableCharacteristics`: android-16.0.0_r4 lines 3409-3437,
         * android-17.0.0_r1 lines 3719-3746.
         */
        fun charging(now: ChargingNow) {
            val formats = formatsFor(context)
            val bar = ChargeBar.of(now)
            val copper = context.getColor(R.color.surface_accent)
            val style =
                NotificationCompat
                    .ProgressStyle()
                    .setProgress(bar.progress)
                    .setProgressTrackerIcon(IconCompat.createWithResource(context, CollectR.drawable.ic_collector))
                    .addProgressSegment(NotificationCompat.ProgressStyle.Segment(bar.toTarget).setColor(copper))
            if (bar.pastTarget > 0) {
                style.addProgressSegment(
                    NotificationCompat.ProgressStyle.Segment(bar.pastTarget).setColor(context.getColor(R.color.surface_quiet)),
                )
            }
            bar.targetAt?.let { style.addProgressPoint(NotificationCompat.ProgressStyle.Point(it).setColor(copper)) }
            val status = status(now, formats)
            val notification =
                base(CHARGING, HERE)
                    .setContentTitle(context.getString(R.string.surface_charging_title, status))
                    .setContentText(timeToTarget(now, formats))
                    .setShortCriticalText(status)
                    .setStyle(style)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setCategory(Notification.CATEGORY_PROGRESS)
                    .setRequestPromotedOngoing(true)
                    .build()
            manager.notify(CHARGING_ID, notification)
        }

        /** "62% · 18 W": the status chip's text and the title's figures. */
        private fun status(
            now: ChargingNow,
            formats: Formats,
        ): String {
            val level = formats.percent(now.levelPct)
            val watts = now.watts?.takeIf { it > 0 } ?: return level
            return context.getString(R.string.surface_charging_status, level, formats.wholeWatts(watts).joined())
        }

        private fun timeToTarget(
            now: ChargingNow,
            formats: Formats,
        ): String? {
            val time = now.toTarget?.takeIf { now.levelPct < now.targetPct && !now.belowHighest } ?: return null
            val (shortest, longest) = formats.timeLeft(time.shortestMin / MINUTES_PER_HOUR, time.longestMin / MINUTES_PER_HOUR)
            return context.getString(R.string.surface_charging_to_target, formats.percent(now.targetPct), formats.range(shortest, longest))
        }

        /** The ended charge in one line, which leaves by itself after ten seconds (SPEC 12.2). */
        fun chargeEnded(summary: ChargeSummary) {
            val formats = formatsFor(context)
            val gained = formats.signedShare(summary.gainedPct / PERCENT)
            val time = formats.hours(summary.durationMs / MS_PER_HOUR)
            val text =
                summary.averageWatts
                    ?.let { context.getString(R.string.surface_charge_ended_power, gained, time, formats.wholeWatts(it).joined()) }
                    ?: context.getString(R.string.surface_charge_ended, gained, time)
            val notification =
                base(CHARGING, HERE)
                    .setContentTitle(context.getString(R.string.surface_charge_ended_title))
                    .setContentText(text)
                    .setSilent(true)
                    .setTimeoutAfter(SUMMARY_MS)
                    .build()
            manager.notify(CHARGING_ID, notification)
        }

        fun cancelCharging() = manager.cancel(CHARGING_ID)

        fun alert(alert: Alert) {
            val formats = formatsFor(context)
            val (id, title, text) =
                when (alert) {
                    is Alert.TargetReached -> {
                        Triple(
                            TARGET_ID,
                            context.getString(R.string.surface_target_title, formats.percent(alert.targetPct)),
                            context.getString(R.string.surface_target_text),
                        )
                    }

                    is Alert.Hot -> {
                        Triple(
                            HOT_ID,
                            context.getString(R.string.surface_hot_title, formats.celsius(alert.tempDeciC.toDouble()).joined()),
                            context.getString(R.string.surface_hot_text),
                        )
                    }
                }
            manager.notify(id, text(ALERTS, title, text).setCategory(Notification.CATEGORY_ALARM).build())
        }

        /** An insight that does not wait for the digest: the Act-level thermal ones (SPEC 9.4). */
        fun insight(
            id: Long,
            text: InsightText,
        ) = manager.notify(INSIGHT_BASE_ID + (id % INSIGHT_IDS).toInt(), text(ALERTS, text.title, text.body).build())

        /** The day's digest (SPEC 9.4): one notification for all new insights, their titles as its lines. */
        fun digest(insights: List<InsightText>) {
            val first = insights.firstOrNull() ?: return
            val builder =
                if (insights.size == 1) {
                    text(INSIGHTS, first.title, first.body)
                } else {
                    val title =
                        context.resources.getQuantityString(
                            R.plurals.surface_digest_title,
                            insights.size,
                            formatsFor(context).integer(insights.size.toLong()),
                        )
                    val lines = NotificationCompat.InboxStyle()
                    insights.take(DIGEST_LINES).forEach { lines.addLine(it.title) }
                    base(INSIGHTS, HERE).setContentTitle(title).setContentText(first.title).setStyle(lines)
                }
            manager.notify(DIGEST_ID, builder.setAutoCancel(true).build())
        }

        /** Coulomb lost its access (SPEC 12.1); the tap opens Privileges, where the fix is. Null withdraws it. */
        fun privileges(issue: PrivilegeIssue?) {
            val words =
                when (issue) {
                    PrivilegeIssue.MANAGER_STOPPED -> R.string.surface_privileges_stopped
                    PrivilegeIssue.GRANTS_REVOKED -> R.string.surface_privileges_revoked
                    PrivilegeIssue.PERMISSION_DENIED -> R.string.surface_privileges_denied
                    else -> null
                }
            if (words == null) {
                manager.cancel(PRIVILEGES_ID)
                return
            }
            val notification =
                base(PRIVILEGES, OpenRequest.PRIVILEGES)
                    .setContentTitle(context.getString(R.string.surface_privileges_title))
                    .setContentText(context.getString(words))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(words)))
                    .setAutoCancel(true)
                    .build()
            manager.notify(PRIVILEGES_ID, notification)
        }

        private fun text(
            channel: String,
            title: String,
            text: String,
        ) = base(channel, HERE)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)

        private fun base(
            channel: String,
            open: String,
        ) = NotificationCompat
            .Builder(context, channel)
            .setSmallIcon(CollectR.drawable.ic_collector)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    open.hashCode(),
                    Intent(context, MainActivity::class.java)
                        .putExtra(OpenRequest.EXTRA, open)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

        companion object {
            const val CHARGING = "charging"
            const val ALERTS = "alerts"
            const val INSIGHTS = "insights"
            const val PRIVILEGES = "privileges"

            /** A tap that opens the app where it is: no request the activity knows. */
            private const val HERE = ""

            // 1 is the collector's.
            private const val CHARGING_ID = 2
            private const val TARGET_ID = 3
            private const val HOT_ID = 4
            private const val DIGEST_ID = 5
            private const val PRIVILEGES_ID = 6
            private const val INSIGHT_BASE_ID = 100
            private const val INSIGHT_IDS = 100
            private const val DIGEST_LINES = 5
            private const val SUMMARY_MS = 10_000L
            private const val MINUTES_PER_HOUR = 60.0
            private const val MS_PER_HOUR = 3_600_000.0
            private const val PERCENT = 100.0
        }
    }

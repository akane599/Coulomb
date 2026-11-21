// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.datastore

import androidx.datastore.core.DataStore
import io.github.akane599.coulomb.core.datastore.proto.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The settings of SPEC 14.4 that are built so far, with their defaults. */
public data class UserSettings(
    /** "Show system apps in Drain": Off. */
    val showSystemApps: Boolean = false,
    /** "Scale attribution to measured": Off. */
    val scaleToMeasured: Boolean = false,
    /** "Component breakdown" (ADR-0035 §2): Off. */
    val componentBreakdown: Boolean = false,
    /** The charge target (SPEC 14.4, ADR-0046 §4): 80% suggested, 50–100%. */
    val chargeTargetPct: Int = DEFAULT_CHARGE_TARGET,
    /** Onboarding finished (ADR-0047 §1). */
    val onboarded: Boolean = false,
    /** "Charge target alarm" (SPEC 12.3): Off; it fires at [chargeTargetPct]. */
    val chargeAlarm: Boolean = false,
    /** "Temperature alarm" in tenths of a degree (SPEC 14.4): 42 °C, 38–50 °C; null is off. */
    val temperatureAlarmDeciC: Int? = DEFAULT_TEMPERATURE_ALARM,
    /** "Insights digest" (SPEC 14.4): On. */
    val insightsDigest: Boolean = true,
    /** The minute of the local day from which the digest may post: 09:00. */
    val insightsDigestMinute: Int = DEFAULT_DIGEST_MINUTE,
    /** "Action Center" (SPEC 13, 14.4): Off. Nothing on the phone is changed while it is off. */
    val actionCenter: Boolean = false,
) {
    public companion object {
        public const val DEFAULT_CHARGE_TARGET: Int = 80
        public const val DEFAULT_TEMPERATURE_ALARM: Int = 420

        /** SPEC 14.4's range for the temperature alarm, in tenths of a degree. */
        public val TEMPERATURE_ALARMS: IntRange = 380..500
        public const val DEFAULT_DIGEST_MINUTE: Int = 9 * 60
        public const val MINUTES_PER_DAY: Int = 24 * 60

        /** SPEC 14.4's range for the charge target. */
        public val CHARGE_TARGETS: IntRange = 50..100
    }
}

/** Reads and writes [UserSettings] in Proto DataStore (SPEC 8.1). */
@Singleton
public class SettingsStore
    @Inject
    internal constructor(
        private val store: DataStore<Settings>,
    ) {
        public val settings: Flow<UserSettings> =
            store.data
                .map {
                    UserSettings(
                        showSystemApps = it.show_system_apps,
                        scaleToMeasured = it.scale_to_measured,
                        componentBreakdown = it.component_breakdown,
                        // 0 is unset; a value outside the range (an older or edited file) falls back too.
                        chargeTargetPct =
                            it.charge_target_pct.takeIf { pct -> pct in UserSettings.CHARGE_TARGETS }
                                ?: UserSettings.DEFAULT_CHARGE_TARGET,
                        onboarded = it.onboarded,
                        chargeAlarm = it.charge_alarm,
                        temperatureAlarmDeciC =
                            when (val stored = it.temperature_alarm_deci_c) {
                                ALARM_OFF -> null
                                in UserSettings.TEMPERATURE_ALARMS -> stored
                                else -> UserSettings.DEFAULT_TEMPERATURE_ALARM
                            },
                        insightsDigest = !it.insights_digest_off,
                        insightsDigestMinute =
                            (it.insights_digest_minute_plus_one - 1).takeIf { minute -> minute in 0 until UserSettings.MINUTES_PER_DAY }
                                ?: UserSettings.DEFAULT_DIGEST_MINUTE,
                        actionCenter = it.action_center,
                    )
                }.distinctUntilChanged()

        public suspend fun showSystemApps(show: Boolean) {
            store.updateData { it.copy(show_system_apps = show) }
        }

        public suspend fun scaleToMeasured(scale: Boolean) {
            store.updateData { it.copy(scale_to_measured = scale) }
        }

        public suspend fun componentBreakdown(on: Boolean) {
            store.updateData { it.copy(component_breakdown = on) }
        }

        /** Onboarding is done: the app opens on Now from here on (ADR-0047 §1). */
        public suspend fun onboarded() {
            store.updateData { it.copy(onboarded = true) }
        }

        public suspend fun chargeAlarm(on: Boolean) {
            store.updateData { it.copy(charge_alarm = on) }
        }

        /** Stores the alarm's threshold, held to [UserSettings.TEMPERATURE_ALARMS]; null turns the alarm off. */
        public suspend fun temperatureAlarm(deciC: Int?) {
            val stored = deciC?.coerceIn(UserSettings.TEMPERATURE_ALARMS) ?: ALARM_OFF
            store.updateData { it.copy(temperature_alarm_deci_c = stored) }
        }

        public suspend fun actionCenter(on: Boolean) {
            store.updateData { it.copy(action_center = on) }
        }

        public suspend fun insightsDigest(on: Boolean) {
            store.updateData { it.copy(insights_digest_off = !on) }
        }

        /** Stores the minute of the day from which the digest may post. */
        public suspend fun insightsDigestMinute(minute: Int) {
            val held = minute.coerceIn(0, UserSettings.MINUTES_PER_DAY - 1)
            store.updateData { it.copy(insights_digest_minute_plus_one = held + 1) }
        }

        /** Stores [pct], held to [UserSettings.CHARGE_TARGETS]. */
        public suspend fun chargeTarget(pct: Int) {
            val held = pct.coerceIn(UserSettings.CHARGE_TARGETS)
            store.updateData { it.copy(charge_target_pct = held) }
        }

        private companion object {
            /** The stored value of a temperature alarm that is off. */
            const val ALARM_OFF = -1
        }
    }

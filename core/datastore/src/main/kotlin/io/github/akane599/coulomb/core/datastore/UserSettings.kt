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

/**
 * "Theme" (SPEC 14.4). Stored by position: append, never reorder. The wallpaper accent is not
 * built (`BACKLOG.md`).
 */
public enum class ThemeChoice {
    SYSTEM,
    LIGHT,
    DARK,
    BLACK,
}

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
    /** "Theme" (SPEC 14.4). */
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /** "Units" (SPEC 14.4): temperatures in °F. */
    val fahrenheit: Boolean = false,
    /** "Monitoring mode" (SPEC 7.1): Lite, with no foreground service. */
    val liteMode: Boolean = false,
    /** "Screen-on sampling" (SPEC 14.4): seconds between samples while a Coulomb screen is visible. */
    val screenOnSamplingS: Int = DEFAULT_SAMPLING_S,
    /** "Data retention" (SPEC 14.4): raw samples kept [LONG_RAW_DAYS] days instead of SPEC 7.7's 14. */
    val longRawRetention: Boolean = false,
    /** "Developer console" (SPEC 14.4): unlocked. */
    val developerConsole: Boolean = false,
    /** "Automation API" (SPEC 12.7). */
    val automation: Boolean = false,
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

        public const val DEFAULT_SAMPLING_S: Int = 5

        /** SPEC 14.4: 2 to 60 s. */
        public val SAMPLING_S: IntRange = 2..60

        /** Raw samples under "longer raw retention"; SPEC 7.7's own is 14 days. */
        public const val LONG_RAW_DAYS: Int = 60
    }
}

/** Reads and writes [UserSettings] in Proto DataStore (SPEC 8.1). */
@Suppress("TooManyFunctions") // One setter per row of SPEC 14.4, each holding its value to the spec's range.
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
                        theme = ThemeChoice.entries.getOrElse(it.theme) { ThemeChoice.SYSTEM },
                        fahrenheit = it.fahrenheit,
                        liteMode = it.lite_mode,
                        screenOnSamplingS =
                            it.screen_on_sampling_s.takeIf { s -> s in UserSettings.SAMPLING_S } ?: UserSettings.DEFAULT_SAMPLING_S,
                        longRawRetention = it.long_raw_retention,
                        developerConsole = it.developer_console,
                        automation = it.automation,
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

        /**
         * The settings were restored from a backup of another install (ADR-0062 §15): what stands
         * for a grant or a consent given there starts off again, and onboarding runs, because
         * this install has neither. Nothing changes on an install that has no settings yet.
         */
        public suspend fun startedFromBackup() {
            store.updateData { it.copy(onboarded = false, action_center = false, automation = false) }
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

        public suspend fun theme(choice: ThemeChoice) {
            store.updateData { it.copy(theme = choice.ordinal) }
        }

        public suspend fun fahrenheit(on: Boolean) {
            store.updateData { it.copy(fahrenheit = on) }
        }

        public suspend fun liteMode(on: Boolean) {
            store.updateData { it.copy(lite_mode = on) }
        }

        /** Stores the interval, held to [UserSettings.SAMPLING_S]. */
        public suspend fun screenOnSampling(seconds: Int) {
            store.updateData { it.copy(screen_on_sampling_s = seconds.coerceIn(UserSettings.SAMPLING_S)) }
        }

        public suspend fun longRawRetention(on: Boolean) {
            store.updateData { it.copy(long_raw_retention = on) }
        }

        public suspend fun developerConsole(on: Boolean) {
            store.updateData { it.copy(developer_console = on) }
        }

        public suspend fun automation(on: Boolean) {
            store.updateData { it.copy(automation = on) }
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

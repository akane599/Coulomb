// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.datastore

import androidx.datastore.core.DataStore
import io.github.akane599.coulomb.core.datastore.proto.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        /** True from a failed [startedFromBackup] until a write stores its reset (ADR-0062 §15; audit DATA-002). */
        private val resetOwed = MutableStateFlow(false)

        /** One at a time on the owed path, so two first writes do not apply the reset over each other. */
        private val owedWrites = Mutex()

        /** False while a restored install's reset is owed; the app marks its first start done once it is stored. */
        public val resetStored: Flow<Boolean> = resetOwed.map { !it }

        /**
         * The settings as stored. While a restored install's reset is owed they read as that reset
         * would leave them: nothing that stands for a consent given on another install is on.
         */
        public val settings: Flow<UserSettings> =
            combine(store.data, resetOwed) { stored, owed -> read(if (owed) stored.withoutConsents() else stored) }
                .distinctUntilChanged()

        private fun read(stored: Settings): UserSettings =
            UserSettings(
                showSystemApps = stored.show_system_apps,
                scaleToMeasured = stored.scale_to_measured,
                componentBreakdown = stored.component_breakdown,
                // 0 is unset; a value outside the range (an older or edited file) falls back too.
                chargeTargetPct =
                    stored.charge_target_pct.takeIf { pct -> pct in UserSettings.CHARGE_TARGETS }
                        ?: UserSettings.DEFAULT_CHARGE_TARGET,
                onboarded = stored.onboarded,
                chargeAlarm = stored.charge_alarm,
                temperatureAlarmDeciC =
                    when (val alarm = stored.temperature_alarm_deci_c) {
                        ALARM_OFF -> null
                        in UserSettings.TEMPERATURE_ALARMS -> alarm
                        else -> UserSettings.DEFAULT_TEMPERATURE_ALARM
                    },
                insightsDigest = !stored.insights_digest_off,
                insightsDigestMinute =
                    (stored.insights_digest_minute_plus_one - 1).takeIf { minute -> minute in 0 until UserSettings.MINUTES_PER_DAY }
                        ?: UserSettings.DEFAULT_DIGEST_MINUTE,
                actionCenter = stored.action_center,
                theme = ThemeChoice.entries.getOrElse(stored.theme) { ThemeChoice.SYSTEM },
                fahrenheit = stored.fahrenheit,
                liteMode = stored.lite_mode,
                screenOnSamplingS =
                    stored.screen_on_sampling_s.takeIf { s -> s in UserSettings.SAMPLING_S } ?: UserSettings.DEFAULT_SAMPLING_S,
                longRawRetention = stored.long_raw_retention,
                developerConsole = stored.developer_console,
                automation = stored.automation,
            )

        public suspend fun showSystemApps(show: Boolean) {
            update { it.copy(show_system_apps = show) }
        }

        public suspend fun scaleToMeasured(scale: Boolean) {
            update { it.copy(scale_to_measured = scale) }
        }

        public suspend fun componentBreakdown(on: Boolean) {
            update { it.copy(component_breakdown = on) }
        }

        /** Onboarding is done: the app opens on Now from here on (ADR-0047 §1). */
        public suspend fun onboarded() {
            update { it.copy(onboarded = true) }
        }

        /**
         * The settings were restored from a backup of another install (ADR-0062 §15): what stands
         * for a grant or a consent given there starts off again, and onboarding runs, because
         * this install has neither. Nothing changes on an install that has no settings yet.
         */
        public suspend fun startedFromBackup() {
            // Owed before the write: if it fails, this process still reads the consents as off, and
            // the next write stores the reset with itself (audit DATA-002).
            resetOwed.value = true
            update { it }
        }

        /**
         * One settings write. While a restored install's reset is owed, the write stores the reset
         * with it, so once a write has gone through what is read is what the file holds.
         */
        private suspend fun update(transform: (Settings) -> Settings) {
            if (!resetOwed.value) {
                store.updateData { transform(it) }
                return
            }
            owedWrites.withLock {
                val owed = resetOwed.value
                store.updateData { current -> transform(if (owed) current.withoutConsents() else current) }
                resetOwed.value = false
            }
        }

        private fun Settings.withoutConsents(): Settings = copy(onboarded = false, action_center = false, automation = false)

        public suspend fun chargeAlarm(on: Boolean) {
            update { it.copy(charge_alarm = on) }
        }

        /** Stores the alarm's threshold, held to [UserSettings.TEMPERATURE_ALARMS]; null turns the alarm off. */
        public suspend fun temperatureAlarm(deciC: Int?) {
            val stored = deciC?.coerceIn(UserSettings.TEMPERATURE_ALARMS) ?: ALARM_OFF
            update { it.copy(temperature_alarm_deci_c = stored) }
        }

        public suspend fun actionCenter(on: Boolean) {
            update { it.copy(action_center = on) }
        }

        public suspend fun theme(choice: ThemeChoice) {
            update { it.copy(theme = choice.ordinal) }
        }

        public suspend fun fahrenheit(on: Boolean) {
            update { it.copy(fahrenheit = on) }
        }

        public suspend fun liteMode(on: Boolean) {
            update { it.copy(lite_mode = on) }
        }

        /** Stores the interval, held to [UserSettings.SAMPLING_S]. */
        public suspend fun screenOnSampling(seconds: Int) {
            update { it.copy(screen_on_sampling_s = seconds.coerceIn(UserSettings.SAMPLING_S)) }
        }

        public suspend fun longRawRetention(on: Boolean) {
            update { it.copy(long_raw_retention = on) }
        }

        public suspend fun developerConsole(on: Boolean) {
            update { it.copy(developer_console = on) }
        }

        public suspend fun automation(on: Boolean) {
            update { it.copy(automation = on) }
        }

        public suspend fun insightsDigest(on: Boolean) {
            update { it.copy(insights_digest_off = !on) }
        }

        /** Stores the minute of the day from which the digest may post. */
        public suspend fun insightsDigestMinute(minute: Int) {
            val held = minute.coerceIn(0, UserSettings.MINUTES_PER_DAY - 1)
            update { it.copy(insights_digest_minute_plus_one = held + 1) }
        }

        /** Stores [pct], held to [UserSettings.CHARGE_TARGETS]. */
        public suspend fun chargeTarget(pct: Int) {
            val held = pct.coerceIn(UserSettings.CHARGE_TARGETS)
            update { it.copy(charge_target_pct = held) }
        }

        private companion object {
            /** The stored value of a temperature alarm that is off. */
            const val ALARM_OFF = -1
        }
    }

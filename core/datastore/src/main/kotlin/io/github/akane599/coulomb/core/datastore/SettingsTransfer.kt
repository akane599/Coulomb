// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.datastore

import kotlinx.coroutines.flow.first
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The settings as a bundle carries them (ADR-0062 §12): named keys, which outlive the stored
 * form. Every setting is written, so the file says what the phone was set to. Only the ones that
 * mean the same on any phone are ever applied from a file, and only when the user asks: a switch
 * that stands for a grant, a permission or a consent given on the old phone ([NEVER_APPLIED]) is
 * turned on by hand on the new one.
 */
@Singleton
public class SettingsTransfer
    @Inject
    constructor(
        private val store: SettingsStore,
    ) {
        /** The settings now, as `settings.json`. */
        public suspend fun toJson(): String {
            val now = store.settings.first()
            return JSONObject()
                .put(SHOW_SYSTEM_APPS, now.showSystemApps)
                .put(SCALE_TO_MEASURED, now.scaleToMeasured)
                .put(CHARGE_TARGET_PCT, now.chargeTargetPct)
                .put(TEMPERATURE_ALARM_DECI_C, now.temperatureAlarmDeciC ?: JSONObject.NULL)
                .put(INSIGHTS_DIGEST_MINUTE, now.insightsDigestMinute)
                .put(THEME, now.theme.name)
                .put(FAHRENHEIT, now.fahrenheit)
                .put(SCREEN_ON_SAMPLING_S, now.screenOnSamplingS)
                .put("component_breakdown", now.componentBreakdown)
                .put("charge_alarm", now.chargeAlarm)
                .put("insights_digest", now.insightsDigest)
                .put("action_center", now.actionCenter)
                .put("lite_mode", now.liteMode)
                .put("long_raw_retention", now.longRawRetention)
                .put("automation", now.automation)
                .toString(2)
        }

        /**
         * Applies the neutral settings of [json], a bundle's `settings.json`; a key that is missing
         * or not of its type is left as it is here. Returns false when [json] is not a settings file.
         */
        public suspend fun apply(json: String): Boolean {
            val file =
                try {
                    JSONObject(json)
                } catch (_: JSONException) {
                    return false
                }
            (file.opt(SHOW_SYSTEM_APPS) as? Boolean)?.let { store.showSystemApps(it) }
            (file.opt(SCALE_TO_MEASURED) as? Boolean)?.let { store.scaleToMeasured(it) }
            (file.opt(CHARGE_TARGET_PCT) as? Int)?.takeIf { it in UserSettings.CHARGE_TARGETS }?.let { store.chargeTarget(it) }
            when (val alarm = file.opt(TEMPERATURE_ALARM_DECI_C)) {
                JSONObject.NULL -> store.temperatureAlarm(null)
                is Int -> if (alarm in UserSettings.TEMPERATURE_ALARMS) store.temperatureAlarm(alarm)
            }
            (
                file.opt(
                    INSIGHTS_DIGEST_MINUTE,
                ) as? Int
            )?.takeIf { it in 0 until UserSettings.MINUTES_PER_DAY }?.let { store.insightsDigestMinute(it) }
            (file.opt(THEME) as? String)?.let { name -> ThemeChoice.entries.firstOrNull { it.name == name } }?.let { store.theme(it) }
            (file.opt(FAHRENHEIT) as? Boolean)?.let { store.fahrenheit(it) }
            (file.opt(SCREEN_ON_SAMPLING_S) as? Int)?.takeIf { it in UserSettings.SAMPLING_S }?.let { store.screenOnSampling(it) }
            return true
        }

        public companion object {
            private const val SHOW_SYSTEM_APPS = "show_system_apps"
            private const val SCALE_TO_MEASURED = "scale_to_measured"
            private const val CHARGE_TARGET_PCT = "charge_target_pct"
            private const val TEMPERATURE_ALARM_DECI_C = "temperature_alarm_deci_c"
            private const val INSIGHTS_DIGEST_MINUTE = "insights_digest_minute"
            private const val THEME = "theme"
            private const val FAHRENHEIT = "fahrenheit"
            private const val SCREEN_ON_SAMPLING_S = "screen_on_sampling_s"

            /** Written for the record and never applied from a file. `onboarded` and the developer console are not written at all. */
            public val NEVER_APPLIED: Set<String> =
                setOf(
                    "component_breakdown",
                    "charge_alarm",
                    "insights_digest",
                    "action_center",
                    "lite_mode",
                    "long_raw_retention",
                    "automation",
                )
        }
    }

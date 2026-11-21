// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.datastore

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.IOException
import java.io.StringReader
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
         * The temperature alarm takes a threshold from a file and is never switched off by one.
         */
        public suspend fun apply(json: String): Boolean {
            val file = flat(json) ?: return false
            (file[SHOW_SYSTEM_APPS] as? Boolean)?.let { store.showSystemApps(it) }
            (file[SCALE_TO_MEASURED] as? Boolean)?.let { store.scaleToMeasured(it) }
            (file[CHARGE_TARGET_PCT] as? Int)?.takeIf { it in UserSettings.CHARGE_TARGETS }?.let { store.chargeTarget(it) }
            (file[TEMPERATURE_ALARM_DECI_C] as? Int)?.takeIf { it in UserSettings.TEMPERATURE_ALARMS }?.let { store.temperatureAlarm(it) }
            (file[INSIGHTS_DIGEST_MINUTE] as? Int)
                ?.takeIf { it in 0 until UserSettings.MINUTES_PER_DAY }
                ?.let { store.insightsDigestMinute(it) }
            (file[THEME] as? String)?.let { name -> ThemeChoice.entries.firstOrNull { it.name == name } }?.let { store.theme(it) }
            (file[FAHRENHEIT] as? Boolean)?.let { store.fahrenheit(it) }
            (file[SCREEN_ON_SAMPLING_S] as? Int)?.takeIf { it in UserSettings.SAMPLING_S }?.let { store.screenOnSampling(it) }
            return true
        }

        /**
         * [json] as one flat object of switches, whole numbers and words, or null when it is
         * anything else. The text is another phone's file: it is read strictly and token by
         * token, so nesting is refused where it starts and never reaches a recursive parser.
         */
        private fun flat(json: String): Map<String, Any>? {
            if (json.length > MAX_CHARS) return null
            val reader = JsonReader(StringReader(json))
            val values = HashMap<String, Any>()
            return try {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    values[name] = plain(reader) ?: continue
                }
                reader.endObject()
                values.takeIf { reader.peek() == JsonToken.END_DOCUMENT }
            } catch (_: IOException) {
                null
            } catch (_: IllegalStateException) {
                // An array or an object where a plain value belongs.
                null
            }
        }

        /** The next value when it is a switch, a whole number or a word; null for a null or a fraction. */
        private fun plain(reader: JsonReader): Any? =
            when (reader.peek()) {
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.STRING -> reader.nextString()
                JsonToken.NUMBER -> reader.nextString().toIntOrNull()
                JsonToken.NULL -> reader.nextNull().let { null }
                else -> error("nested")
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

            /** Coulomb's own file is under a kilobyte. */
            private const val MAX_CHARS = 64 * 1024

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

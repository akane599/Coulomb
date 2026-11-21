// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** One rule's thresholds by name. A name the file lacks is a broken bundle, not a default. */
public class RuleThresholds internal constructor(
    private val rule: String,
    private val values: Map<String, Double>,
) {
    public operator fun get(name: String): Double = values[name] ?: error("insights.json has no $rule.$name")

    public val names: Set<String> get() = values.keys
}

/**
 * SPEC 9.1: the rules' thresholds, read from `insights.json` bundled with the app, so tuning one
 * needs no code change. The file is an object of rule codes, each an object of numbers.
 */
public class Thresholds private constructor(
    private val byRule: Map<String, RuleThresholds>,
) {
    public fun of(rule: RuleId): RuleThresholds = byRule[rule.code] ?: error("insights.json has no ${rule.code}")

    public companion object {
        private const val BUNDLED = "/insights.json"

        /** Reads [json]; throws on text that is not the shape above, since the file ships with the app. */
        public fun parse(json: String): Thresholds {
            val root = Json.parseToJsonElement(json).jsonObject
            return Thresholds(
                root.mapValues { (rule, values) ->
                    val numbers = (values as JsonObject).mapValues { (name, value) -> number(rule, name, value as? JsonPrimitive) }
                    RuleThresholds(rule, numbers)
                },
            )
        }

        /** The thresholds the app ships with. */
        public fun bundled(): Thresholds {
            val stream = checkNotNull(Thresholds::class.java.getResourceAsStream(BUNDLED)) { "$BUNDLED is not bundled" }
            return parse(stream.use { it.readBytes().decodeToString() })
        }

        private fun number(
            rule: String,
            name: String,
            value: JsonPrimitive?,
        ): Double = value?.takeUnless { it.isString }?.doubleOrNull ?: error("insights.json: $rule.$name is not a number")
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The unit of an [Evidence]'s measured value and threshold; the UI formats by it (SPEC 9.3). */
public enum class EvidenceUnit {
    HOURS,
    MINUTES,
    PERCENT,
    PERCENT_PER_HOUR,
    PER_HOUR,
    COUNT,
    CELSIUS,
    WATTS,
    POINTS,
    DAYS,
    MAH,
}

/**
 * Why an insight exists: what "Why am I seeing this?" shows (SPEC 9.4). [measured] and
 * [threshold] are the rule's main comparison, in [unit]. [values] holds its other numbers by name
 * (a second threshold, a baseline, a count) and [labels] its names (a tag, a charger). The span is
 * the time the measurement covers, and [sessionId] the session it was made in, which the insight's
 * card opens.
 *
 * Stored as `Insight.evidenceJson`. Reading is lenient: a field this version does not know is
 * ignored, and text that is not evidence reads as null rather than failing a screen.
 */
public data class Evidence(
    val measured: Double,
    val threshold: Double,
    val unit: EvidenceUnit,
    val fromWallMs: Long,
    val toWallMs: Long,
    val values: Map<String, Double> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
    val sessionId: Long? = null,
) {
    public fun toJson(): String =
        buildJsonObject {
            put("measured", measured)
            put("threshold", threshold)
            put("unit", unit.name)
            put("from", fromWallMs)
            put("to", toWallMs)
            sessionId?.let { put("session", it) }
            if (values.isNotEmpty()) putJsonObject("values") { values.forEach { (name, value) -> put(name, value) } }
            if (labels.isNotEmpty()) putJsonObject("labels") { labels.forEach { (name, value) -> put(name, value) } }
        }.toString()

    public companion object {
        public fun fromJson(json: String): Evidence? {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            val unit = root.text("unit")?.let { name -> EvidenceUnit.entries.firstOrNull { it.name == name } } ?: return null
            val measured = root.number("measured") ?: return null
            val threshold = root.number("threshold") ?: return null
            val from = (root["from"] as? JsonPrimitive)?.longOrNull ?: return null
            val to = (root["to"] as? JsonPrimitive)?.longOrNull ?: return null
            return Evidence(
                measured,
                threshold,
                unit,
                from,
                to,
                values = root.members("values").mapNotNull { (name, value) -> value.doubleOrNull?.let { name to it } }.toMap(),
                labels = root.members("labels").filter { it.value.isString }.mapValues { it.value.content },
                sessionId = (root["session"] as? JsonPrimitive)?.longOrNull,
            )
        }

        private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

        private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.members(name: String): Map<String, JsonPrimitive> =
            (this[name] as? JsonObject)
                ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.let { key to it } }
                ?.toMap()
                .orEmpty()
    }
}

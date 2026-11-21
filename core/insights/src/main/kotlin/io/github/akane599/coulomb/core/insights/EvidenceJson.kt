// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import io.github.akane599.coulomb.core.model.insight.Evidence
import io.github.akane599.coulomb.core.model.insight.EvidenceUnit
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

/**
 * `Insight.evidenceJson`: an [Evidence] as a small JSON object. Reading is lenient: a field this
 * version does not know is ignored, and text that is not evidence reads as null rather than failing
 * a screen.
 */
public object EvidenceJson {
    public fun encode(evidence: Evidence): String =
        buildJsonObject {
            put("measured", evidence.measured)
            put("threshold", evidence.threshold)
            put("unit", evidence.unit.name)
            put("from", evidence.fromWallMs)
            put("to", evidence.toWallMs)
            evidence.sessionId?.let { put("session", it) }
            if (evidence.values.isNotEmpty()) putJsonObject("values") { evidence.values.forEach { (name, value) -> put(name, value) } }
            if (evidence.labels.isNotEmpty()) putJsonObject("labels") { evidence.labels.forEach { (name, value) -> put(name, value) } }
        }.toString()

    public fun decode(json: String): Evidence? {
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

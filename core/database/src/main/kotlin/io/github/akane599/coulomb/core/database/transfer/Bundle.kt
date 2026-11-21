// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.transfer

import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Why a file was not imported (ADR-0062 §10, §11). */
public enum class BundleProblem {
    /** Not a Coulomb bundle, or a format this build does not know. */
    NOT_A_BUNDLE,

    /** Written by a newer Coulomb: its schema or format is ahead of this build's. */
    NEWER,

    /** From a schema older than the first that could export. */
    TOO_OLD,

    /** The export did not finish: the closing entry is missing. */
    INCOMPLETE,

    /** Something in it is not what Coulomb writes: a table, a row, a count or a check after the load. */
    DAMAGED,

    /** Larger than any bundle Coulomb writes. */
    TOO_LARGE,

    /** This bundle is already an archive on this phone. */
    ALREADY_IMPORTED,
}

/** A bundle that is refused whole; nothing of it is kept. [detail] is for the log and tests, not for the screen. */
public class BundleRejectedException(
    public val problem: BundleProblem,
    detail: String,
    cause: Throwable? = null,
) : Exception("$problem: $detail", cause)

/** Refuses the bundle being read: the one place a refusal is thrown from. */
internal fun refuse(
    problem: BundleProblem,
    detail: String,
    cause: Throwable? = null,
): Nothing = throw BundleRejectedException(problem, detail, cause)

/** The names and numbers of the bundle's container (ADR-0062 §1 to §3). Never change a name; a new form is a new major. */
public object BundleFormat {
    public const val NAME: String = "coulomb-bundle"
    public const val MAJOR: Int = 1
    public const val MINOR: Int = 0

    /** The first schema version that could export, so the oldest a bundle can carry. */
    public const val OLDEST_SCHEMA: Int = 7

    public const val MANIFEST: String = "manifest.json"
    public const val README: String = "README.txt"
    public const val SETTINGS: String = "settings.json"
    public const val END: String = "end.json"
    public const val TABLES: String = "tables/"
    public const val ROWS: String = ".ndjson"

    public const val MIME: String = "application/zip"
}

/**
 * What a bundle says about itself, in `manifest.json`, its first entry: which build wrote it, from
 * which schema and phone, and every table with its columns in the order of each row's values.
 * [firstWallMs] and [lastWallMs] span the sessions it holds; null when it holds none.
 */
public data class BundleManifest(
    val bundleId: String,
    val exportedAtWallMs: Long,
    val zone: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val schemaVersion: Int,
    val identityHash: String,
    val model: String,
    val oem: String,
    val fingerprint: String,
    val sdkInt: Int,
    val firstWallMs: Long?,
    val lastWallMs: Long?,
    val tables: List<DumpTable>,
    val formatMajor: Int = BundleFormat.MAJOR,
    val formatMinor: Int = BundleFormat.MINOR,
) {
    public fun toJson(): String {
        val root =
            JSONObject()
                .put("format", BundleFormat.NAME)
                .put("formatVersion", JSONObject().put("major", formatMajor).put("minor", formatMinor))
                .put("bundleId", bundleId)
                .put("exportedAtWallMs", exportedAtWallMs)
                .put("zone", zone)
                .put("app", JSONObject().put("versionName", appVersionName).put("versionCode", appVersionCode))
                .put("schema", JSONObject().put("version", schemaVersion).put("identityHash", identityHash))
                .put(
                    "device",
                    JSONObject()
                        .put("model", model)
                        .put("oem", oem)
                        .put("fingerprint", fingerprint)
                        .put("sdkInt", sdkInt),
                ).put(
                    "span",
                    JSONObject().put("firstWallMs", firstWallMs ?: JSONObject.NULL).put("lastWallMs", lastWallMs ?: JSONObject.NULL),
                ).put("codes", JSONObject(CodedColumns.NAMES.mapValues { JSONArray(it.value) }))
                .put("tables", JSONArray(tables.map(::tableJson)))
        return root.toString(2)
    }

    private fun tableJson(table: DumpTable): JSONObject =
        JSONObject()
            .put("name", table.name)
            .put("primaryKey", JSONArray(table.primaryKey))
            .put(
                "columns",
                JSONArray(table.columns.map { JSONObject().put("name", it.name).put("type", it.type).put("notNull", it.notNull) }),
            )

    public companion object {
        /**
         * The manifest in [text]. Throws [BundleRejectedException]: [BundleProblem.NOT_A_BUNDLE]
         * for anything that is not one, [BundleProblem.NEWER] for a format ahead of this build's.
         */
        public fun parse(text: String): BundleManifest {
            try {
                val root = JSONObject(text)
                if (root.optString("format") != BundleFormat.NAME) refuse(BundleProblem.NOT_A_BUNDLE, "format")
                val version = root.getJSONObject("formatVersion")
                if (version.getInt("major") > BundleFormat.MAJOR) refuse(BundleProblem.NEWER, "format major")
                return read(root, version)
            } catch (e: JSONException) {
                refuse(BundleProblem.NOT_A_BUNDLE, e.message.orEmpty(), e)
            }
        }

        private fun read(
            root: JSONObject,
            version: JSONObject,
        ): BundleManifest {
            val app = root.getJSONObject("app")
            val schema = root.getJSONObject("schema")
            val device = root.getJSONObject("device")
            val span = root.getJSONObject("span")
            val tables = root.getJSONArray("tables")
            return BundleManifest(
                bundleId = root.getString("bundleId"),
                exportedAtWallMs = root.getLong("exportedAtWallMs"),
                zone = root.getString("zone"),
                appVersionName = app.getString("versionName"),
                appVersionCode = app.getLong("versionCode"),
                schemaVersion = schema.getInt("version"),
                identityHash = schema.getString("identityHash"),
                model = device.getString("model"),
                oem = device.getString("oem"),
                fingerprint = device.getString("fingerprint"),
                sdkInt = device.getInt("sdkInt"),
                firstWallMs = if (span.isNull("firstWallMs")) null else span.getLong("firstWallMs"),
                lastWallMs = if (span.isNull("lastWallMs")) null else span.getLong("lastWallMs"),
                tables = (0 until tables.length()).map { table(tables.getJSONObject(it)) },
                formatMajor = version.getInt("major"),
                formatMinor = version.getInt("minor"),
            )
        }

        private fun table(json: JSONObject): DumpTable {
            val columns = json.getJSONArray("columns")
            val key = json.getJSONArray("primaryKey")
            return DumpTable(
                name = json.getString("name"),
                columns =
                    (0 until columns.length()).map {
                        val column = columns.getJSONObject(it)
                        DumpColumn(column.getString("name"), column.getString("type"), column.getBoolean("notNull"))
                    },
                primaryKey = (0 until key.length()).map(key::getString),
            )
        }
    }
}

/**
 * One row as one line of JSON (ADR-0062 §2): an array of the stored values in column order.
 * Integers stay integers up to 64 bits, a real is written in a form that reads back to the same
 * bits, a blob is base64, and a real that JSON has no number for is its name in quotes.
 */
internal object JsonRows {
    fun encode(
        row: Array<Any?>,
        out: StringBuilder,
    ) {
        out.append('[')
        row.forEachIndexed { index, value ->
            if (index > 0) out.append(',')
            when (value) {
                null -> out.append("null")
                is Long -> out.append(value)
                is Double -> if (value.isFinite()) out.append(value) else out.append('"').append(value).append('"')
                is ByteArray -> out.append('"').append(Base64.encodeToString(value, Base64.NO_WRAP)).append('"')
                else -> text(value.toString(), out)
            }
        }
        out.append(']')
    }

    /** The values of [line] for [table]'s columns, each checked against its column. Throws [BundleRejectedException]. */
    fun decode(
        line: String,
        table: DumpTable,
    ): Array<Any?> {
        val values =
            try {
                JSONArray(line)
            } catch (e: JSONException) {
                refuse(BundleProblem.DAMAGED, "${table.name}: not a row", e)
            }
        if (values.length() != table.columns.size) damaged(table, "${values.length()} values for ${table.columns.size} columns")
        return Array(table.columns.size) { index -> value(values.get(index), table.columns[index], table) }
    }

    private fun value(
        json: Any,
        column: DumpColumn,
        table: DumpTable,
    ): Any? {
        if (json == JSONObject.NULL) {
            if (column.notNull) damaged(table, "null in ${column.name}")
            return null
        }
        val value =
            when (column.type) {
                "INTEGER" -> (json as? Int)?.toLong() ?: json as? Long
                "REAL" -> (json as? Number)?.toDouble() ?: (json as? String)?.let(::unbounded)
                "TEXT" -> (json as? String)?.takeIf { it.length <= MAX_TEXT }
                "BLOB" -> (json as? String)?.let(::blob)
                else -> null
            }
        return value ?: damaged(table, "${column.name} is not ${column.type}")
    }

    private fun unbounded(name: String): Double? = name.toDoubleOrNull()?.takeIf { !it.isFinite() }

    private fun blob(text: String): ByteArray? =
        try {
            Base64.decode(text, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun damaged(
        table: DumpTable,
        what: String,
    ): Nothing = refuse(BundleProblem.DAMAGED, "${table.name}: $what")

    private fun text(
        value: String,
        out: StringBuilder,
    ) {
        out.append('"')
        for (c in value) {
            when {
                c == '"' -> {
                    out.append("\\\"")
                }

                c == '\\' -> {
                    out.append("\\\\")
                }

                c == '\n' -> {
                    out.append("\\n")
                }

                c < ' ' || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR -> {
                    out
                        .append(
                            "\\u",
                        ).append(c.code.toString(HEX).padStart(HEX_DIGITS, '0'))
                }

                else -> {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    /** No text column of Coulomb's comes near this; a longer one is not Coulomb's. */
    private const val MAX_TEXT = 1 shl 20
    private const val HEX = 16
    private const val HEX_DIGITS = 4
    private const val LINE_SEPARATOR = ' '
    private const val PARAGRAPH_SEPARATOR = ' '
}

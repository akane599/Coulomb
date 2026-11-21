// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.transfer

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.StringReader

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

    /** Not the bundle's fault: the phone has no room for it, or the file could not be read. */
    FAILED,
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

/**
 * True when [text] is strict JSON that nests no deeper than [limit]. A bundle's small entries are
 * held to this before `org.json` reads them: its parser is lenient and recursive, so a few
 * kilobytes of opening brackets would end the process with a stack overflow. This reader is
 * neither.
 */
internal fun strictJson(
    text: String,
    limit: Int,
): Boolean {
    val reader = JsonReader(StringReader(text))
    var depth = 0
    return try {
        while (depth <= limit) {
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> reader.beginArray().also { depth++ }
                JsonToken.BEGIN_OBJECT -> reader.beginObject().also { depth++ }
                JsonToken.END_ARRAY -> reader.endArray().also { depth-- }
                JsonToken.END_OBJECT -> reader.endObject().also { depth-- }
                JsonToken.NAME -> reader.nextName()
                JsonToken.END_DOCUMENT -> break
                else -> reader.skipValue()
            }
        }
        depth <= limit
    } catch (_: IOException) {
        false
    } catch (_: IllegalStateException) {
        false
    }
}

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

    /** Deeper than anything in a bundle's manifest, settings or closing entry. */
    internal const val MAX_DEPTH: Int = 8
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
            if (!strictJson(text, BundleFormat.MAX_DEPTH)) refuse(BundleProblem.NOT_A_BUNDLE, "not strict JSON")
            try {
                val root = JSONObject(text)
                if (root.optString("format") != BundleFormat.NAME) refuse(BundleProblem.NOT_A_BUNDLE, "format")
                val version = root.getJSONObject("formatVersion")
                if (version.getInt("major") > BundleFormat.MAJOR) refuse(BundleProblem.NEWER, "format major")
                sameCodes(root.optJSONObject("codes"))
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

        /**
         * A list of codes only ever grows at its end (ADR-0040), so the bundle's lists and this
         * build's must agree as far as both go: a code that stood for another name would relabel
         * every row that holds it.
         */
        private fun sameCodes(codes: JSONObject?) {
            for ((column, names) in CodedColumns.NAMES) {
                val theirs = codes?.optJSONArray(column) ?: continue
                for (code in 0 until minOf(theirs.length(), names.size)) {
                    if (theirs.optString(code) != names[code]) refuse(BundleProblem.DAMAGED, "$column: code $code is another name")
                }
            }
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

    /**
     * The values of [line] for [table]'s columns, each checked against its column. Throws
     * [BundleRejectedException]. The line is read strictly and token by token: exactly one flat
     * array of as many values as the table has columns, with nothing nested and nothing after it.
     */
    fun decode(
        line: String,
        table: DumpTable,
    ): Array<Any?> {
        val reader = JsonReader(StringReader(line))
        try {
            reader.beginArray()
            val row = Array(table.columns.size) { index -> value(reader, table.columns[index], table) }
            reader.endArray()
            if (reader.peek() != JsonToken.END_DOCUMENT) damaged(table, "text after a row")
            return row
        } catch (e: IOException) {
            refuse(BundleProblem.DAMAGED, "${table.name}: not a row", e)
        } catch (e: IllegalStateException) {
            // Fewer or more values than columns: the reader met another token than the one asked for.
            refuse(BundleProblem.DAMAGED, "${table.name}: not a row of ${table.columns.size} values", e)
        }
    }

    private fun value(
        reader: JsonReader,
        column: DumpColumn,
        table: DumpTable,
    ): Any? {
        val value: Any? =
            when (reader.peek()) {
                JsonToken.NULL -> {
                    reader.nextNull()
                    if (column.notNull) damaged(table, "null in ${column.name}")
                    return null
                }

                JsonToken.NUMBER -> {
                    number(reader.nextString(), column.type)
                }

                JsonToken.STRING -> {
                    text(reader.nextString(), column.type)
                }

                else -> {
                    null
                }
            }
        return value ?: damaged(table, "${column.name} is not ${column.type}")
    }

    /** A number as it is written: an integer column takes no fraction, and a real column any finite number. */
    private fun number(
        literal: String,
        type: String,
    ): Any? =
        when (type) {
            "INTEGER" -> literal.toLongOrNull()
            "REAL" -> literal.toDoubleOrNull()?.takeIf { it.isFinite() }
            else -> null
        }

    private fun text(
        text: String,
        type: String,
    ): Any? =
        when (type) {
            "REAL" -> unbounded(text)
            "TEXT" -> text.takeIf { it.length <= MAX_TEXT }
            "BLOB" -> blob(text)
            else -> null
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

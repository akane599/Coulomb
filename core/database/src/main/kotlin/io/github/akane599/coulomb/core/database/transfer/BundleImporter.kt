// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.transfer

import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import androidx.room.Room
import io.github.akane599.coulomb.core.database.CoulombDatabase
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** A bundle that passed every check, as a database file at this build's schema. */
public class ImportedBundle(
    public val manifest: BundleManifest,
    /** `settings.json` as it was in the bundle; the caller decides what of it applies (ADR-0062 §12). */
    public val settings: String,
    public val database: File,
)

/**
 * Reads one of Coulomb's JSON bundles into a database file of its own (SPEC 14.5, ADR-0062 §9 to
 * §11). The bundle is treated as hostile: entries are read by the names Coulomb writes and nothing
 * is extracted to a path the bundle gives; sizes are counted while inflating; its tables and
 * columns must be the schema's for its version; no SQL is built from its text; every value is
 * checked against its column and bound as a parameter. One failed check refuses the whole bundle
 * and deletes what was written: a skipped row would read as a real gap.
 *
 * An older bundle is not translated. The file is created in the bundle's own schema version from
 * the exported schema, loaded, and then opened as a [CoulombDatabase], which migrates and
 * validates it like any other.
 */
public class BundleImporter(
    private val context: Context,
) {
    /**
     * Loads the bundle in [input] into [into], which must not exist, and closes [input]. [progress]
     * is called with the rows loaded so far. Throws [BundleRejectedException]; [into] is then gone.
     */
    public fun load(
        input: InputStream,
        into: File,
        progress: (rows: Long) -> Unit = {},
    ): ImportedBundle {
        check(!into.exists()) { "An import never writes over a file" }
        try {
            val (manifest, settings) = input.use { Load(Bounded(it), into, progress).run() }
            // Opening it as Coulomb's own database migrates an older schema and validates the result.
            CoulombDatabase.configured(Room.databaseBuilder(context, CoulombDatabase::class.java, into.path)).run {
                openHelper.writableDatabase
                close()
            }
            return ImportedBundle(manifest, settings, into)
        } catch (e: BundleRejectedException) {
            discard(into)
            throw e
        } catch (e: ZipException) {
            discard(into)
            refuse(BundleProblem.NOT_A_BUNDLE, e.message.orEmpty(), e)
        } catch (e: IOException) {
            discarded(into, e)
        } catch (e: SQLException) {
            discarded(into, e)
        } catch (e: JSONException) {
            discarded(into, e)
        } catch (e: IllegalStateException) {
            // Room's own refusal of a database that is not its schema.
            discarded(into, e)
        }
    }

    /** Removes what was loaded and refuses the bundle as damaged, keeping [cause] for the log. */
    private fun discarded(
        file: File,
        cause: Exception,
    ): Nothing {
        discard(file)
        refuse(BundleProblem.DAMAGED, cause.message.orEmpty(), cause)
    }

    private fun discard(file: File) {
        SQLiteDatabase.deleteDatabase(file)
    }

    /** One load: the zip's entries in the order Coulomb writes them, into a database built from the schema. */
    private inner class Load(
        private val bounded: Bounded,
        private val into: File,
        private val progress: (Long) -> Unit,
    ) {
        private val zip = ZipInputStream(bounded)
        private val counts = HashMap<String, Long>()
        private var settings: String? = null
        private var rows = 0L

        fun run(): Pair<BundleManifest, String> {
            val first = zip.nextEntry ?: refuse(BundleProblem.NOT_A_BUNDLE, "empty")
            if (first.name != BundleFormat.MANIFEST) refuse(BundleProblem.NOT_A_BUNDLE, "first entry")
            val manifest = BundleManifest.parse(text(MAX_SMALL))
            val schema = SchemaAsset.read(context, manifest)
            val db = SQLiteDatabase.openOrCreateDatabase(into, null)
            try {
                schema.create(db)
                db.beginTransaction()
                val finished = entries(db, manifest)
                if (!finished) refuse(BundleProblem.INCOMPLETE, "no ${BundleFormat.END}")
                // These boots are another phone's or another install's: never the base of this one's numbering.
                db.execSQL("UPDATE boot SET imported = 1")
                db.setTransactionSuccessful()
                db.endTransaction()
                Checks.run(db, manifest)
            } finally {
                if (db.inTransaction()) db.endTransaction()
                db.close()
            }
            return manifest to (settings ?: damaged("no ${BundleFormat.SETTINGS}"))
        }

        /** Every entry after the manifest; true when the closing entry was the last one. */
        private fun entries(
            db: SQLiteDatabase,
            manifest: BundleManifest,
        ): Boolean {
            val tables = manifest.tables.associateBy { BundleFormat.TABLES + it.name + BundleFormat.ROWS }
            var finished = false
            while (true) {
                val entry = zip.nextEntry ?: break
                if (finished) damaged("an entry after ${BundleFormat.END}")
                val table = tables[entry.name]
                when {
                    table != null -> rows(db, table)
                    entry.name == BundleFormat.README -> text(MAX_SMALL)
                    entry.name == BundleFormat.SETTINGS -> settings = text(MAX_SMALL)
                    entry.name == BundleFormat.END -> finished = end(text(MAX_SMALL), manifest)
                    else -> damaged("an entry Coulomb does not write")
                }
            }
            return finished
        }

        private fun rows(
            db: SQLiteDatabase,
            table: DumpTable,
        ) {
            if (counts.containsKey(table.name)) damaged("${table.name} twice")
            val columns = table.columns.joinToString { "`${it.name}`" }
            val marks = table.columns.joinToString { "?" }
            val coded = table.columns.map { CodedColumns.names(table.name, it.name)?.size }
            var count = 0L
            db.compileStatement("INSERT INTO `${table.name}` ($columns) VALUES ($marks)").use { insert ->
                while (true) {
                    val line = line() ?: break
                    val values = JsonRows.decode(line, table)
                    values.forEachIndexed { index, value ->
                        val size = coded[index]
                        if (size != null && value is Long && value !in 0 until size) damaged("${table.name}: an unknown code")
                        insert.bind(index + 1, value)
                    }
                    insert.executeInsert()
                    insert.clearBindings()
                    count++
                    if (++rows % PROGRESS_EVERY == 0L) progress(rows)
                }
            }
            counts[table.name] = count
        }

        /** The closing entry: complete, and a count for every table equal to what was loaded. */
        private fun end(
            text: String,
            manifest: BundleManifest,
        ): Boolean {
            val stated =
                try {
                    val end = JSONObject(text)
                    if (!end.getBoolean("complete")) refuse(BundleProblem.INCOMPLETE, "not complete")
                    end.getJSONObject("rows")
                } catch (e: JSONException) {
                    refuse(BundleProblem.DAMAGED, "the closing entry", e)
                }
            for (table in manifest.tables) {
                val loaded = counts[table.name] ?: damaged("${table.name} is missing")
                if (stated.optLong(table.name, -1) != loaded) damaged("${table.name}: $loaded rows, another count stated")
            }
            return true
        }

        /** The rest of the current entry as text; refused past [max] bytes. */
        private fun text(max: Int): String {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > max) refuse(BundleProblem.TOO_LARGE, "a small entry of over $max bytes")
            }
            return out.toString(Charsets.UTF_8.name())
        }

        private val pending = ByteArray(BUFFER)
        private var pendingSize = 0
        private var pendingAt = 0
        private val lineBytes = ByteArrayOutputStream()

        /** The current entry's next line without its line feed, or null at the entry's end. */
        private fun line(): String? {
            lineBytes.reset()
            while (pendingAt < pendingSize || refill()) {
                val byte = pending[pendingAt++]
                if (byte == LINE_FEED) return lineBytes.toString(Charsets.UTF_8.name())
                lineBytes.write(byte.toInt())
                if (lineBytes.size() > MAX_LINE) refuse(BundleProblem.TOO_LARGE, "a row of over $MAX_LINE bytes")
            }
            // A last line without a line feed still counts.
            return if (lineBytes.size() == 0) null else lineBytes.toString(Charsets.UTF_8.name())
        }

        /** Reads the entry's next bytes; false at its end. */
        private fun refill(): Boolean {
            pendingSize = zip.read(pending).coerceAtLeast(0)
            pendingAt = 0
            return pendingSize > 0
        }

        private fun damaged(what: String): Nothing = refuse(BundleProblem.DAMAGED, what)
    }

    /** The checks after the load, on the whole database (ADR-0062 §11). */
    private object Checks {
        fun run(
            db: SQLiteDatabase,
            manifest: BundleManifest,
        ) {
            db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                if (cursor.moveToFirst()) refuse(BundleProblem.DAMAGED, "a row points at one that is not there")
            }
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst()
                if (cursor.getString(0) != "ok") refuse(BundleProblem.DAMAGED, "integrity check")
            }
            // No table has a foreign key to `boot`, and every time in the database is read against a boot.
            for (table in manifest.tables) {
                if (table.name == "boot" || table.columns.none { it.name == "bootSeq" }) continue
                db.rawQuery("SELECT 1 FROM `${table.name}` WHERE bootSeq NOT IN (SELECT bootSeq FROM boot) LIMIT 1", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        refuse(
                            BundleProblem.DAMAGED,
                            "${table.name}: a boot that is not there",
                        )
                    }
                }
            }
        }
    }

    /** [input] with the bytes read from it counted: a bundle larger than any Coulomb writes is refused, whatever its header says. */
    private class Bounded(
        private val input: InputStream,
    ) : InputStream() {
        private var read = 0L

        override fun read(): Int = input.read().also { if (it >= 0) count(1) }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = input.read(buffer, offset, length).also { if (it > 0) count(it) }

        override fun close() = input.close()

        private fun count(bytes: Int) {
            read += bytes
            if (read > MAX_COMPRESSED) refuse(BundleProblem.TOO_LARGE, "over $MAX_COMPRESSED bytes")
        }
    }

    private companion object {
        /** A year of data is tens of megabytes compressed. */
        const val MAX_COMPRESSED = 1L shl 30
        const val MAX_SMALL = 1 shl 20

        /** A history chunk's row is the longest Coulomb writes, far under this. */
        const val MAX_LINE = 8 shl 20
        const val BUFFER = 64 * 1024
        const val LINE_FEED = '\n'.code.toByte()
        const val PROGRESS_EVERY = 5_000L
    }
}

private fun SQLiteStatement.bind(
    index: Int,
    value: Any?,
) = when (value) {
    null -> bindNull(index)
    is Long -> bindLong(index, value)
    is Double -> bindDouble(index, value)
    is ByteArray -> bindBlob(index, value)
    else -> bindString(index, value.toString())
}

/**
 * The schema of one version as Room exported it (`core/database/schemas`, shipped as assets): the
 * statements that create its tables and indexes, and its identity hash.
 */
internal class SchemaAsset private constructor(
    private val version: Int,
    private val statements: List<String>,
) {
    /** Creates the empty database of this version in [db]. Foreign keys are checked after the load, not during it. */
    fun create(db: SQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = OFF")
        statements.forEach(db::execSQL)
        db.version = version
    }

    companion object {
        private const val DIRECTORY = "io.github.akane599.coulomb.core.database.CoulombDatabase"

        /**
         * The schema [manifest] names, after checking that the manifest is that schema: the same
         * identity hash, the same tables, and in each the same columns with the same types. Throws
         * [BundleRejectedException].
         */
        fun read(
            context: Context,
            manifest: BundleManifest,
        ): SchemaAsset {
            val version = manifest.schemaVersion
            if (version < BundleFormat.OLDEST_SCHEMA) refuse(BundleProblem.TOO_OLD, "schema $version")
            val text =
                try {
                    context.assets.open("$DIRECTORY/$version.json").use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: IOException) {
                    // This build ships every schema up to its own: one it does not have is ahead of it.
                    refuse(BundleProblem.NEWER, "schema $version", e)
                }
            val database = JSONObject(text).getJSONObject("database")
            if (database.getString("identityHash") != manifest.identityHash) damaged("the schema's hash")
            val entities = database.getJSONArray("entities")
            val statements = ArrayList<String>()
            val tables = HashMap<String, Set<DumpColumn>>()
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val name = entity.getString("tableName")
                statements += entity.getString("createSql").replace("\${TABLE_NAME}", name)
                // A table without an index has no such list.
                val indices = entity.optJSONArray("indices")
                for (i in 0 until (indices?.length() ?: 0)) {
                    statements += indices!!.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", name)
                }
                val fields = entity.getJSONArray("fields")
                tables[name] =
                    (0 until fields.length()).mapTo(HashSet()) {
                        val field = fields.getJSONObject(it)
                        DumpColumn(field.getString("columnName"), field.getString("affinity"), field.optBoolean("notNull"))
                    }
            }
            val setup = database.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) statements += setup.getString(index)
            if (manifest.tables.associate { it.name to it.columns.toSet() } !=
                tables
            ) {
                damaged("tables or columns that are not the schema's")
            }
            return SchemaAsset(version, statements)
        }

        private fun damaged(what: String): Nothing = refuse(BundleProblem.DAMAGED, what)
    }
}

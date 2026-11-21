// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.transfer

import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.akane599.coulomb.core.database.CoulombDatabase
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Who writes a bundle and when; the rest of the manifest is read from the database. */
public data class BundleOrigin(
    val bundleId: String,
    val exportedAtWallMs: Long,
    val zone: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val model: String,
    val oem: String,
    val fingerprint: String,
    val sdkInt: Int,
)

/**
 * A copy of the database in one statement (`VACUUM INTO`), which the exports then read at leisure:
 * the copy is one consistent state, and the collector's writer waits only while the file is
 * written, not while it is turned into JSON. This is a copy, not the compaction `VACUUM` that
 * SPEC 7.7 keeps for charging and idle: the database itself is not rewritten.
 */
public object DatabaseCopy {
    /** Writes [db] to [to], which must not exist yet. */
    public fun copy(
        db: CoulombDatabase,
        to: File,
    ) {
        check(!to.exists()) { "A copy is never written over a file" }
        db.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(to.path))
    }
}

/**
 * Writes Coulomb's JSON bundle (SPEC 14.5, ADR-0062 §1 to §6): a zip with the manifest first, one
 * line-delimited JSON file per table and a closing entry that holds every table's row count. A
 * reader that finds no closing entry knows the export did not finish.
 */
public class BundleWriter(
    private val origin: BundleOrigin,
) {
    /**
     * Writes [source] to [out] and closes it. [settings] is the settings file's text, written as it
     * is. [progress] is called with the rows written so far and the total, a few times a second at
     * most.
     */
    public fun write(
        source: SupportSQLiteDatabase,
        settings: String,
        out: OutputStream,
        progress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val dump = TableDump(source)
        val tables = dump.tables()
        val total = tables.sumOf(dump::count)
        val counts = LinkedHashMap<String, Long>()
        ZipOutputStream(out.buffered()).use { zip ->
            zip.entry(BundleFormat.MANIFEST, manifest(source, tables).toJson())
            zip.entry(BundleFormat.README, README)
            var done = 0L
            for (table in tables) {
                zip.putNextEntry(ZipEntry(BundleFormat.TABLES + table.name + BundleFormat.ROWS))
                val line = StringBuilder()
                counts[table.name] =
                    dump.rows(table) { row ->
                        line.setLength(0)
                        JsonRows.encode(row.blobless(table), line)
                        line.append('\n')
                        zip.write(line.toString().toByteArray())
                        if (++done % PROGRESS_EVERY == 0L) progress(done, total)
                    }
                zip.closeEntry()
            }
            zip.entry(BundleFormat.SETTINGS, settings)
            zip.entry(BundleFormat.END, JSONObject().put("complete", true).put("rows", JSONObject(counts.toMap())).toString())
            progress(total, total)
        }
    }

    private fun manifest(
        source: SupportSQLiteDatabase,
        tables: List<DumpTable>,
    ): BundleManifest {
        val hash =
            source.query("SELECT identity_hash FROM room_master_table").use { cursor ->
                cursor.moveToFirst()
                cursor.getString(0)
            }
        val (first, last) =
            source.query("SELECT MIN(startWall), MAX(COALESCE(endWall, startWall)) FROM session").use { cursor ->
                cursor.moveToFirst()
                (if (cursor.isNull(0)) null else cursor.getLong(0)) to (if (cursor.isNull(1)) null else cursor.getLong(1))
            }
        return BundleManifest(
            bundleId = origin.bundleId,
            exportedAtWallMs = origin.exportedAtWallMs,
            zone = origin.zone,
            appVersionName = origin.appVersionName,
            appVersionCode = origin.appVersionCode,
            schemaVersion = source.version,
            identityHash = hash,
            model = origin.model,
            oem = origin.oem,
            fingerprint = origin.fingerprint,
            sdkInt = origin.sdkInt,
            firstWallMs = first,
            lastWallMs = last,
            tables = tables,
        )
    }

    /** The snapshot files are not in the bundle, so a row does not point at one (ADR-0062 §6). */
    private fun Array<Any?>.blobless(table: DumpTable): Array<Any?> {
        if (table.name != "snapshot") return this
        val path = table.columns.indexOfFirst { it.name == "blobPath" }
        if (path >= 0) this[path] = null
        return this
    }

    private fun ZipOutputStream.entry(
        name: String,
        text: String,
    ) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray())
        closeEntry()
    }

    private companion object {
        const val PROGRESS_EVERY = 5_000L

        val README =
            """
            Coulomb data bundle
            ===================

            This zip is a copy of what Coulomb recorded on one phone. Coulomb can import it on
            another phone, where it is kept as a separate, read-only history.

            manifest.json   which build wrote it, the schema version, the phone, and every table
                            with its columns in the order of each row's values
            tables/*.ndjson one file per table, one row per line, each a JSON array
            settings.json   the settings at the time of the export
            end.json        the row count of every table; a bundle without it did not finish

            Values are in the form the database stores them:
            - integers are whole numbers of up to 64 bits. Times in nanoseconds pass 2^53, so read
              them with a tool that keeps 64-bit integers, not one that reads every number as a
              double;
            - a column listed under "codes" in the manifest holds the position of its value in
              that list;
            - a BLOB is base64 text.

            The bundle holds the names of the apps installed on the phone. It is not signed:
            Coulomb's checks on import find damage, not editing. For reading in a spreadsheet, use
            the CSV export instead.
            """.trimIndent() + "\n"
    }
}

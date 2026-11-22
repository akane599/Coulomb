// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.entity.CorruptChunkException
import io.github.akane599.coulomb.core.database.transfer.BundleImporter
import io.github.akane599.coulomb.core.database.transfer.BundleManifest
import io.github.akane599.coulomb.core.database.transfer.BundleOrigin
import io.github.akane599.coulomb.core.database.transfer.BundleRejectedException
import io.github.akane599.coulomb.core.database.transfer.BundleWriter
import io.github.akane599.coulomb.core.database.transfer.CodedColumns
import io.github.akane599.coulomb.core.database.transfer.CsvExport
import io.github.akane599.coulomb.core.database.transfer.DatabaseCopy
import io.github.akane599.coulomb.core.database.transfer.ImportedBundle
import io.github.akane599.coulomb.core.model.SessionKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A bundle imported from a file: another phone's history, kept apart from this one's (ADR-0062 §9). */
public data class Archive(
    val id: String,
    val model: String,
    /** The span of the sessions it holds; null when it holds none. */
    val firstWallMs: Long?,
    val lastWallMs: Long?,
    val exportedAtWallMs: Long,
    val sizeBytes: Long,
    /** How many discharge and charge sessions it holds; null for an archive that kept no count. */
    val discharges: Long? = null,
    val charges: Long? = null,
)

/** Why a file was not imported, in the words a screen needs (ADR-0062 §10, §11). */
public enum class ImportProblem {
    NOT_A_BUNDLE,
    NEWER,
    TOO_OLD,
    INCOMPLETE,
    DAMAGED,
    TOO_LARGE,
    ALREADY_IMPORTED,

    /** The phone ran out of room, or the file could not be read to its end. */
    FAILED,
}

public sealed interface ImportResult {
    /** [settings] is the bundle's `settings.json`, for `SettingsTransfer.apply` if the user asks. */
    public data class Imported(
        val archive: Archive,
        val settings: String,
    ) : ImportResult

    public data class Refused(
        val problem: ImportProblem,
    ) : ImportResult
}

/**
 * Coulomb's data as files (SPEC 14.5, ADR-0062): the JSON bundle and the CSV export out, a bundle
 * in as an archive, and "Delete all data". Everything here runs while its screen is open, on the
 * caller's coroutine: no job, alarm or wakelock (SPEC 7.4).
 *
 * The exports read a copy of the database, so they are one consistent state and the collector
 * keeps writing. The copy lives in the cache directory and is removed when the export ends; one
 * left by a killed process is removed by the next.
 */
@Singleton
public class DataTransfer
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val database: CoulombDatabase,
        private val dispatchers: AppDispatchers,
    ) {
        private val archiveRoot: File get() = context.noBackupFilesDir.resolve(ARCHIVES)
        private val scratch: File get() = context.cacheDir.resolve(SCRATCH)
        private val listed = MutableStateFlow<List<Archive>?>(null)

        /** One import, export or deletion at a time: each owns the staging and scratch folders while it runs. */
        private val busy = Mutex()

        /** The archives on this phone, newest export first; null until first read by [refresh]. */
        public val archives: StateFlow<List<Archive>?> = listed.asStateFlow()

        /** Reads the archive folders again, and removes what an interrupted import or export left. */
        public suspend fun refresh() {
            sweep()
            withContext(dispatchers.io) { list() }
        }

        /**
         * Removes what a killed import or export left behind: a half-loaded bundle can be large.
         * Called when Coulomb starts and from [refresh]; while an import or export runs, its files
         * are its own and nothing is removed.
         */
        public suspend fun sweep(): Unit =
            withContext(dispatchers.io) {
                if (!busy.tryLock()) return@withContext
                try {
                    archiveRoot.listFiles { file -> file.name.startsWith(STAGING) }?.forEach { it.deleteRecursively() }
                    scratch.deleteRecursively()
                } finally {
                    busy.unlock()
                }
            }

        private fun list() {
            listed.value =
                archiveRoot
                    .listFiles()
                    ?.mapNotNull(::archive)
                    .orEmpty()
                    .sortedByDescending { it.exportedAtWallMs }
        }

        /** A name for an export's file: "coulomb-SM-S938B-20261121-1420.zip". [kind] is "bundle", "csv" or "raw". */
        public fun fileName(
            kind: String,
            nowWallMs: Long,
        ): String {
            val model = Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
            val time =
                java.time.format.DateTimeFormatter
                    .ofPattern("yyyyMMdd-HHmm")
                    .withZone(ZoneId.systemDefault())
            return "coulomb-$kind-$model-${time.format(java.time.Instant.ofEpochMilli(nowWallMs))}.zip"
        }

        /**
         * Writes the JSON bundle to [out] and closes it. [settings] is `settings.json`'s text
         * (`SettingsTransfer.toJson`). Throws [IOException] when the file cannot be written.
         */
        public suspend fun exportBundle(
            out: OutputStream,
            nowWallMs: Long,
            settings: String,
            progress: (done: Long, total: Long) -> Unit = { _, _ -> },
        ) {
            withCopy { copy ->
                val origin =
                    BundleOrigin(
                        bundleId = UUID.randomUUID().toString(),
                        exportedAtWallMs = nowWallMs,
                        zone = ZoneId.systemDefault().id,
                        appVersionName = version().first,
                        appVersionCode = version().second,
                        model = Build.MODEL,
                        oem = Build.MANUFACTURER,
                        fingerprint = Build.FINGERPRINT,
                        sdkInt = Build.VERSION.SDK_INT,
                    )
                BundleWriter(origin).write(copy.openHelper.readableDatabase, settings, out, progress)
            }
        }

        /** Writes the CSV export to [out] and closes it. Throws [IOException] when the file cannot be written. */
        public suspend fun exportCsv(
            out: OutputStream,
            progress: (done: Long, total: Long) -> Unit = { _, _ -> },
        ): Unit = withCopy { copy -> CsvExport().write(copy.openHelper.readableDatabase, out, progress) }

        /**
         * Imports the bundle in [input] as an archive and closes [input]. Nothing of a refused
         * bundle is kept, and nothing of an accepted one enters this phone's own tables.
         */
        public suspend fun import(
            input: InputStream,
            progress: (rows: Long) -> Unit = {},
        ): ImportResult =
            withContext(dispatchers.io) {
                busy.withLock {
                    val staging = archiveRoot.resolve(STAGING + UUID.randomUUID())
                    try {
                        staging.mkdirs()
                        val bundle = BundleImporter(context).load(input, staging.resolve(DATABASE), progress)
                        // The checks after the last row report no progress: a cancel during them stops here.
                        ensureActive()
                        val id = bundle.manifest.bundleId
                        when {
                            // The id becomes a folder's name, and it came from the file.
                            !ID.matches(id) -> ImportResult.Refused(ImportProblem.DAMAGED)

                            archiveRoot.resolve(id).exists() -> ImportResult.Refused(ImportProblem.ALREADY_IMPORTED)

                            else -> place(staging, bundle)
                        }
                    } catch (e: BundleRejectedException) {
                        // The problem only: a parser's message can quote the other phone's file.
                        Log.w(TAG, "bundle refused: ${e.problem}")
                        ImportResult.Refused(ImportProblem.valueOf(e.problem.name))
                    } catch (e: IOException) {
                        Log.w(TAG, "archive not written", e)
                        ImportResult.Refused(ImportProblem.FAILED)
                    } catch (e: SQLException) {
                        Log.w(TAG, "archive not read back", e)
                        ImportResult.Refused(ImportProblem.FAILED)
                    } finally {
                        staging.deleteRecursively()
                    }
                }
            }

        /**
         * Moves the loaded bundle from [staging] to its own folder, whole or not at all. The rename is
         * the commit: a cancel before it keeps nothing, one after it keeps the archive, which the list
         * shows (audit CONC-009).
         */
        private suspend fun place(
            staging: File,
            bundle: ImportedBundle,
        ): ImportResult {
            val home = archiveRoot.resolve(bundle.manifest.bundleId)
            staging.resolve(MANIFEST).writeText(bundle.manifest.toJson())
            staging.resolve(SUMMARY).writeText(summary(bundle.database).toString())
            currentCoroutineContext().ensureActive()
            if (!staging.renameTo(home)) return ImportResult.Refused(ImportProblem.FAILED)
            list()
            return archive(home)?.let { ImportResult.Imported(it, bundle.settings) } ?: ImportResult.Refused(ImportProblem.FAILED)
        }

        /** Removes one archive for good. */
        public suspend fun deleteArchive(id: String): Unit =
            withContext(dispatchers.io) {
                busy.withLock {
                    if (ID.matches(id)) archiveRoot.resolve(id).deleteRecursively()
                    list()
                }
            }

        /** [id]'s archive opened as a database of its own, for reading; the caller closes it. Null when it is gone. */
        internal suspend fun open(id: String): CoulombDatabase? =
            withContext(dispatchers.io) {
                val file = archiveRoot.resolve(id).resolve(DATABASE)
                if (!ID.matches(id) || !file.exists()) return@withContext null
                CoulombDatabase.configured(Room.databaseBuilder(context, CoulombDatabase::class.java, file.path))
            }

        /**
         * "Delete all data" (SPEC 14.5, ADR-0062 §14): every row Coulomb recorded, the snapshot
         * files, the archives and anything half-written. Settings and granted permissions stay.
         * The caller stops the collector first and restarts the process after, because the
         * collector holds this boot, the calibration and the capacity in memory.
         */
        public suspend fun deleteAll(): Unit =
            withContext(dispatchers.io) {
                busy.withLock {
                    database.clearAllTables()
                    // Where the collector writes them (`FileBlobStore(context.filesDir)`, folder
                    // "snapshots"; excluded from backup in data_extraction_rules.xml). Audit DATA-001.
                    context.filesDir.resolve(SNAPSHOTS).deleteRecursively()
                    // The raw export's scratch folder (DataViewModel), anything half-written there.
                    context.cacheDir.resolve(RAW_EXPORT_SCRATCH).deleteRecursively()
                    archiveRoot.deleteRecursively()
                    scratch.deleteRecursively()
                    list()
                }
            }

        /**
         * Runs [block] on a copy of the database. The copy needs about the database's size in the
         * cache directory; a phone without that room, or a stored row the export cannot read, ends
         * the export as an [IOException] like any other failed write.
         */
        private suspend fun withCopy(block: (CoulombDatabase) -> Unit): Unit =
            withContext(dispatchers.io) {
                busy.withLock {
                    scratch.deleteRecursively()
                    scratch.mkdirs()
                    val file = scratch.resolve(UUID.randomUUID().toString() + ".db")
                    try {
                        DatabaseCopy.copy(database, file)
                        val copy = CoulombDatabase.configured(Room.databaseBuilder(context, CoulombDatabase::class.java, file.path))
                        try {
                            block(copy)
                        } finally {
                            copy.close()
                        }
                    } catch (e: SQLException) {
                        throw IOException("The database could not be copied or read", e)
                    } catch (e: CorruptChunkException) {
                        throw IOException("A stored history chunk could not be read", e)
                    } finally {
                        scratch.deleteRecursively()
                    }
                }
            }

        private fun archive(folder: File): Archive? {
            val manifest = folder.resolve(MANIFEST)
            val data = folder.resolve(DATABASE)
            if (!ID.matches(folder.name) || !manifest.isFile || !data.isFile) return null
            return try {
                val read = BundleManifest.parse(manifest.readText())
                val size = folder.listFiles()?.sumOf { it.length() } ?: 0L
                val counts = counts(folder.resolve(SUMMARY))
                Archive(
                    id = folder.name,
                    model = read.model.take(MAX_MODEL),
                    firstWallMs = read.firstWallMs,
                    lastWallMs = read.lastWallMs,
                    exportedAtWallMs = read.exportedAtWallMs,
                    sizeBytes = size,
                    discharges = counts?.optLong(DISCHARGES),
                    charges = counts?.optLong(CHARGES),
                )
            } catch (_: BundleRejectedException) {
                null
            }
        }

        /** The sessions of a loaded archive by kind, counted once so that listing it never opens its database. */
        private fun summary(database: File): JSONObject {
            val kinds = CodedColumns.names("session", "kind").orEmpty()

            fun count(
                db: SQLiteDatabase,
                kind: SessionKind,
            ): Long =
                db.rawQuery("SELECT COUNT(*) FROM session WHERE kind = ?", arrayOf(kinds.indexOf(kind.name).toString())).use { rows ->
                    if (rows.moveToFirst()) rows.getLong(0) else 0L
                }
            return SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                JSONObject().put(DISCHARGES, count(db, SessionKind.DISCHARGE)).put(CHARGES, count(db, SessionKind.CHARGE))
            }
        }

        private fun counts(file: File): JSONObject? =
            try {
                file.takeIf { it.isFile }?.let { JSONObject(it.readText()) }
            } catch (_: JSONException) {
                null
            }

        private fun version(): Pair<String, Long> {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return info.versionName.orEmpty() to info.longVersionCode
        }

        private companion object {
            const val ARCHIVES = "archives"
            const val SNAPSHOTS = "snapshots"
            const val RAW_EXPORT_SCRATCH = "raw-export"
            const val SCRATCH = "transfer"
            const val TAG = "DataTransfer"
            const val STAGING = ".staging-"
            const val DATABASE = "coulomb.db"
            const val MANIFEST = "manifest.json"
            const val SUMMARY = "summary.json"
            const val DISCHARGES = "discharges"
            const val CHARGES = "charges"

            /** A model name is shown as plain text; one from a file is cut to a line. */
            const val MAX_MODEL = 64
            val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        }
    }

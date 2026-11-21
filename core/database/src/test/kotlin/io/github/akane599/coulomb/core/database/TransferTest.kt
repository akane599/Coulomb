// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.transfer.BundleFormat
import io.github.akane599.coulomb.core.database.transfer.BundleImporter
import io.github.akane599.coulomb.core.database.transfer.BundleOrigin
import io.github.akane599.coulomb.core.database.transfer.BundleProblem
import io.github.akane599.coulomb.core.database.transfer.BundleRejectedException
import io.github.akane599.coulomb.core.database.transfer.BundleWriter
import io.github.akane599.coulomb.core.database.transfer.CodedColumns
import io.github.akane599.coulomb.core.database.transfer.CsvExport
import io.github.akane599.coulomb.core.database.transfer.DatabaseCopy
import io.github.akane599.coulomb.core.database.transfer.TableDump
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The exports and the import (SPEC 14.5, ADR-0062), on a database with a row of each awkward kind. */
@RunWith(AndroidJUnit4::class)
class TransferTest : DatabaseTest() {
    @get:Rule
    val files = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val origin =
        BundleOrigin(
            "3f6c1f0e-0000-4000-8000-000000000001",
            1_800_000_000_000,
            "Europe/Istanbul",
            "1.0.0",
            10,
            "Lab phone",
            "Lab",
            "lab/phone:17/A/1",
            37,
        )

    /** Samples, a snapshot with counters, a session, history with a tag, and text that needs escaping. */
    private suspend fun fill() {
        sql(
            "INSERT INTO boot (bootSeq, rawBootCount, fingerprint, firstWallMs, firstElapsedNs, historyOffsetMs, imported) " +
                "VALUES (1, 7, 'lab', 1000, 5, NULL, 0)",
        )
        db.sampleDao().insert(sample(1, 30 * NS_PER_S))
        db.sampleDao().insert(sample(1, 60 * NS_PER_S, currentUa = null))
        val snapshotId = db.snapshotDao().insert(snapshot(elapsedS = 10), counters())
        db.sessionDao().insert(session(kind = SessionKind.CHARGE, startSnapshotId = snapshotId))
        db.historyDao().insertRead(
            listOf(
                event(1, 100, tag = "=cmd|' /C calc'!A0", type = HistoryKind.WAKE_LOCK, change = HistoryChange.START),
                event(
                    1,
                    200,
                    tag = "line one\nline \"two\", with a comma   and ğüş",
                    type = HistoryKind.WAKE_LOCK,
                    change = HistoryChange.STOP,
                ),
                event(1, 300),
            ),
            emptyList(),
        )
    }

    /** A copy of the test's database, opened as the export reads it. */
    private fun copy(): CoulombDatabase {
        val file = File(files.root, "copy-${files.root.list()?.size}.db")
        DatabaseCopy.copy(db, file)
        return CoulombDatabase.configured(Room.databaseBuilder(context, CoulombDatabase::class.java, file.path))
    }

    private fun bundle(): ByteArray {
        val source = copy()
        val out = ByteArrayOutputStream()
        BundleWriter(origin).write(source.openHelper.readableDatabase, """{"show_system_apps":true}""", out)
        source.close()
        return out.toByteArray()
    }

    /** Every table's rows as column name to value; a migrated table may hold its columns in another order. */
    private fun rows(database: CoulombDatabase): Map<String, List<Map<String, Any?>>> {
        val dump = TableDump(database.openHelper.readableDatabase)
        return dump.tables().associate { table ->
            val rows = ArrayList<Map<String, Any?>>()
            dump.rows(table) { row ->
                rows +=
                    table.columns.indices.associate { table.columns[it].name to ((row[it] as? ByteArray)?.toList() ?: row[it]) }
            }
            table.name to rows
        }
    }

    private fun import(bytes: ByteArray) =
        BundleImporter(context).load(ByteArrayInputStream(bytes), File(files.root, "archive-${files.root.list()?.size}.db"))

    private fun problem(bytes: ByteArray): BundleProblem? =
        runCatching {
            import(bytes)
        }.exceptionOrNull().let { (it as? BundleRejectedException)?.problem }

    /** The zip in [bytes] with [change] applied to its entries, in order. */
    private fun edited(
        bytes: ByteArray,
        change: (LinkedHashMap<String, ByteArray>) -> Unit,
    ): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        change(entries)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun everyTableOfTheSchemaIsExported() {
        val schema =
            context.assets.open("${CoulombDatabase::class.java.name}/${db.openHelper.readableDatabase.version}.json").use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        val entities = JSONObject(schema).getJSONObject("database").getJSONArray("entities")
        val names = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        assertThat(TableDump(db.openHelper.readableDatabase).tables().map { it.name }).containsExactlyElementsIn(names)
    }

    @Test
    fun everyCodedColumnIsAnIntegerColumnOfTheSchema() {
        val tables = TableDump(db.openHelper.readableDatabase).tables().associateBy { it.name }
        CodedColumns.NAMES.keys.forEach { key ->
            val (table, column) = key.split(".")
            assertThat(
                tables
                    .getValue(table)
                    .columns
                    .single { it.name == column }
                    .type,
            ).isEqualTo("INTEGER")
        }
    }

    @Test
    fun aBundleComesBackRowForRow() =
        runTest {
            fill()
            val before = copy()
            val imported = import(bundle())
            assertThat(imported.manifest.bundleId).isEqualTo(origin.bundleId)
            assertThat(imported.manifest.model).isEqualTo("Lab phone")
            assertThat(imported.manifest.schemaVersion).isEqualTo(db.openHelper.readableDatabase.version)
            assertThat(imported.settings).isEqualTo("""{"show_system_apps":true}""")
            val archive = CoulombDatabase.configured(Room.databaseBuilder(context, CoulombDatabase::class.java, imported.database.path))
            // The two differences are meant: an archive's boots are marked imported, and no row points at a snapshot file.
            val expected =
                rows(before).mapValues { (table, rows) ->
                    when (table) {
                        "boot" -> rows.map { it + ("imported" to 1L) }
                        "snapshot" -> rows.map { it + ("blobPath" to null) }
                        else -> rows
                    }
                }
            assertThat(expected.getValue("battery_sample")).hasSize(2)
            assertThat(rows(archive)).isEqualTo(expected)
            // The history reads back through the DAO: the chunks and their tags arrived whole.
            assertThat(archive.historyDao().events(1, 0, 2, 0).map { it.tag })
                .containsExactly("=cmd|' /C calc'!A0", "line one\nline \"two\", with a comma   and ğüş", null)
                .inOrder()
            before.close()
            archive.close()
        }

    @Test
    fun aBundleThatDidNotFinishIsRefused() =
        runTest {
            fill()
            assertThat(problem(edited(bundle()) { it.remove(BundleFormat.END) })).isEqualTo(BundleProblem.INCOMPLETE)
        }

    @Test
    fun aBundleThatIsNotWhatCoulombWritesIsRefusedWhole() =
        runTest {
            fill()
            val good = bundle()
            val sessions = BundleFormat.TABLES + "session" + BundleFormat.ROWS
            // A row fewer than the closing entry states.
            assertThat(
                problem(
                    edited(good) {
                        it[BundleFormat.TABLES + "battery_sample" + BundleFormat.ROWS] = ByteArray(0)
                    },
                ),
            ).isEqualTo(BundleProblem.DAMAGED)
            // A code outside its list, which a screen would meet as a crash.
            assertThat(problem(edited(good) { it[sessions] = String(it.getValue(sessions)).replaceFirst(",1,", ",99,").toByteArray() }))
                .isEqualTo(BundleProblem.DAMAGED)
            // A value of another type than its column.
            assertThat(problem(edited(good) { it[sessions] = String(it.getValue(sessions)).replaceFirst("[", "[\"x\",").toByteArray() }))
                .isEqualTo(BundleProblem.DAMAGED)
            // An entry Coulomb does not write, and a table that is not the schema's.
            assertThat(problem(edited(good) { it["tables/evil.ndjson"] = ByteArray(0) })).isEqualTo(BundleProblem.DAMAGED)
            assertThat(
                problem(
                    edited(good) {
                        it[BundleFormat.MANIFEST] =
                            String(it.getValue(BundleFormat.MANIFEST)).replace("\"uid_meta\"", "\"uid_meta2\"").toByteArray()
                    },
                ),
            ).isEqualTo(BundleProblem.DAMAGED)
            // A sample of a boot that is not there.
            val boots = BundleFormat.TABLES + "boot" + BundleFormat.ROWS
            assertThat(
                problem(
                    edited(good) {
                        it[boots] = ByteArray(0)
                        it[BundleFormat.END] = String(it.getValue(BundleFormat.END)).replace("\"boot\":1", "\"boot\":0").toByteArray()
                    },
                ),
            ).isEqualTo(BundleProblem.DAMAGED)
            // Nothing of a refused bundle stays.
            assertThat(files.root.list()!!.filter { it.startsWith("archive-") }).isEmpty()
        }

    @Test
    fun aNewerOrForeignFileIsToldApart() =
        runTest {
            fill()
            val good = bundle()
            val manifest = { change: (String) -> String ->
                edited(good) { it[BundleFormat.MANIFEST] = change(String(it.getValue(BundleFormat.MANIFEST))).toByteArray() }
            }
            assertThat(
                problem(
                    manifest {
                        it.replace("\"version\": ${db.openHelper.readableDatabase.version}", "\"version\": 999")
                    },
                ),
            ).isEqualTo(BundleProblem.NEWER)
            assertThat(problem(manifest { it.replace("\"major\": 1", "\"major\": 2") })).isEqualTo(BundleProblem.NEWER)
            assertThat(
                problem(
                    manifest {
                        it.replace("\"version\": ${db.openHelper.readableDatabase.version}", "\"version\": 3")
                    },
                ),
            ).isEqualTo(BundleProblem.TOO_OLD)
            assertThat(problem(manifest { it.replace("coulomb-bundle", "something-else") })).isEqualTo(BundleProblem.NOT_A_BUNDLE)
            assertThat(problem("not a zip at all".toByteArray())).isEqualTo(BundleProblem.NOT_A_BUNDLE)
        }

    @Test
    fun theCsvIsWrittenForPeople() =
        runTest {
            fill()
            val source = copy()
            val out = ByteArrayOutputStream()
            CsvExport().write(source.openHelper.readableDatabase, out)
            source.close()
            val csv = LinkedHashMap<String, String>()
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    csv[entry.name] = String(zip.readBytes())
                }
            }
            assertThat(csv.keys).containsAtLeast("README.txt", "session.csv", "history_event.csv", "battery_sample.csv")
            assertThat(csv.keys).doesNotContain("history_chunk.csv")
            // A byte-order mark, CRLF, and a coded column by name.
            assertThat(csv.getValue("session.csv")).startsWith("\uFEFFid,kind,")
            assertThat(csv.getValue("session.csv").lines()[1]).startsWith("1,CHARGE,")
            assertThat(csv.getValue("session.csv")).contains("\r\n")
            // A time is followed by itself written out; a missing value is an empty field.
            val samples = csv.getValue("battery_sample.csv").lines()
            assertThat(samples[0]).contains("wallMs,wallMsUtc,")
            assertThat(samples[1]).contains("30000,1970-01-01 00:00:30.000,")
            assertThat(samples[2]).contains(",80,4000000,,,4100,")
            assertThat(csv.getValue("snapshot.csv")).contains("S5=ok")
            // A tag that begins like a formula is made text; quotes, commas and line ends are quoted.
            val history = csv.getValue("history_event.csv")
            assertThat(history).contains(",WAKE_LOCK,START,0,10123,'=cmd|' /C calc'!A0,")
            assertThat(history).contains("\"line one\nline \"\"two\"\", with a comma   and ğüş\"")
            assertThat(history).contains(",BATTERY_LEVEL,SET,0,,,80")
        }
}

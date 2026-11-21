// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.database.entity.HistoryChunkCodec
import io.github.akane599.coulomb.core.database.entity.HistoryChunkEntity
import io.github.akane599.coulomb.core.database.entity.PackedEvent
import io.github.akane599.coulomb.core.database.transfer.BundleFormat
import io.github.akane599.coulomb.core.database.transfer.BundleImporter
import io.github.akane599.coulomb.core.database.transfer.BundleOrigin
import io.github.akane599.coulomb.core.database.transfer.BundleProblem
import io.github.akane599.coulomb.core.database.transfer.BundleRejectedException
import io.github.akane599.coulomb.core.database.transfer.BundleWriter
import io.github.akane599.coulomb.core.database.transfer.CodedColumns
import io.github.akane599.coulomb.core.database.transfer.CsvExport
import io.github.akane599.coulomb.core.database.transfer.DatabaseCopy
import io.github.akane599.coulomb.core.database.transfer.ImportLimits
import io.github.akane599.coulomb.core.database.transfer.NamedColumns
import io.github.akane599.coulomb.core.database.transfer.TableDump
import io.github.akane599.coulomb.core.database.transfer.ValueChecks
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Modifier
import java.net.URI
import java.util.jar.JarFile
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

    /** [bytes] with [change] applied to every row of [table]; a row's values are in the order of the column names given. */
    private fun rowsEdited(
        bytes: ByteArray,
        table: String,
        change: (row: JSONArray, columns: List<String>) -> Unit,
    ): ByteArray =
        edited(bytes) { entries ->
            val tables = JSONObject(String(entries.getValue(BundleFormat.MANIFEST))).getJSONArray("tables")
            val columns =
                (0 until tables.length())
                    .map(tables::getJSONObject)
                    .single { it.getString("name") == table }
                    .getJSONArray("columns")
                    .let { all -> (0 until all.length()).map { all.getJSONObject(it).getString("name") } }
            val name = BundleFormat.TABLES + table + BundleFormat.ROWS
            entries[name] =
                String(entries.getValue(name))
                    .lines()
                    .filter { it.isNotEmpty() }
                    .joinToString("") { line -> JSONArray(line).also { change(it, columns) }.toString() + "\n" }
                    .toByteArray()
        }

    @Test
    fun aFileBuiltToBreakTheReaderIsRefused() =
        runTest {
            fill()
            val good = bundle()
            // A few kilobytes of brackets would overflow a recursive parser's stack.
            val deep = "[".repeat(20_000)
            val samples = BundleFormat.TABLES + "battery_sample" + BundleFormat.ROWS
            assertThat(problem(edited(good) { it[BundleFormat.MANIFEST] = "{\"format\":$deep".toByteArray() }))
                .isEqualTo(BundleProblem.NOT_A_BUNDLE)
            assertThat(problem(edited(good) { it[samples] = "$deep\n".toByteArray() })).isEqualTo(BundleProblem.DAMAGED)
            assertThat(problem(edited(good) { it[BundleFormat.END] = "{\"complete\":true,\"rows\":$deep".toByteArray() }))
                .isEqualTo(BundleProblem.DAMAGED)
            // What a lenient reader lets through: text after a row, a comment, a value in single quotes.
            val row = { change: (String) -> String -> edited(good) { it[samples] = change(String(it.getValue(samples))).toByteArray() } }
            assertThat(problem(row { it.replaceFirst("]", "] x") })).isEqualTo(BundleProblem.DAMAGED)
            assertThat(problem(row { it.replaceFirst("[", "[/* */") })).isEqualTo(BundleProblem.DAMAGED)
            assertThat(problem(row { it.replaceFirst("[", "['x',") })).isEqualTo(BundleProblem.DAMAGED)
            // A file cut short did not finish.
            assertThat(problem(good.copyOf(good.size / 2))).isEqualTo(BundleProblem.INCOMPLETE)
            assertThat(files.root.list()!!.filter { it.startsWith("archive-") }).isEmpty()
        }

    @Test
    fun anImportStopsBeforeItFillsThePhone() =
        runTest {
            fill()
            val good = bundle()
            val with = { limits: ImportLimits ->
                runCatching { BundleImporter(context, limits).load(ByteArrayInputStream(good), File(files.root, "archive-limited.db")) }
                    .exceptionOrNull()
                    .let { (it as? BundleRejectedException)?.problem }
            }
            // The rows' inflated size is counted, whatever the file's own size.
            assertThat(with(ImportLimits(maxInflated = 1_000))).isEqualTo(BundleProblem.TOO_LARGE)
            assertThat(with(ImportLimits(free = { 0L }))).isEqualTo(BundleProblem.FAILED)
            assertThat(files.root.list()!!.filter { it.startsWith("archive-") }).isEmpty()
        }

    @Test
    fun aValueNoReaderCouldTakeIsRefused() =
        runTest {
            fill()
            sql(
                "INSERT INTO capability (id, state, tier, reason, fixAction, probedAtWall, fingerprint, detailJson) " +
                    "VALUES ('s5', 'AVAILABLE', 'T1_DURABLE_GRANTS', NULL, NULL, 1, 'lab', '{}')",
            )
            sql(
                "INSERT INTO history_gap " +
                    "(startMonotonicMs, endMonotonicMs, startBootSeq, startElapsedNs, endBootSeq, endElapsedNs, reason) " +
                    "VALUES (1, 2, 1, 1, 1, 2, 'OVERFLOW')",
            )
            val good = bundle()
            import(good)
            val set = { table: String, column: String, value: Any ->
                problem(rowsEdited(good, table) { row, columns -> row.put(columns.indexOf(column), value) })
            }
            // An enum stored by name, a column with a code of its own, and an id without a foreign key.
            assertThat(set("capability", "state", "BOGUS")).isEqualTo(BundleProblem.DAMAGED)
            assertThat(set("snapshot", "privilegeMode", 77)).isEqualTo(BundleProblem.DAMAGED)
            assertThat(set("history_gap", "endBootSeq", 99)).isEqualTo(BundleProblem.DAMAGED)
            // A history chunk: bytes that are no chunk, a kind this build does not know, a boot that is not there.
            val chunk = { boot: Int, type: Int ->
                val packed = PackedEvent(boot, 100, type, changeCode = 0, baseline = false, uid = null, tagId = null, value = 1)
                Base64.encodeToString(HistoryChunkCodec.encode(listOf(packed)), Base64.NO_WRAP)
            }
            val chunks = { data: String ->
                problem(
                    rowsEdited(good, "history_chunk") { row, columns ->
                        row.put(columns.indexOf("count"), 1)
                        row.put(columns.indexOf("data"), data)
                    },
                )
            }
            assertThat(chunks(chunk(1, 0))).isNull()
            assertThat(chunks("AAAA")).isEqualTo(BundleProblem.DAMAGED)
            assertThat(chunks(chunk(1, 9_999))).isEqualTo(BundleProblem.DAMAGED)
            assertThat(chunks(chunk(9, 0))).isEqualTo(BundleProblem.DAMAGED)
        }

    @Test
    fun anArchiveIsInTheStoredForm() =
        runTest {
            fill()
            val imported = import(bundle())
            SQLiteDatabase.openDatabase(imported.database.path, null, SQLiteDatabase.OPEN_READONLY).use { archive ->
                archive.rawQuery("SELECT sql FROM sqlite_master WHERE name = 'window_stat'", null).use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getString(0)).contains("WITHOUT ROWID")
                }
            }
        }

    @Test
    fun aLaterMinorMayAddAnEntryAndAListOfCodesMayOnlyGrow() =
        runTest {
            fill()
            val good = bundle()
            val manifest = { change: (JSONObject) -> Unit ->
                edited(good) {
                    it[BundleFormat.MANIFEST] =
                        JSONObject(String(it.getValue(BundleFormat.MANIFEST))).also(change).toString().toByteArray()
                    val end = it.remove(BundleFormat.END)!!
                    it["extra.json"] = "{}".toByteArray()
                    it[BundleFormat.END] = end
                }
            }
            assertThat(problem(manifest { })).isEqualTo(BundleProblem.DAMAGED)
            import(manifest { it.getJSONObject("formatVersion").put("minor", BundleFormat.MINOR + 1) })
            val codes = { change: (JSONArray) -> Unit ->
                edited(good) {
                    val whole = JSONObject(String(it.getValue(BundleFormat.MANIFEST)))
                    change(whole.getJSONObject("codes").getJSONArray("session.kind"))
                    it[BundleFormat.MANIFEST] = whole.toString().toByteArray()
                }
            }
            import(codes { it.put("A_KIND_OF_A_LATER_BUILD") })
            assertThat(problem(codes { it.put(0, "CHARGE") })).isEqualTo(BundleProblem.DAMAGED)
        }

    /**
     * Room's converters and Coulomb's own throw on a name or code they do not know, so every enum
     * column must be in one of the three lists the import checks. The entity classes are found on
     * the class path and matched to their tables by their columns.
     */
    @Test
    fun everyEnumColumnOfTheEntitiesIsHeldToWhatItMayStore() {
        val tables =
            TableDump(db.openHelper.readableDatabase).tables().associate { table ->
                table.columns.map { it.name }.toSet() to
                    table.name
            }
        val held = HashSet<String>()
        val matched = HashSet<String>()
        for (type in entityClasses()) {
            val fields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            val table = tables[fields.map { it.name }.toSet()] ?: continue
            matched += table
            for (field in fields.filter { it.type.isEnum }) {
                val key = "$table.${field.name}"
                val names = field.type.enumConstants!!.map { (it as Enum<*>).name }
                val listed =
                    NamedColumns.NAMES[key] == names ||
                        CodedColumns.NAMES[key]?.toSet() == names.toSet() ||
                        key in ValueChecks.SELF_CODED
                assertWithMessage(key).that(listed).isTrue()
                held += key
            }
        }
        // The two tables of S4's energies hold an embedded row of numbers: their classes' fields are not their columns.
        assertThat(tables.values - matched).containsExactly("uid_component_power", "window_component")
        // And no list names a column that is not there: a coverage is a class of its own, so it is held by name.
        assertThat(held).containsAtLeastElementsIn(NamedColumns.NAMES.keys + CodedColumns.NAMES.keys)
        val columns =
            TableDump(db.openHelper.readableDatabase).tables().flatMap { table ->
                table.columns.map { "${table.name}.${it.name}" }
            }
        assertThat(columns).containsAtLeastElementsIn(ValueChecks.SELF_CODED.keys)
    }

    /** Every class of the entity package, from the directory or the jar the entities were loaded from. */
    private fun entityClasses(): List<Class<*>> {
        val name = HistoryChunkEntity::class.java.packageName
        val folder = name.replace('.', '/')
        val one = ClassLoader.getSystemResource("$folder/${HistoryChunkEntity::class.java.simpleName}.class")
        val files =
            if (one.protocol == "file") {
                File(one.toURI()).parentFile!!.list()!!.toList()
            } else {
                val jar = File(URI(one.path.substringBefore("!")))
                JarFile(jar).use { it.entries().toList().map { entry -> entry.name } }.filter { it.startsWith("$folder/") }
            }
        return files
            .filter { it.endsWith(".class") }
            .map { Class.forName(name + "." + it.substringAfterLast('/').removeSuffix(".class")) }
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.transfer

import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.akane599.coulomb.core.database.entity.HistoryChunkCodec
import io.github.akane599.coulomb.core.database.entity.HistoryCodes
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The CSV export (SPEC 14.5, ADR-0062 §7): a zip with one file per table, written for people and
 * spreadsheets. The same in every language: comma, decimal point, UTF-8 with a byte-order mark,
 * CRLF and RFC 4180 quoting.
 *
 * - NULL is an empty field; an empty string is `""`.
 * - A coded column is written by name ([CodedColumns]); `snapshot.sourceStatus` in words.
 * - A wall-clock column in epoch milliseconds is followed by `<name>Utc`, and `epochDay` by
 *   `epochDayDate`.
 * - Text that a spreadsheet would run as a formula gets a leading apostrophe: tags and labels come
 *   from other apps.
 * - The stored history is written as its events (`history_event.csv`), not as packed chunks.
 * - A file is split at [MAX_ROWS] rows, which a spreadsheet's sheet cannot pass.
 */
public class CsvExport {
    /** Writes [source] to [out] and closes it. [progress] gets the rows written so far and the total. */
    public fun write(
        source: SupportSQLiteDatabase,
        out: OutputStream,
        progress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val dump = TableDump(source)
        val tables = dump.tables()
        val total = tables.sumOf(dump::count)
        var done = 0L
        val tick = { if (++done % PROGRESS_EVERY == 0L) progress(done, total) }
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(README.toByteArray())
            zip.closeEntry()
            for (table in tables) {
                if (table.name == HISTORY_CHUNKS) history(dump, table, zip, tick) else table(dump, table, zip, tick)
            }
            progress(total, total)
        }
    }

    private fun table(
        dump: TableDump,
        table: DumpTable,
        zip: ZipOutputStream,
        tick: () -> Unit,
    ) {
        val writers = table.columns.map { cell(table.name, it) }
        val header = table.columns.flatMap { listOfNotNull(it.name, extraName(it.name)) }
        val file = Sheet(zip, table.name, header)
        dump.rows(table) { row ->
            file.row(row.indices.flatMap { index -> writers[index](row[index]) })
            tick()
        }
        file.close()
    }

    /** One column's cells for a value: the value itself, and for a time its readable form beside it. */
    private fun cell(
        table: String,
        column: DumpColumn,
    ): (Any?) -> List<String?> {
        val names = CodedColumns.names(table, column.name)
        return when {
            names != null -> { value -> listOf((value as? Long)?.let { names.getOrNull(it.toInt()) ?: it.toString() }) }
            table == "snapshot" && column.name == "sourceStatus" -> { value -> listOf((value as? Long)?.let(CodedColumns::sourceStatus)) }
            isWallClock(column.name) -> { value -> listOf(plain(value), (value as? Long)?.let(::utc)) }
            column.name == EPOCH_DAY -> { value -> listOf(plain(value), (value as? Long)?.let { LocalDate.ofEpochDay(it).toString() }) }
            else -> { value -> listOf(plain(value)) }
        }
    }

    private fun extraName(column: String): String? =
        when {
            isWallClock(column) -> column + "Utc"
            column == EPOCH_DAY -> column + "Date"
            else -> null
        }

    private fun isWallClock(column: String): Boolean = column == "wallMs" || column.endsWith("Wall") || column.endsWith("WallMs")

    private fun utc(wallMs: Long): String = UTC.format(Instant.ofEpochMilli(wallMs))

    private fun plain(value: Any?): String? =
        when (value) {
            null -> null
            is ByteArray -> value.joinToString("") { "%02x".format(it) }
            is String -> guarded(value)
            else -> value.toString()
        }

    /** A cell that begins like a formula is text, and is made to read as text. */
    private fun guarded(text: String): String = if (text.isNotEmpty() && text[0] in FORMULA_STARTS) "'$text" else text

    /** The packed history as one row per event, with each tag's text. */
    private fun history(
        dump: TableDump,
        chunks: DumpTable,
        zip: ZipOutputStream,
        tick: () -> Unit,
    ) {
        val tags = HashMap<Long, String>()
        dump.tables().firstOrNull { it.name == "history_tag" }?.let { table ->
            val id = table.columns.indexOfFirst { it.name == "id" }
            val tag = table.columns.indexOfFirst { it.name == "tag" }
            dump.rows(table) { row -> tags[row[id] as Long] = row[tag] as String }
        }
        val firstId = chunks.columns.indexOfFirst { it.name == "firstId" }
        val count = chunks.columns.indexOfFirst { it.name == "count" }
        val data = chunks.columns.indexOfFirst { it.name == "data" }
        val file = Sheet(zip, "history_event", HISTORY_HEADER)
        dump.rows(chunks) { row ->
            val events = HistoryChunkCodec.decode((row[count] as Long).toInt(), row[data] as ByteArray)
            events.forEachIndexed { index, event ->
                file.row(
                    listOf(
                        ((row[firstId] as Long) + index).toString(),
                        event.bootSeq.toString(),
                        event.elapsedMs.toString(),
                        HistoryCodes.kind(event.typeCode).name,
                        HistoryCodes.change(event.changeCode).name,
                        if (event.baseline) "1" else "0",
                        event.uid?.toString(),
                        event.tagId?.let { tags[it] ?: it.toString() }?.let(::guarded),
                        event.value?.toString(),
                    ),
                )
            }
            tick()
        }
        file.close()
    }

    /** One table's file or files in the zip: a new one, with the header again, every [MAX_ROWS] rows. */
    private class Sheet(
        private val zip: ZipOutputStream,
        private val name: String,
        private val header: List<String>,
    ) {
        private var rows = 0
        private var part = 0
        private val line = StringBuilder()

        init {
            open()
        }

        fun row(cells: List<String?>) {
            if (rows == MAX_ROWS) {
                zip.closeEntry()
                open()
            }
            write(cells)
            rows++
        }

        fun close() = zip.closeEntry()

        private fun open() {
            part++
            rows = 0
            zip.putNextEntry(ZipEntry(if (part == 1) "$name.csv" else "$name-$part.csv"))
            zip.write(BOM)
            write(header)
        }

        private fun write(cells: List<String?>) {
            line.setLength(0)
            cells.forEachIndexed { index, cell ->
                if (index > 0) line.append(',')
                if (cell != null) quoted(cell)
            }
            line.append("\r\n")
            zip.write(line.toString().toByteArray())
        }

        private fun quoted(cell: String) {
            val needs =
                cell.isEmpty() || cell.any { it == ',' || it == '"' || it == '\r' || it == '\n' } || cell.first() == ' ' ||
                    cell.last() == ' '
            if (!needs) {
                line.append(cell)
                return
            }
            line.append('"')
            for (c in cell) if (c == '"') line.append("\"\"") else line.append(c)
            line.append('"')
        }
    }

    private companion object {
        const val MAX_ROWS = 1_000_000
        const val PROGRESS_EVERY = 5_000L
        const val HISTORY_CHUNKS = "history_chunk"
        const val EPOCH_DAY = "epochDay"
        val HISTORY_HEADER = listOf("id", "bootSeq", "elapsedMs", "kind", "change", "baseline", "uid", "tag", "value")
        val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')
        val UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
        val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        val README =
            """
            Coulomb CSV export
            ==================

            One file per table of Coulomb's database, for reading in a spreadsheet or a script.
            UTF-8 with a byte-order mark, comma separated, decimal point, CRLF line ends. In a
            spreadsheet set to another language, open the files with "From Text/CSV" and choose
            comma and point there.

            - An empty field is "no value"; an empty text is written as "".
            - A column whose name ends in Wall or WallMs is a time in milliseconds since 1970 UTC;
              the column after it, ending in Utc, is the same time written out.
            - Times ending in Ns are nanoseconds since the phone started. A spreadsheet keeps 15
              digits, so sort and compare them as text, or use the JSON bundle.
            - Text that begins with =, +, - or @ has an apostrophe put before it, so a spreadsheet
              does not run it as a formula.
            - history_event.csv is the battery history, one row per event.
            - A table of over 1,000,000 rows continues in <table>-2.csv and so on.

            The export holds the names of the apps installed on the phone. The JSON bundle is the
            exact copy; Coulomb imports that one, not this.
            """.trimIndent() + "\n"
    }
}

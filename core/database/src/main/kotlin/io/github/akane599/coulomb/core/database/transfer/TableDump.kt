// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.transfer

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase

/** A column as SQLite declares it: [type] is `INTEGER`, `REAL`, `TEXT` or `BLOB`. */
public data class DumpColumn(
    val name: String,
    val type: String,
    val notNull: Boolean,
)

/** A table with its columns in declared order and its primary key in key order. */
public data class DumpTable(
    val name: String,
    val columns: List<DumpColumn>,
    val primaryKey: List<String>,
)

/**
 * A database read table by table in the form it is stored (SPEC 14.5, ADR-0062): every table
 * SQLite lists, so one added later cannot be forgotten, and every row as the values SQLite holds
 * (`Long`, `Double`, `String`, `ByteArray` or null), in primary key order.
 *
 * Reads only. The export gives it a copy of the database ([DatabaseCopy]), so a long dump is one
 * consistent state and never holds the collector's writer.
 */
public class TableDump(
    private val db: SupportSQLiteDatabase,
) {
    /** The tables that hold data, by name: not SQLite's own and not Room's bookkeeping. */
    public fun tables(): List<DumpTable> {
        val names =
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        return names.filterNot { it in BOOKKEEPING || it.startsWith("sqlite_") }.map(::table)
    }

    /** How many rows [table] has. */
    public fun count(table: DumpTable): Long =
        db.query("SELECT COUNT(*) FROM `${table.name}`").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    /** Every row of [table] in primary key order, to [each]; returns how many there were. */
    public fun rows(
        table: DumpTable,
        each: (Array<Any?>) -> Unit,
    ): Long {
        val columns = table.columns.joinToString { "`${it.name}`" }
        val order = table.primaryKey.ifEmpty { listOf("rowid") }.joinToString { if (it == "rowid") it else "`$it`" }
        var count = 0L
        db.query("SELECT $columns FROM `${table.name}` ORDER BY $order").use { cursor ->
            val width = cursor.columnCount
            while (cursor.moveToNext()) {
                each(Array(width) { cursor.value(it) })
                count++
            }
        }
        return count
    }

    private fun table(name: String): DumpTable {
        val columns = ArrayList<DumpColumn>()
        val key = ArrayList<Pair<Int, String>>()
        db.query("PRAGMA table_info(`$name`)").use { cursor ->
            while (cursor.moveToNext()) {
                val column = cursor.getString(INFO_NAME)
                columns += DumpColumn(column, cursor.getString(INFO_TYPE).uppercase(), cursor.getInt(INFO_NOT_NULL) != 0)
                val position = cursor.getInt(INFO_PK)
                if (position > 0) key += position to column
            }
        }
        return DumpTable(name, columns, key.sortedBy { it.first }.map { it.second })
    }

    private fun Cursor.value(index: Int): Any? =
        when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
            Cursor.FIELD_TYPE_BLOB -> getBlob(index)
            else -> getString(index)
        }

    private companion object {
        val BOOKKEEPING = setOf("room_master_table", "android_metadata")

        // The columns of `PRAGMA table_info`: cid, name, type, notnull, dflt_value, pk.
        const val INFO_NAME = 1
        const val INFO_TYPE = 2
        const val INFO_NOT_NULL = 3
        const val INFO_PK = 5
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.database.Cursor
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.entity.RecordCodes
import io.github.akane599.coulomb.core.database.entity.SourceStatusCodes
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The questions the developer console can ask (SPEC 14.4): a fixed list, each read-only. */
public enum class ConsoleQuery {
    /** The database's size and each table's row count. */
    STORAGE,

    /** The latest snapshots: when, why, which kind, how long. */
    SNAPSHOTS,

    /** What each source gave the latest heavy snapshot. */
    SOURCES,

    /** The capability probe's stored results. */
    CAPABILITIES,

    /** The boots Coulomb has seen. */
    BOOTS,
}

/** One cell of a console table, by what it is, so the screen prints it in the user's formats. */
public sealed interface ConsoleValue {
    public data class Text(
        val text: String,
    ) : ConsoleValue

    public data class Count(
        val count: Long,
    ) : ConsoleValue

    /** A wall-clock time in milliseconds. */
    public data class Time(
        val wallMs: Long,
    ) : ConsoleValue

    /** A stored null. */
    public data object None : ConsoleValue
}

/** A query's answer. [columns] are the database's own names, which are not translated. */
public data class ConsoleTable(
    val columns: List<String>,
    val rows: List<List<ConsoleValue>>,
)

/**
 * SPEC 14.4 "Developer console": read-only typed queries. There is no free SQL: every statement
 * here is a fixed `SELECT` on the reading connection, and a table name comes only from the
 * schema itself (ADR-0064 §7).
 */
public class DeveloperConsole
    @Inject
    internal constructor(
        private val db: CoulombDatabase,
        private val dispatchers: AppDispatchers,
    ) {
        public suspend fun run(query: ConsoleQuery): ConsoleTable =
            withContext(dispatchers.io) {
                when (query) {
                    ConsoleQuery.STORAGE -> storage()
                    ConsoleQuery.SNAPSHOTS -> snapshots()
                    ConsoleQuery.SOURCES -> sources()
                    ConsoleQuery.CAPABILITIES -> capabilities()
                    ConsoleQuery.BOOTS -> boots()
                }
            }

        private fun storage(): ConsoleTable {
            val sqlite = db.openHelper.readableDatabase
            val tables =
                rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name") {
                    it.getString(0)
                }.filterNot { it == "android_metadata" || it == "room_master_table" }
            val bytes = long("PRAGMA page_count") * long("PRAGMA page_size")
            val counts =
                tables.map { table ->
                    val count = sqlite.query("SELECT COUNT(*) FROM `$table`").use { if (it.moveToFirst()) it.getLong(0) else 0 }
                    listOf(ConsoleValue.Text(table), ConsoleValue.Count(count))
                }
            return ConsoleTable(listOf("table", "rows"), listOf(listOf(ConsoleValue.Text(FILE_ROW), ConsoleValue.Count(bytes))) + counts)
        }

        private fun snapshots(): ConsoleTable =
            ConsoleTable(
                listOf("wallMs", "trigger", "kind", "durationMs"),
                rows("SELECT wallMs, `trigger`, kind, durationMs FROM snapshot ORDER BY $NEWEST LIMIT $LATEST") {
                    listOf(
                        ConsoleValue.Time(it.getLong(0)),
                        ConsoleValue.Text(RecordCodes.trigger(it.getInt(1)).name),
                        ConsoleValue.Text(RecordCodes.snapshotKind(it.getInt(2)).name),
                        count(it, DURATION),
                    )
                },
            )

        private fun sources(): ConsoleTable {
            val status =
                rows("SELECT sourceStatus FROM snapshot WHERE kind = ${RecordCodes.SNAPSHOT_HEAVY} ORDER BY $NEWEST LIMIT 1") {
                    it.getLong(0)
                }.firstOrNull()
            val statuses = status?.let(SourceStatusCodes::decode).orEmpty()
            return ConsoleTable(
                listOf("source", "status"),
                statuses.map { (source, state) -> listOf(ConsoleValue.Text(source), ConsoleValue.Text(state)) },
            )
        }

        private fun capabilities(): ConsoleTable =
            ConsoleTable(
                listOf("id", "state", "tier"),
                rows("SELECT id, state, tier FROM capability ORDER BY id") {
                    listOf(ConsoleValue.Text(it.getString(0)), ConsoleValue.Text(it.getString(1)), ConsoleValue.Text(it.getString(2)))
                },
            )

        private fun boots(): ConsoleTable =
            ConsoleTable(
                listOf("bootSeq", "rawBootCount", "firstWallMs"),
                rows("SELECT bootSeq, rawBootCount, firstWallMs FROM boot ORDER BY bootSeq DESC LIMIT $LATEST") {
                    listOf(ConsoleValue.Text(it.getString(0)), count(it, 1), ConsoleValue.Time(it.getLong(2)))
                },
            )

        private fun count(
            cursor: Cursor,
            column: Int,
        ): ConsoleValue = if (cursor.isNull(column)) ConsoleValue.None else ConsoleValue.Count(cursor.getLong(column))

        private fun long(sql: String): Long =
            db.openHelper.readableDatabase
                .query(sql)
                .use { if (it.moveToFirst()) it.getLong(0) else 0 }

        private fun <T> rows(
            sql: String,
            read: (Cursor) -> T,
        ): List<T> =
            db.openHelper.readableDatabase.query(sql).use { cursor ->
                buildList { while (cursor.moveToNext()) add(read(cursor)) }
            }

        private companion object {
            const val LATEST = 30
            const val DURATION = 3
            const val NEWEST = "bootSeq DESC, elapsedNs DESC"

            /** The first row of [ConsoleQuery.STORAGE]: the database file's size in bytes. */
            const val FILE_ROW = "(file, bytes)"
        }
    }

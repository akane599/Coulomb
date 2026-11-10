// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The tables stored `WITHOUT ROWID` (ADR-0051). A rowid table with a composite primary key keeps
 * the key twice, in the table and in SQLite's automatic index; without a rowid the table is that
 * index. A `window_stat` row is little more than its key, and many are kept for good, so the
 * second copy was a tenth of the year's file.
 *
 * Room cannot declare the clause, so it is added here: [rebuild] runs in the migration to version
 * 6 and, through [Callback], after Room has created the tables of a new database. Both forms have
 * the same columns and keys, which is all Room's validation reads of a table with no other index.
 * The exported schema does not show the clause; `SchemaTest` and `MigrationTest` read it from
 * `sqlite_master`.
 *
 * Only a table without a secondary index can take this form. On a table without a rowid SQLite
 * reports the key's columns as part of every other index, and Room then rejects the table: that
 * is why `window_uid` keeps its rowid.
 *
 * The definitions are version 6's, frozen here like every migration's.
 */
internal object WithoutRowid {
    val TABLES: List<String> = listOf("window_stat")

    /** Rebuilds [TABLES] without a rowid, keeping every row. Rows go in in key order. */
    fun rebuild(db: SupportSQLiteDatabase) = windowStat().forEach(db::execSQL)

    /** A new database gets the same form as a migrated one. The tables are still empty. */
    object Callback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = rebuild(db)
    }

    private fun windowStat() =
        listOf(
            "CREATE TABLE `_new_window_stat` (`windowId` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `keyId` INTEGER NOT NULL, " +
                "`value` INTEGER NOT NULL, `provenance` INTEGER NOT NULL, PRIMARY KEY(`windowId`, `uid`, `keyId`), " +
                "FOREIGN KEY(`windowId`) REFERENCES `window_result`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE ) WITHOUT ROWID",
            "INSERT INTO `_new_window_stat` (`windowId`, `uid`, `keyId`, `value`, `provenance`) " +
                "SELECT `windowId`, `uid`, `keyId`, `value`, `provenance` FROM `window_stat` ORDER BY `windowId`, `uid`, `keyId`",
            "DROP TABLE `window_stat`",
            "ALTER TABLE `_new_window_stat` RENAME TO `window_stat`",
        )
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import io.github.akane599.coulomb.core.database.entity.HistoryChunkCodec
import io.github.akane599.coulomb.core.database.entity.PackedEvent

/**
 * Version 7's history (ADR-0051): the rows of `history_event` packed into `history_chunk`, in id
 * order, losing nothing.
 *
 * - Event `i` of a chunk has the id `firstId + i`, so a chunk holds a run of ids without a break. A
 *   new chunk starts after every snapshot's `historyEventId` mark, at every break in the ids and
 *   after [HistoryChunkCodec.MAX_EVENTS] events. Every event keeps its id, and a mark is still the
 *   end of a chunk, where the ring is cut.
 * - `history_counter` takes the next id from the `AUTOINCREMENT` counter, the highest id and the
 *   highest mark, whichever is largest: an emptied ring must not hand out ids at or below the
 *   marks (ADR-0021's defect).
 * - `history_tag.lastEventId` becomes the last event stored with each tag, 0 for a tag only the
 *   kernel log uses.
 *
 * Codes are carried as stored, without reading the enums. A row the format cannot hold (a change
 * code above 3, a negative boot or tag id) fails the migration rather than being stored wrong; no
 * version ever wrote one.
 *
 * The definitions are version 7's, frozen here.
 */
internal object HistoryChunksMigration {
    fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "CREATE TABLE IF NOT EXISTS `history_chunk` (`firstId` INTEGER NOT NULL, `count` INTEGER NOT NULL, " +
                "`data` BLOB NOT NULL, PRIMARY KEY(`firstId`))",
            "CREATE TABLE IF NOT EXISTS `history_counter` (`id` INTEGER NOT NULL, `nextEventId` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "ALTER TABLE `history_tag` ADD COLUMN `lastEventId` INTEGER NOT NULL DEFAULT 0",
        ).forEach(db::execSQL)

        val marks = marks(db)
        val packer =
            db
                .compileStatement(
                    "INSERT INTO `history_chunk` (`firstId`, `count`, `data`) VALUES (?, ?, ?)",
                ).use { pack(db, marks, it) }
        db.compileStatement("UPDATE `history_tag` SET `lastEventId` = ? WHERE `id` = ?").use { update ->
            for ((tagId, eventId) in packer.lastUse) {
                update.bindLong(1, eventId)
                update.bindLong(2, tagId)
                update.executeUpdateDelete()
            }
        }

        val counted = long(db, "SELECT IFNULL(MAX(seq), 0) FROM sqlite_sequence WHERE name = 'history_event'")
        val highest = maxOf(packer.lastId, marks.maxOrNull() ?: 0L, counted)
        db.execSQL("INSERT INTO `history_counter` (`id`, `nextEventId`) VALUES (0, ${highest + 1})")
        db.execSQL("DROP TABLE `history_event`")
    }

    /**
     * Walks `history_event` in id order and writes its chunks through [insert]. Reads a page at a
     * time by key, so no cursor outgrows its window however long the ring is.
     */
    private fun pack(
        db: SupportSQLiteDatabase,
        marks: Set<Long>,
        insert: SupportSQLiteStatement,
    ): Packer {
        val packer = Packer(marks, insert)
        var after = Long.MIN_VALUE
        var read: Int
        do {
            read = 0
            db
                .query(
                    "SELECT `id`, `bootSeq`, `elapsedMs`, `type`, `change`, `baseline`, `uid`, `tagId`, `value` " +
                        "FROM `history_event` WHERE `id` > ? ORDER BY `id` LIMIT $PAGE",
                    arrayOf<Any?>(after),
                ).use { rows ->
                    while (rows.moveToNext()) {
                        after = rows.getLong(ID)
                        packer.add(after, event(rows))
                        read++
                    }
                }
        } while (read == PAGE)
        packer.flush()
        return packer
    }

    /**
     * The event of [rows]' current row. Read as `Long`s and checked, so an out-of-range number fails
     * the migration instead of being cut to an `Int`.
     */
    private fun event(rows: Cursor): PackedEvent {
        val id = rows.getLong(ID)

        fun int(
            column: Int,
            name: String,
        ): Int {
            val value = rows.getLong(column)
            check(value in Int.MIN_VALUE..Int.MAX_VALUE) { "history_event $id: $name $value is out of range" }
            return value.toInt()
        }
        return PackedEvent(
            bootSeq = int(BOOT_SEQ, "bootSeq"),
            elapsedMs = rows.getLong(ELAPSED_MS),
            typeCode = int(TYPE, "type"),
            changeCode = int(CHANGE, "change"),
            baseline = rows.getLong(BASELINE) != 0L,
            uid = if (rows.isNull(UID)) null else int(UID, "uid"),
            tagId = if (rows.isNull(TAG_ID)) null else rows.getLong(TAG_ID),
            value = if (rows.isNull(VALUE)) null else rows.getLong(VALUE),
        )
    }

    /** Gathers a run of events with consecutive ids and writes it as one chunk. */
    private class Packer(
        private val marks: Set<Long>,
        private val insert: SupportSQLiteStatement,
    ) {
        /** The last event stored with each tag. */
        val lastUse = HashMap<Long, Long>()
        var lastId = 0L
            private set
        private val pending = ArrayList<PackedEvent>(HistoryChunkCodec.MAX_EVENTS)
        private var firstId = 0L

        fun add(
            id: Long,
            event: PackedEvent,
        ) {
            val continues = pending.isNotEmpty() && id == lastId + 1 && lastId !in marks && pending.size < HistoryChunkCodec.MAX_EVENTS
            if (!continues) {
                flush()
                firstId = id
            }
            pending += event
            event.tagId?.let { lastUse[it] = id }
            lastId = id
        }

        fun flush() {
            if (pending.isEmpty()) return
            insert.bindLong(FIRST_ID, firstId)
            insert.bindLong(COUNT, pending.size.toLong())
            insert.bindBlob(DATA, HistoryChunkCodec.encode(pending))
            insert.executeInsert()
            pending.clear()
        }
    }

    /** Every snapshot's history mark: the id of the last event its read had stored. */
    private fun marks(db: SupportSQLiteDatabase): Set<Long> =
        db.query("SELECT DISTINCT `historyEventId` FROM `snapshot` WHERE `historyEventId` IS NOT NULL").use { rows ->
            buildSet {
                while (rows.moveToNext()) add(rows.getLong(0))
            }
        }

    private fun long(
        db: SupportSQLiteDatabase,
        query: String,
    ): Long =
        db.query(query).use {
            check(it.moveToFirst()) { "no row: $query" }
            it.getLong(0)
        }

    /** Rows read per page: a row of nine columns takes about 110 bytes of a cursor window, a page under 1 MB of its 2 MB. */
    private const val PAGE = 8_192

    private const val FIRST_ID = 1
    private const val COUNT = 2
    private const val DATA = 3

    private const val ID = 0
    private const val BOOT_SEQ = 1
    private const val ELAPSED_MS = 2
    private const val TYPE = 3
    private const val CHANGE = 4
    private const val BASELINE = 5
    private const val UID = 6
    private const val TAG_ID = 7
    private const val VALUE = 8
}

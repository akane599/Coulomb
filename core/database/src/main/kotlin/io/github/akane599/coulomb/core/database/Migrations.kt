// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.akane599.coulomb.core.database.entity.RecordCodes
import io.github.akane599.coulomb.core.database.entity.SourceStatusCodes

/**
 * Schema migrations (SPEC 8). Each is tested against the exported schemas in `MigrationTest`. The
 * table definitions are copied from the target version's exported schema, so Room's validation
 * after the migration finds exactly what it expects.
 */
internal object Migrations {
    /**
     * Version 2 (ADR-0021) narrows the biggest tables without losing a value:
     * - window counter keys and wakelock tags move to `stat_key` and `wake_tag`;
     * - window detail provenance becomes a `DetailCodes` code;
     * - the unused `(uid, windowId)` indexes of `window_stat` and `window_wake` go;
     * - history times are stored in milliseconds, which v1 times always were whole numbers of.
     *
     * Ids are kept, so snapshots' `historyEventId` marks stay valid, and so are the `AUTOINCREMENT`
     * counters: a dropped table takes its counter with it, and an emptied ring would otherwise
     * hand out ids below the marks again. Room runs a migration with foreign keys off and does not
     * check them afterwards; the rebuilt rows keep their `windowId`, and nothing references them.
     */
    val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                (lookups() + windowStat() + windowWake() + windowUid() + historyEvent()).forEach(db::execSQL)
            }
        }

    /**
     * Version 3 (ADR-0029) adds the collection mode and the root kernel data:
     * - `snapshot.uptimeMs` and `snapshot.privilegeMode`, null on the rows already stored, which
     *   predate both;
     * - `window_result.kernelCoverage` and `window_wake.wakeups`, null on the rows already stored,
     *   since no kernel source was collected for them;
     * - the empty tables `kernel_read`, `wakeup_source_stat` and `kernel_log_event`.
     *
     * Only additions: no row is rewritten, so no id or `AUTOINCREMENT` counter moves.
     */
    val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                kernelV3().forEach(db::execSQL)
            }
        }

    /**
     * Version 4 (ADR-0035) adds S4's breakdown and takes ADR-0021's margin step 1:
     * - `window_uid`'s `component` and `processState` names move to `stat_key`, which already holds
     *   every other window name; the rows are rebuilt with their ids, losing nothing;
     * - `window_result.breakdown`, null on the rows already stored, which no S4 read covers;
     * - the empty tables `uid_component_power` and `window_component`.
     *
     * `window_uid` has no `AUTOINCREMENT` counter, and nothing references its rows.
     */
    val MIGRATION_3_4: Migration =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                (windowUidIds() + componentsV4()).forEach(db::execSQL)
            }
        }

    /**
     * Version 5 (ADR-0040) narrows the records kept for good without losing a value, for the year's
     * size budget:
     * - the kinds of `session`, `snapshot` and `window_result` become [RecordCodes] codes;
     * - `snapshot.sourceStatusJson` becomes `sourceStatus`, a [SourceStatusCodes] code.
     *
     * The three tables are rebuilt with their ids and `AUTOINCREMENT` counters, so every reference
     * to them stays valid. Room runs a migration with foreign keys off, so dropping a parent table
     * does not touch its children.
     */
    val MIGRATION_4_5: Migration =
        object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                snapshotV5(db)
                (sessionV5() + windowResultV5()).forEach(db::execSQL)
            }
        }

    /**
     * Version 6 (ADR-0051) stores `window_stat` without a rowid, so each row's key is kept once
     * instead of twice. The rows are copied as they are: no value or key changes, the table has
     * no `AUTOINCREMENT` counter, and nothing references its rows.
     */
    val MIGRATION_5_6: Migration =
        object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = WithoutRowid.rebuild(db)
        }

    /**
     * Version 7 (ADR-0051) packs the history ring: `history_event`'s rows become `history_chunk`'s
     * byte strings, each event keeping its id, and `history_counter` and `history_tag.lastEventId`
     * take over what `AUTOINCREMENT` and a scan of every event did ([HistoryChunksMigration]).
     */
    val MIGRATION_6_7: Migration =
        object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) = HistoryChunksMigration.migrate(db)
        }

    val ALL: Array<Migration> =
        arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

    /** The 20 S4 columns of version 4 (`PowerColumns`), in their exported order. */
    private const val POWER_COLUMNS =
        "`screen` INTEGER NOT NULL, `cpu` INTEGER NOT NULL, `bluetooth` INTEGER NOT NULL, `camera` INTEGER NOT NULL, " +
            "`audio` INTEGER NOT NULL, `video` INTEGER NOT NULL, `flashlight` INTEGER NOT NULL, `mobileRadio` INTEGER NOT NULL, " +
            "`sensors` INTEGER NOT NULL, `gnss` INTEGER NOT NULL, `wifi` INTEGER NOT NULL, `wakelock` INTEGER NOT NULL, " +
            "`phone` INTEGER NOT NULL, `ambientDisplay` INTEGER NOT NULL, `custom` INTEGER NOT NULL, `other` INTEGER NOT NULL, " +
            "`foreground` INTEGER NOT NULL, `background` INTEGER NOT NULL, `foregroundService` INTEGER NOT NULL, `cached` INTEGER NOT NULL"

    /**
     * The codes of `DetailCodes` at version 2, frozen here. A value outside them fails the NOT NULL
     * column and the migration, rather than storing a wrong code.
     */
    private const val PROVENANCE_CODE =
        "CASE provenance WHEN 'MEASURED' THEN 0 WHEN 'MODELED' THEN 1 WHEN 'ESTIMATED' THEN 2 WHEN 'DERIVED' THEN 3 END"

    private fun lookups() =
        listOf(
            "CREATE TABLE IF NOT EXISTS `stat_key` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_stat_key_name` ON `stat_key` (`name`)",
            "INSERT INTO `stat_key` (`name`) SELECT DISTINCT `key` FROM `window_stat` ORDER BY `key`",
            "CREATE TABLE IF NOT EXISTS `wake_tag` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tag` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_wake_tag_tag` ON `wake_tag` (`tag`)",
            "INSERT INTO `wake_tag` (`tag`) SELECT DISTINCT `tag` FROM `window_wake` ORDER BY `tag`",
        )

    private fun windowStat() =
        listOf(
            "CREATE TABLE IF NOT EXISTS `_new_window_stat` (`windowId` INTEGER NOT NULL, `uid` INTEGER NOT NULL, " +
                "`keyId` INTEGER NOT NULL, `value` INTEGER NOT NULL, `provenance` INTEGER NOT NULL, " +
                "PRIMARY KEY(`windowId`, `uid`, `keyId`), FOREIGN KEY(`windowId`) REFERENCES `window_result`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "INSERT INTO `_new_window_stat` (`windowId`, `uid`, `keyId`, `value`, `provenance`) " +
                "SELECT s.`windowId`, s.`uid`, k.`id`, s.`value`, $PROVENANCE_CODE " +
                "FROM `window_stat` s JOIN `stat_key` k ON k.`name` = s.`key`",
            "DROP TABLE `window_stat`",
            "ALTER TABLE `_new_window_stat` RENAME TO `window_stat`",
        )

    private fun windowWake() =
        listOf(
            "CREATE TABLE IF NOT EXISTS `_new_window_wake` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`windowId` INTEGER NOT NULL, `uid` INTEGER, `tagId` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                "`totalMs` INTEGER NOT NULL, `count` INTEGER NOT NULL, `screenOffMs` INTEGER, " +
                "FOREIGN KEY(`windowId`) REFERENCES `window_result`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "INSERT INTO `_new_window_wake` (`id`, `windowId`, `uid`, `tagId`, `kind`, `totalMs`, `count`, `screenOffMs`) " +
                "SELECT w.`id`, w.`windowId`, w.`uid`, t.`id`, w.`kind`, w.`totalMs`, w.`count`, w.`screenOffMs` " +
                "FROM `window_wake` w JOIN `wake_tag` t ON t.`tag` = w.`tag`",
        ) + carryCounter("window_wake") +
            listOf(
                "DROP TABLE `window_wake`",
                "ALTER TABLE `_new_window_wake` RENAME TO `window_wake`",
                "CREATE INDEX IF NOT EXISTS `index_window_wake_windowId` ON `window_wake` (`windowId`)",
            )

    private fun windowUid() =
        listOf(
            "CREATE TABLE IF NOT EXISTS `_new_window_uid` (`windowId` INTEGER NOT NULL, `uid` INTEGER NOT NULL, " +
                "`component` TEXT NOT NULL, `processState` TEXT NOT NULL, `process` TEXT NOT NULL, `mAh` REAL NOT NULL, " +
                "`provenance` INTEGER NOT NULL, PRIMARY KEY(`windowId`, `uid`, `component`, `processState`, `process`), " +
                "FOREIGN KEY(`windowId`) REFERENCES `window_result`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "INSERT INTO `_new_window_uid` (`windowId`, `uid`, `component`, `processState`, `process`, `mAh`, `provenance`) " +
                "SELECT `windowId`, `uid`, `component`, `processState`, `process`, `mAh`, $PROVENANCE_CODE FROM `window_uid`",
            "DROP TABLE `window_uid`",
            "ALTER TABLE `_new_window_uid` RENAME TO `window_uid`",
            "CREATE INDEX IF NOT EXISTS `index_window_uid_uid_windowId` ON `window_uid` (`uid`, `windowId`)",
        )

    private fun historyEvent() =
        listOf(
            "CREATE TABLE IF NOT EXISTS `_new_history_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bootSeq` INTEGER NOT NULL, `elapsedMs` INTEGER NOT NULL, `type` INTEGER NOT NULL, " +
                "`change` INTEGER NOT NULL, `baseline` INTEGER NOT NULL, `uid` INTEGER, `tagId` INTEGER, `value` INTEGER)",
            "INSERT INTO `_new_history_event` (`id`, `bootSeq`, `elapsedMs`, `type`, `change`, `baseline`, `uid`, " +
                "`tagId`, `value`) SELECT `id`, `bootSeq`, `elapsedNs` / 1000000, `type`, `change`, `baseline`, " +
                "`uid`, `tagId`, `value` FROM `history_event`",
        ) + carryCounter("history_event") +
            listOf(
                "DROP TABLE `history_event`",
                "ALTER TABLE `_new_history_event` RENAME TO `history_event`",
            )

    private fun kernelV3() =
        listOf(
            "ALTER TABLE `snapshot` ADD COLUMN `uptimeMs` INTEGER",
            "ALTER TABLE `snapshot` ADD COLUMN `privilegeMode` INTEGER",
            "ALTER TABLE `window_result` ADD COLUMN `kernelCoverage` INTEGER",
            "ALTER TABLE `window_wake` ADD COLUMN `wakeups` INTEGER",
            "CREATE TABLE IF NOT EXISTS `kernel_read` (`snapshotId` INTEGER NOT NULL, `kernelBoot` INTEGER NOT NULL, " +
                "`suspendSuccess` INTEGER, `suspendFail` INTEGER, `failedFreeze` INTEGER, `failedPrepare` INTEGER, " +
                "`failedSuspend` INTEGER, `failedSuspendLate` INTEGER, `failedSuspendNoirq` INTEGER, `failedResume` INTEGER, " +
                "`failedResumeEarly` INTEGER, `failedResumeNoirq` INTEGER, `lastFailedDev` TEXT, `lastFailedErrno` INTEGER, " +
                "`lastFailedStep` TEXT, `resumeReason` TEXT, `suspendResumeNs` INTEGER, `sleepNs` INTEGER, " +
                "`kmsgAfterSeq` INTEGER, `kmsgFirstSeq` INTEGER, `kmsgLastSeq` INTEGER, `kmsgGaps` TEXT, " +
                "PRIMARY KEY(`snapshotId`), FOREIGN KEY(`snapshotId`) REFERENCES `snapshot`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `wakeup_source_stat` (`snapshotId` INTEGER NOT NULL, `instance` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, `activeCount` INTEGER NOT NULL, `eventCount` INTEGER NOT NULL, " +
                "`wakeupCount` INTEGER NOT NULL, `expireCount` INTEGER NOT NULL, `activeTimeMs` INTEGER NOT NULL, " +
                "`totalTimeMs` INTEGER NOT NULL, `maxTimeMs` INTEGER NOT NULL, `lastChangeMs` INTEGER NOT NULL, " +
                "`preventSuspendTimeMs` INTEGER NOT NULL, PRIMARY KEY(`snapshotId`, `instance`), " +
                "FOREIGN KEY(`snapshotId`) REFERENCES `snapshot`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `kernel_log_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`kernelBoot` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `monotonicUs` INTEGER NOT NULL, `kind` INTEGER NOT NULL, " +
                "`irq` INTEGER, `tagId` INTEGER, `errno` INTEGER, `readSnapshotId` INTEGER NOT NULL, " +
                "FOREIGN KEY(`readSnapshotId`) REFERENCES `snapshot`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_kernel_log_event_kernelBoot_seq` ON `kernel_log_event` (`kernelBoot`, `seq`)",
            "CREATE INDEX IF NOT EXISTS `index_kernel_log_event_readSnapshotId` ON `kernel_log_event` (`readSnapshotId`)",
        )

    private fun windowUidIds() =
        listOf(
            "INSERT OR IGNORE INTO `stat_key` (`name`) SELECT `component` FROM `window_uid` " +
                "UNION SELECT `processState` FROM `window_uid` ORDER BY 1",
            "CREATE TABLE IF NOT EXISTS `_new_window_uid` (`windowId` INTEGER NOT NULL, `uid` INTEGER NOT NULL, " +
                "`componentId` INTEGER NOT NULL, `processStateId` INTEGER NOT NULL, `process` TEXT NOT NULL, " +
                "`mAh` REAL NOT NULL, `provenance` INTEGER NOT NULL, " +
                "PRIMARY KEY(`windowId`, `uid`, `componentId`, `processStateId`, `process`), " +
                "FOREIGN KEY(`windowId`) REFERENCES `window_result`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "INSERT INTO `_new_window_uid` (`windowId`, `uid`, `componentId`, `processStateId`, `process`, `mAh`, `provenance`) " +
                "SELECT u.`windowId`, u.`uid`, c.`id`, p.`id`, u.`process`, u.`mAh`, u.`provenance` FROM `window_uid` u " +
                "JOIN `stat_key` c ON c.`name` = u.`component` JOIN `stat_key` p ON p.`name` = u.`processState`",
            "DROP TABLE `window_uid`",
            "ALTER TABLE `_new_window_uid` RENAME TO `window_uid`",
            "CREATE INDEX IF NOT EXISTS `index_window_uid_uid_windowId` ON `window_uid` (`uid`, `windowId`)",
        )

    private fun componentsV4() =
        listOf(
            "ALTER TABLE `window_result` ADD COLUMN `breakdown` INTEGER",
            "CREATE TABLE IF NOT EXISTS `uid_component_power` (`snapshotId` INTEGER NOT NULL, `uid` INTEGER NOT NULL, " +
                "$POWER_COLUMNS, PRIMARY KEY(`snapshotId`, `uid`), FOREIGN KEY(`snapshotId`) REFERENCES `snapshot`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `window_component` (`windowId` INTEGER NOT NULL, `uid` INTEGER NOT NULL, " +
                "$POWER_COLUMNS, PRIMARY KEY(`windowId`, `uid`), FOREIGN KEY(`windowId`) REFERENCES `window_result`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )

    /** `CASE column WHEN 'NAME' THEN code ...`: a name outside [entries] gives null, which a NOT NULL column refuses. */
    private fun <T : Enum<T>> codeOf(
        column: String,
        entries: List<T>,
    ) = entries.withIndex().joinToString(" ", "CASE `$column` ", " END") { (code, entry) -> "WHEN '${entry.name}' THEN $code" }

    private fun snapshotV5(db: SupportSQLiteDatabase) {
        listOf(
            "CREATE TABLE IF NOT EXISTS `_new_snapshot` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bootSeq` INTEGER NOT NULL, `elapsedNs` INTEGER NOT NULL, `wallMs` INTEGER NOT NULL, " +
                "`trigger` INTEGER NOT NULL, `kind` INTEGER NOT NULL, `levelPct` INTEGER, `chargeUah` INTEGER, " +
                "`currentRaw` INTEGER, `voltageMv` INTEGER, `tempDeciC` INTEGER, `plugType` INTEGER, `status` INTEGER, " +
                "`statsStartWall` INTEGER, `statsRealtimeMs` INTEGER, `historyCursorMs` INTEGER, `historyEventId` INTEGER, " +
                "`sourceStatus` INTEGER NOT NULL, `blobPath` TEXT, `partial` INTEGER NOT NULL, " +
                "`countersPruned` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `uptimeMs` INTEGER, `privilegeMode` INTEGER)",
            "INSERT INTO `_new_snapshot` (`id`, `bootSeq`, `elapsedNs`, `wallMs`, `trigger`, `kind`, `levelPct`, " +
                "`chargeUah`, `currentRaw`, `voltageMv`, `tempDeciC`, `plugType`, `status`, `statsStartWall`, " +
                "`statsRealtimeMs`, `historyCursorMs`, `historyEventId`, `sourceStatus`, `blobPath`, `partial`, " +
                "`countersPruned`, `durationMs`, `uptimeMs`, `privilegeMode`) " +
                "SELECT `id`, `bootSeq`, `elapsedNs`, `wallMs`, ${codeOf("trigger", RecordCodes.TRIGGERS)}, " +
                "${codeOf("kind", RecordCodes.SNAPSHOT_KINDS)}, `levelPct`, `chargeUah`, `currentRaw`, `voltageMv`, " +
                "`tempDeciC`, `plugType`, `status`, `statsStartWall`, `statsRealtimeMs`, `historyCursorMs`, " +
                "`historyEventId`, 0, `blobPath`, `partial`, `countersPruned`, `durationMs`, `uptimeMs`, " +
                "`privilegeMode` FROM `snapshot`",
        ).forEach(db::execSQL)
        // The JSON is parsed here rather than in SQL, by the same code the tests use.
        db.compileStatement("UPDATE `_new_snapshot` SET `sourceStatus` = ? WHERE `id` = ?").use { update ->
            db.query("SELECT `id`, `sourceStatusJson` FROM `snapshot` WHERE `sourceStatusJson` <> '{}'").use { rows ->
                while (rows.moveToNext()) {
                    update.bindLong(1, SourceStatusCodes.fromJson(rows.getString(1)))
                    update.bindLong(2, rows.getLong(0))
                    update.executeUpdateDelete()
                }
            }
        }
        (
            carryCounter("snapshot") +
                listOf(
                    "DROP TABLE `snapshot`",
                    "ALTER TABLE `_new_snapshot` RENAME TO `snapshot`",
                    "CREATE INDEX IF NOT EXISTS `index_snapshot_bootSeq_elapsedNs` ON `snapshot` (`bootSeq`, `elapsedNs`)",
                    "CREATE INDEX IF NOT EXISTS `index_snapshot_wallMs` ON `snapshot` (`wallMs`)",
                )
        ).forEach(db::execSQL)
    }

    private fun sessionV5() =
        listOf(
            "CREATE TABLE IF NOT EXISTS `_new_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`kind` INTEGER NOT NULL, `bootSeq` INTEGER NOT NULL, `startElapsedNs` INTEGER NOT NULL, " +
                "`endElapsedNs` INTEGER, `startWall` INTEGER NOT NULL, `endWall` INTEGER, `startSnapshotId` INTEGER, " +
                "`endSnapshotId` INTEGER, `startLevel` INTEGER NOT NULL, `endLevel` INTEGER, `startChargeUah` INTEGER, " +
                "`endChargeUah` INTEGER, `chargeSource` INTEGER, `avgCurrentMa` REAL, `peakCurrentMa` REAL, " +
                "`minTempDeciC` INTEGER, `meanTempDeciC` INTEGER, `maxTempDeciC` INTEGER NOT NULL, `awakeRatio` REAL, " +
                "`deepIdleReached` INTEGER, `deepIdleReachedMs` INTEGER, `minutesAtOrAbove80` INTEGER, " +
                "`minutesAt100` INTEGER, `stressIndex` REAL, `cycleEquivalents` REAL, `closeReason` INTEGER, " +
                "`quality` REAL NOT NULL, FOREIGN KEY(`startSnapshotId`) REFERENCES `snapshot`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`endSnapshotId`) REFERENCES `snapshot`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE RESTRICT )",
            "INSERT INTO `_new_session` (`id`, `kind`, `bootSeq`, `startElapsedNs`, `endElapsedNs`, `startWall`, " +
                "`endWall`, `startSnapshotId`, `endSnapshotId`, `startLevel`, `endLevel`, `startChargeUah`, " +
                "`endChargeUah`, `chargeSource`, `avgCurrentMa`, `peakCurrentMa`, `minTempDeciC`, `meanTempDeciC`, " +
                "`maxTempDeciC`, `awakeRatio`, `deepIdleReached`, `deepIdleReachedMs`, `minutesAtOrAbove80`, " +
                "`minutesAt100`, `stressIndex`, `cycleEquivalents`, `closeReason`, `quality`) " +
                "SELECT `id`, ${codeOf("kind", RecordCodes.SESSION_KINDS)}, `bootSeq`, `startElapsedNs`, " +
                "`endElapsedNs`, `startWall`, `endWall`, `startSnapshotId`, `endSnapshotId`, `startLevel`, `endLevel`, " +
                "`startChargeUah`, `endChargeUah`, ${codeOf("chargeSource", RecordCodes.CHARGE_SOURCES)}, " +
                "`avgCurrentMa`, `peakCurrentMa`, `minTempDeciC`, `meanTempDeciC`, `maxTempDeciC`, `awakeRatio`, " +
                "`deepIdleReached`, `deepIdleReachedMs`, `minutesAtOrAbove80`, `minutesAt100`, `stressIndex`, " +
                "`cycleEquivalents`, ${codeOf("closeReason", RecordCodes.CLOSE_REASONS)}, `quality` FROM `session`",
        ) + carryCounter("session") +
            listOf(
                "DROP TABLE `session`",
                "ALTER TABLE `_new_session` RENAME TO `session`",
                "CREATE INDEX IF NOT EXISTS `index_session_kind_bootSeq_startElapsedNs` ON `session` " +
                    "(`kind`, `bootSeq`, `startElapsedNs`)",
                "CREATE INDEX IF NOT EXISTS `index_session_startSnapshotId` ON `session` (`startSnapshotId`)",
                "CREATE INDEX IF NOT EXISTS `index_session_endSnapshotId` ON `session` (`endSnapshotId`)",
            )

    private fun windowResultV5() =
        listOf(
            "CREATE TABLE IF NOT EXISTS `_new_window_result` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`kind` INTEGER NOT NULL, `sessionId` INTEGER, `fromSnapshotId` INTEGER NOT NULL, " +
                "`toSnapshotId` INTEGER NOT NULL, `measuredMah` REAL, `chargeSource` INTEGER, `modeledMah` REAL, " +
                "`unattributedMah` REAL, `screenOnMs` INTEGER NOT NULL, `screenOffMs` INTEGER NOT NULL, " +
                "`awakeMsScreenOff` INTEGER, `quality` REAL NOT NULL, `detailPruned` INTEGER NOT NULL, " +
                "`kernelCoverage` INTEGER, `breakdown` INTEGER, FOREIGN KEY(`fromSnapshotId`) REFERENCES `snapshot`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`toSnapshotId`) REFERENCES `snapshot`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`sessionId`) REFERENCES `session`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL )",
            "INSERT INTO `_new_window_result` (`id`, `kind`, `sessionId`, `fromSnapshotId`, `toSnapshotId`, " +
                "`measuredMah`, `chargeSource`, `modeledMah`, `unattributedMah`, `screenOnMs`, `screenOffMs`, " +
                "`awakeMsScreenOff`, `quality`, `detailPruned`, `kernelCoverage`, `breakdown`) " +
                "SELECT `id`, ${codeOf("kind", RecordCodes.WINDOW_KINDS)}, `sessionId`, `fromSnapshotId`, " +
                "`toSnapshotId`, `measuredMah`, ${codeOf("chargeSource", RecordCodes.CHARGE_SOURCES)}, `modeledMah`, " +
                "`unattributedMah`, `screenOnMs`, `screenOffMs`, `awakeMsScreenOff`, `quality`, `detailPruned`, " +
                "`kernelCoverage`, `breakdown` FROM `window_result`",
        ) + carryCounter("window_result") +
            listOf(
                "DROP TABLE `window_result`",
                "ALTER TABLE `_new_window_result` RENAME TO `window_result`",
                "CREATE INDEX IF NOT EXISTS `index_window_result_fromSnapshotId` ON `window_result` (`fromSnapshotId`)",
                "CREATE INDEX IF NOT EXISTS `index_window_result_toSnapshotId` ON `window_result` (`toSnapshotId`)",
                "CREATE INDEX IF NOT EXISTS `index_window_result_sessionId` ON `window_result` (`sessionId`)",
                "CREATE INDEX IF NOT EXISTS `index_window_result_kind_toSnapshotId` ON `window_result` (`kind`, `toSnapshotId`)",
            )

    /**
     * Gives `_new_<table>` the `AUTOINCREMENT` counter of [table] before [table] is dropped; the
     * rename then keeps it. The old counter is at least every id copied.
     */
    private fun carryCounter(table: String) =
        listOf(
            "DELETE FROM sqlite_sequence WHERE name = '_new_$table'",
            "INSERT INTO sqlite_sequence (name, seq) SELECT '_new_$table', seq FROM sqlite_sequence WHERE name = '$table'",
        )
}

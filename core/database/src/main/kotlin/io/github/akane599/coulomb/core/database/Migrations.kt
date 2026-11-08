// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

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

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.dao.KernelRows
import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.dao.WindowStat
import io.github.akane599.coulomb.core.database.dao.WindowWake
import io.github.akane599.coulomb.core.database.entity.HistoryChunkCodec
import io.github.akane599.coulomb.core.database.entity.HistoryCodes
import io.github.akane599.coulomb.core.database.entity.KernelReadEntity
import io.github.akane599.coulomb.core.database.entity.KmsgSpan
import io.github.akane599.coulomb.core.database.entity.PowerColumns
import io.github.akane599.coulomb.core.database.entity.SourceStatusCodes
import io.github.akane599.coulomb.core.database.entity.UidComponentPowerEntity
import io.github.akane599.coulomb.core.database.entity.WindowComponentEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowUid
import io.github.akane599.coulomb.core.model.Breakdown
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.PrivilegeMode
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import io.github.akane599.coulomb.core.model.kernel.KernelLogEvent
import io.github.akane599.coulomb.core.model.kernel.KernelLogRecord
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every migration against the committed schemas (SPEC 8): the result must match the exported schema and keep every value. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CoulombDatabase::class.java)

    @Test
    fun oneToTwoKeepsEveryDetailValueAndEventId() =
        runTest {
            helper.createDatabase(NAME, 1).use { v1 -> (snapshotsAndWindow() + detailAndHistory()).forEach(v1::execSQL) }

            helper.runMigrationsAndValidate(NAME, 2, true, *Migrations.ALL).close()

            migrated { db ->
                val rows = db.windowDao().rows(7)
                assertThat(
                    rows.uids.associate { it.uid to it.provenance },
                ).containsExactly(10123, Provenance.MODELED, -2, Provenance.DERIVED)
                assertThat(rows.wakes)
                    .containsExactly(
                        WindowWake(null, "qcom_rx_wakelock", WakeKind.KERNEL, 1200, 3, null),
                        WindowWake(10123, "*job*/sync", WakeKind.PARTIAL, 900, 1, 0),
                        WindowWake(10124, "*job*/sync", WakeKind.PARTIAL, 300, 2, 300),
                    ).inOrder()
                assertThat(rows.stats)
                    .containsExactly(
                        WindowStat(10123, "job_runtime_ms", 4000, Provenance.MEASURED),
                        WindowStat(10123, "alarm_wakeups", 3, Provenance.ESTIMATED),
                        WindowStat(-1, "alarm_wakeups", 14, Provenance.MEASURED),
                    )
                // The snapshots still mark the same events, now in milliseconds.
                val events = db.historyDao().events(1, 0, 1, 20 * NS_PER_S)
                assertThat(events.map { it.event.id to it.event.elapsedMs }).containsExactly(2L to 9_500L, 3L to 15_001L).inOrder()
                assertThat(events.map { it.event.type }).containsExactly(HistoryKind.BATTERY_LEVEL, HistoryKind.WAKE_LOCK).inOrder()
                assertThat(events[1].event.change).isEqualTo(HistoryChange.START)
                assertThat(events.map { it.tag }).containsExactly(null, "*alarm*").inOrder()
                // A window stored after the migration finds the migrated tags.
                val id = db.windowDao().insert(db.windowDao().window(7)!!.copy(id = 0), rows)
                assertThat(
                    db
                        .windowDao()
                        .rows(id)
                        .wakes
                        .map { it.tag },
                ).containsExactly("qcom_rx_wakelock", "*job*/sync", "*job*/sync")
            }
        }

    /**
     * A ring emptied before the upgrade (history reads failing for 30 days) must not hand out its
     * old ids again: snapshots still mark id 50, so a new event 1 would be read as already cut.
     */
    @Test
    fun oneToTwoKeepsTheIdCountersOfEmptiedTables() =
        runTest {
            helper.createDatabase(NAME, 1).use { v1 ->
                (
                    snapshotsAndWindow() +
                        listOf(
                            "INSERT INTO history_event VALUES (50, 1, 9500000000, $LEVEL, $SET, 0, NULL, NULL, 80)",
                            "DELETE FROM history_event",
                            "INSERT INTO window_wake VALUES (60, 7, NULL, 'qcom_rx_wakelock', 'KERNEL', 1200, 3, NULL)",
                            "DELETE FROM window_wake",
                        )
                ).forEach(v1::execSQL)
            }

            helper.runMigrationsAndValidate(NAME, 2, true, *Migrations.ALL).close()

            migrated { db ->
                assertThat(db.historyDao().insertRead(listOf(event(1, 30_000)), emptyList())).isEqualTo(51)
                db.windowDao().insert(window(1, 2), WindowRows(wakes = listOf(WindowWake(null, "x", WakeKind.KERNEL, 1, 1, null))))
                val wakeId =
                    db.openHelper.readableDatabase.query("SELECT MAX(id) FROM window_wake").use {
                        it.moveToFirst()
                        it.getLong(0)
                    }
                assertThat(wakeId).isEqualTo(61)
            }
        }

    /** Version 3 only adds: stored rows keep every value, and the new columns read as "not collected". */
    @Test
    fun twoToThreeAddsKernelStorageAndKeepsEveryRow() =
        runTest {
            helper.createDatabase(NAME, 2).use { v2 ->
                (
                    snapshotsAndWindow() +
                        listOf(
                            "INSERT INTO wake_tag VALUES (3, 'qcom_rx_wakelock')",
                            "INSERT INTO window_wake VALUES (40, 7, NULL, 3, 'KERNEL', 1200, 3, NULL)",
                            "INSERT INTO history_tag VALUES (5, '*alarm*')",
                            "INSERT INTO history_event VALUES (3, 1, 15001, $WAKE_LOCK, $START, 1, 10123, 5, NULL)",
                        )
                ).forEach(v2::execSQL)
            }

            helper.runMigrationsAndValidate(NAME, 3, true, *Migrations.ALL).close()

            migrated { db ->
                val snapshot = checkNotNull(db.snapshotDao().snapshot(2))
                assertThat(snapshot.historyEventId).isEqualTo(3)
                assertThat(snapshot.uptimeMs).isNull()
                assertThat(snapshot.privilegeMode).isNull()
                val window = checkNotNull(db.windowDao().window(7))
                assertThat(window.kernelCoverage).isNull()
                assertThat(db.windowDao().rows(7).wakes)
                    .containsExactly(WindowWake(null, "qcom_rx_wakelock", WakeKind.KERNEL, 1200, 3, null, wakeups = null))
                assertThat(db.historyDao().events(1, 0, 1, 20 * NS_PER_S).map { it.tag }).containsExactly("*alarm*")

                // The new storage works on the migrated file.
                val id =
                    db.snapshotDao().insert(
                        db.snapshotDao().snapshot(2)!!.copy(id = 0, elapsedNs = 30 * NS_PER_S, privilegeMode = PrivilegeMode.ROOT),
                        kernel =
                            KernelRows(
                                KernelReadEntity.of(0, kernelBoot = 42, kmsg = KmsgSpan(null, 1, 9, emptyList())),
                                events = listOf(KernelLogRecord(9, 77, KernelLogEvent.Abort("*alarm*"))),
                            ),
                    )
                assertThat(db.snapshotDao().snapshot(id)?.privilegeMode).isEqualTo(PrivilegeMode.ROOT)
                assertThat(db.snapshotDao().kmsgCursor(42)).isEqualTo(9)
                assertThat(
                    db
                        .snapshotDao()
                        .kernelLog(42, 0, 9)
                        .single()
                        .event,
                ).isEqualTo(KernelLogEvent.Abort("*alarm*"))
                // The kernel event reuses the history tag already stored.
                assertThat(long(db, "SELECT COUNT(*) FROM history_tag")).isEqualTo(1)
                val coverage = KernelCoverage(Coverage.WHOLE, Coverage.NONE, Coverage.PARTIAL)
                val windowId =
                    db.windowDao().insert(
                        window.copy(id = 0, kernelCoverage = coverage),
                        WindowRows(wakes = listOf(WindowWake(null, "wlan_rx_wake", WakeKind.WAKEUP_SOURCE, 900, 4, null, wakeups = 2))),
                    )
                assertThat(db.windowDao().window(windowId)?.kernelCoverage).isEqualTo(coverage)
                assertThat(
                    db
                        .windowDao()
                        .rows(windowId)
                        .wakes
                        .single()
                        .wakeups,
                ).isEqualTo(2)
            }
        }

    @Test
    fun threeToFourMovesWindowUidNamesToIdsAndAddsS4Storage() =
        runTest {
            helper.createDatabase(NAME, 3).use { v3 ->
                (
                    snapshotsAndWindow() +
                        listOf(
                            // `cpu` is already a counter key: the migration reuses its id.
                            "INSERT INTO stat_key VALUES (4, 'cpu')",
                            "INSERT INTO window_stat VALUES (7, 10123, 4, 900, 0)",
                            "INSERT INTO window_uid VALUES (7, 10123, 'all', 'any', '', 4.5, 1)",
                            "INSERT INTO window_uid VALUES (7, 10123, 'cpu', 'any', 'com.example:remote', 1.25, 1)",
                            "INSERT INTO window_uid VALUES (7, -2, 'all', 'any', '', 0.5, 3)",
                            "INSERT INTO window_uid VALUES (7, -1, 'SCREEN', 'any', '', 30.0, 1)",
                        )
                ).forEach(v3::execSQL)
            }

            helper.runMigrationsAndValidate(NAME, 4, true, *Migrations.ALL).close()

            migrated { db ->
                val rows = db.windowDao().rows(7)
                assertThat(rows.uids)
                    .containsExactly(
                        WindowUid(7, 10123, "all", "any", "", 4.5, Provenance.MODELED),
                        WindowUid(7, 10123, "cpu", "any", "com.example:remote", 1.25, Provenance.MODELED),
                        WindowUid(7, -2, "all", "any", "", 0.5, Provenance.DERIVED),
                        WindowUid(7, -1, "SCREEN", "any", "", 30.0, Provenance.MODELED),
                    )
                assertThat(rows.stats).containsExactly(WindowStat(10123, "cpu", 900, Provenance.MEASURED))
                assertThat(long(db, "SELECT COUNT(*) FROM stat_key")).isEqualTo(4)
                assertThat(long(db, "SELECT id FROM stat_key WHERE name = 'cpu'")).isEqualTo(4)
                val window = checkNotNull(db.windowDao().window(7))
                assertThat(window.breakdown).isNull()

                s4StorageWorks(db, window)
            }
        }

    @Test
    fun fourToFiveCodesTheKindsAndKeepsEveryRowAndCounter() =
        runTest {
            helper.createDatabase(NAME, 4).use { v4 -> recordsV4().forEach(v4::execSQL) }

            helper.runMigrationsAndValidate(NAME, 5, true, *Migrations.ALL).close()

            migrated { db ->
                val snapshots = db.snapshotDao()
                val root = checkNotNull(snapshots.snapshot(2))
                assertThat(root.trigger).isEqualTo(SnapshotTrigger.SCREEN_ON)
                assertThat(root.kind).isEqualTo(SnapshotKind.HEAVY)
                assertThat(root.privilegeMode).isEqualTo(PrivilegeMode.ROOT)
                assertThat(SourceStatusCodes.decode(root.sourceStatus))
                    .containsExactly(
                        "S25",
                        "ok",
                        "S26",
                        "ok",
                        "S27",
                        "partial",
                        "S4",
                        "skipped",
                        "S5",
                        "slept",
                        "S6",
                        "ok",
                        "S7",
                        "ok",
                        "S9",
                        "ok",
                    )
                assertThat(SourceStatusCodes.decode(checkNotNull(snapshots.snapshot(1)).sourceStatus))
                    .containsExactly("S4", "off", "S5", "ok", "S7", "failed")
                val light = checkNotNull(snapshots.snapshot(3))
                assertThat(light.kind).isEqualTo(SnapshotKind.LIGHT)
                assertThat(light.trigger).isEqualTo(SnapshotTrigger.SERVICE_STARTED)
                assertThat(light.sourceStatus).isEqualTo(0)
                // An outcome the collector never wrote reads as not read; an unknown source goes.
                assertThat(SourceStatusCodes.decode(checkNotNull(snapshots.snapshot(4)).sourceStatus))
                    .containsExactly("S5", "failed")

                assertThat(long(db, "SELECT kind * 100 + chargeSource * 10 + closeReason FROM session WHERE id = 5")).isEqualTo(100)
                assertThat(db.sessionDao().cycleEquivalentsSum(SessionKind.CHARGE)).isEqualTo(0.25)
                val open = db.sessionDao().open().single()
                assertThat(open.kind).isEqualTo(SessionKind.SCREEN_OFF)
                assertThat(open.chargeSource).isNull()
                assertThat(open.closeReason).isNull()
                assertThat(db.sessionDao().chargeDetail(5)).isNotNull()

                assertThat(checkNotNull(db.windowDao().window(7)).kind).isEqualTo(WindowKind.CONSECUTIVE)
                val session = checkNotNull(db.windowDao().window(8))
                assertThat(session.kind).isEqualTo(WindowKind.SESSION)
                assertThat(session.chargeSource).isEqualTo(ChargeSource.LEVEL_DELTA)
                assertThat(session.sessionId).isEqualTo(5)
                assertThat(db.windowDao().rows(7).stats).containsExactly(WindowStat(10123, "cpu", 900, Provenance.MEASURED))

                assertThat(long(db, "SELECT COUNT(*) FROM pragma_foreign_key_check")).isEqualTo(0)
                // The AUTOINCREMENT counters moved with the tables: deleted ids are not handed out again.
                assertThat(snapshots.insert(root.copy(id = 0, elapsedNs = 90 * NS_PER_S))).isEqualTo(10)
                assertThat(db.sessionDao().insert(open.copy(id = 0))).isEqualTo(10)
                assertThat(db.windowDao().insert(session.copy(id = 0), WindowRows())).isEqualTo(10)
            }
        }

    @Test
    fun fiveToSixDropsTheRowidAndKeepsEveryDetailRow() =
        runTest {
            helper.createDatabase(NAME, 4).use { v4 -> (recordsV4() + detailV4()).forEach(v4::execSQL) }
            val before = helper.runMigrationsAndValidate(NAME, 5, true, *Migrations.ALL).use(::detail)
            assertThat(before).hasSize(7)

            helper.runMigrationsAndValidate(NAME, 6, true, *Migrations.ALL).use { v6 ->
                assertThat(detail(v6)).containsExactlyElementsIn(before)
                WithoutRowid.TABLES.forEach { table ->
                    val sql = strings(v6, "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '$table'").single()
                    assertThat(sql).endsWith("WITHOUT ROWID")
                }
                // The key is stored once now: SQLite keeps no automatic index beside the table.
                assertThat(strings(v6, "SELECT name FROM sqlite_master WHERE tbl_name = 'window_stat' AND type = 'index'")).isEmpty()
                assertThat(strings(v6, "PRAGMA foreign_key_check")).isEmpty()
            }

            migrated { db ->
                val rows = db.windowDao().rows(7)
                assertThat(rows.stats)
                    .containsExactly(
                        WindowStat(-1, "cpu", 14, Provenance.MEASURED),
                        WindowStat(10123, "cpu", 900, Provenance.MEASURED),
                        WindowStat(10123, "wifi", 30, Provenance.ESTIMATED),
                    )
                assertThat(rows.uids.map { it.uid to it.mAh }).containsExactly(10123 to 4.5, 10124 to 0.5, -2 to 0.25)
                // The cascade still finds the rows through the key.
                db.windowDao().pruneDetail(listOf(7))
                assertThat(long(db, "SELECT COUNT(*) FROM window_stat WHERE windowId = 7")).isEqualTo(0)
                assertThat(long(db, "SELECT COUNT(*) FROM window_stat")).isEqualTo(1)
            }
        }

    @Test
    fun sixToSevenPacksTheHistoryAndKeepsEveryEventItsIdAndItsTag() =
        runTest {
            helper.createDatabase(NAME, 4).use { v4 -> (recordsV4() + historyV4()).forEach(v4::execSQL) }
            val before = helper.runMigrationsAndValidate(NAME, 6, true, *Migrations.ALL).use(::historyRows)
            assertThat(before).hasSize(8)

            helper.runMigrationsAndValidate(NAME, 7, true, *Migrations.ALL).use { v7 ->
                assertThat(strings(v7, "SELECT name FROM sqlite_master WHERE name = 'history_event'")).isEmpty()
                // A chunk ends at each snapshot's mark (3 and 9), at the break after id 4 and with the last event.
                assertThat(strings(v7, "SELECT firstId || ':' || count FROM history_chunk ORDER BY firstId"))
                    .containsExactly("2:2", "4:1", "7:3", "10:2")
                    .inOrder()
                // Above the highest id ever given, 20, though that event is gone.
                assertThat(strings(v7, "SELECT id || ':' || nextEventId FROM history_counter")).containsExactly("0:21")
                assertThat(strings(v7, "SELECT tag || ':' || lastEventId FROM history_tag ORDER BY id"))
                    .containsExactly("*alarm*:4", "lock:11", "kernel only:0")
                    .inOrder()
            }

            migrated { db ->
                val events = db.historyDao().events(0, 0, 99, 0)
                assertThat(
                    events.map {
                        val e = it.event
                        listOf(
                            e.id,
                            e.bootSeq,
                            e.elapsedMs,
                            HistoryCodes.kindCode(e.type),
                            HistoryCodes.changeCode(e.change),
                            if (e.baseline) 1 else 0,
                            e.uid ?: "-",
                            e.tagId ?: "-",
                            e.value ?: "-",
                        ).joinToString("|")
                    },
                ).isEqualTo(before)
                assertThat(events.map { it.tag }).containsExactly(null, "*alarm*", "*alarm*", null, "lock", null, null, "lock").inOrder()
                // The span between the two marked snapshots still finds its events.
                assertThat(db.historyDao().events(1, 10 * NS_PER_S, 1, 20 * NS_PER_S).map { it.event.id }).containsExactly(4L)
                assertThat(db.historyDao().insertRead(listOf(event(3, 5)), emptyList())).isEqualTo(21)
                db.historyDao().deleteUnusedTags()
                assertThat(long(db, "SELECT COUNT(*) FROM history_tag")).isEqualTo(2)
            }
        }

    @Test
    fun sixToSevenSplitsALongRunOfEventsAcrossPages() =
        runTest {
            helper.createDatabase(NAME, 4).use { v4 ->
                // A mark at 1000 moves the chunks off the pages' bounds: the read of 8,192 rows ends inside a chunk.
                v4.execSQL(recordsV4().first())
                v4.execSQL("UPDATE snapshot SET historyEventId = 1000 WHERE id = 1")
                v4.execSQL(
                    "WITH RECURSIVE n(i) AS (SELECT 100 UNION ALL SELECT i + 1 FROM n WHERE i < 20099) " +
                        "INSERT INTO history_event SELECT i, 1 + i / 3000, i * 17, i % 60, i % 3, i % 2, " +
                        "CASE WHEN i % 4 = 0 THEN 10000 + i END, NULL, CASE WHEN i % 5 = 0 THEN -i END FROM n",
                )
            }
            val before = helper.runMigrationsAndValidate(NAME, 6, true, *Migrations.ALL).use(::historyRows)
            assertThat(before).hasSize(20_000)

            helper.runMigrationsAndValidate(NAME, 7, true, *Migrations.ALL).use { v7 ->
                assertThat(strings(v7, "SELECT firstId || ':' || count FROM history_chunk ORDER BY firstId"))
                    .containsExactly("100:901", "1001:4096", "5097:4096", "9193:4096", "13289:4096", "17385:2715")
                    .inOrder()
                assertThat(strings(v7, "SELECT nextEventId FROM history_counter")).containsExactly("20100")
                val decoded =
                    v7.query("SELECT firstId, count, data FROM history_chunk ORDER BY firstId").use { chunks ->
                        buildList {
                            while (chunks.moveToNext()) {
                                HistoryChunkCodec.decode(chunks.getInt(1), chunks.getBlob(2)).forEachIndexed { index, e ->
                                    add(
                                        listOf(
                                            chunks.getLong(0) + index,
                                            e.bootSeq,
                                            e.elapsedMs,
                                            e.typeCode,
                                            e.changeCode,
                                            if (e.baseline) 1 else 0,
                                            e.uid ?: "-",
                                            e.tagId ?: "-",
                                            e.value ?: "-",
                                        ).joinToString("|"),
                                    )
                                }
                            }
                        }
                    }
                assertThat(decoded).isEqualTo(before)
            }
        }

    @Test
    fun sixToSevenWithAnEmptyHistoryCountsOnAboveTheHighestMark() =
        runTest {
            helper.createDatabase(NAME, 4).use { v4 ->
                v4.execSQL(recordsV4().first())
                // The ring was cut empty after a snapshot that had marked id 50; no id was ever counted.
                v4.execSQL("UPDATE snapshot SET historyEventId = 50 WHERE id = 1")
            }
            helper.runMigrationsAndValidate(NAME, 6, true, *Migrations.ALL).close()
            helper.runMigrationsAndValidate(NAME, 7, true, *Migrations.ALL).use { v7 ->
                assertThat(strings(v7, "SELECT COUNT(*) FROM history_chunk")).containsExactly("0")
                assertThat(strings(v7, "SELECT nextEventId FROM history_counter")).containsExactly("51")
            }
            migrated { db -> assertThat(db.historyDao().insertRead(listOf(event(1, 5)), emptyList())).isEqualTo(51) }
        }

    @Test
    fun sixToSevenRefusesARowItCannotHoldAndLeavesVersion6() =
        runTest {
            val refused =
                listOf(
                    // A change code above two bits.
                    "INSERT INTO history_event VALUES (2, 1, 9500, $LEVEL, 4, 0, NULL, NULL, 80)" to "change code 4",
                    // A UID beyond an Int, which a cut would turn into another app.
                    "INSERT INTO history_event VALUES (2, 1, 9500, $WAKE_LOCK, $START, 0, 4294977419, NULL, NULL)" to "uid 4294977419",
                )
            refused.forEachIndexed { index, (row, reason) ->
                val name = "$NAME-refused-$index"
                helper.createDatabase(name, 4).use { v4 -> v4.execSQL(row) }
                helper.runMigrationsAndValidate(name, 6, true, *Migrations.ALL).close()
                val error = runCatching { helper.runMigrationsAndValidate(name, 7, true, *Migrations.ALL).close() }.exceptionOrNull()
                assertThat(generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString(" / ")).contains(reason)
                SQLiteDatabase
                    .openDatabase(
                        ApplicationProvider.getApplicationContext<Context>().getDatabasePath(name).path,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                    ).use { file ->
                        assertThat(file.version).isEqualTo(6)
                        file.rawQuery("SELECT COUNT(*) FROM history_event", null).use {
                            it.moveToFirst()
                            assertThat(it.getLong(0)).isEqualTo(1)
                        }
                        file.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'history_chunk'", null).use {
                            it.moveToFirst()
                            assertThat(it.getLong(0)).isEqualTo(0)
                        }
                    }
            }
        }

    /** Every `history_event` row as text, in id order, for a before-and-after comparison. */
    private fun historyRows(db: SupportSQLiteDatabase): List<String> =
        strings(
            db,
            "SELECT id || '|' || bootSeq || '|' || elapsedMs || '|' || type || '|' || change || '|' || baseline || '|' || " +
                "IFNULL(uid, '-') || '|' || IFNULL(tagId, '-') || '|' || IFNULL(value, '-') FROM history_event ORDER BY id",
        )

    /**
     * Version 4 history (the table is the same up to version 6): snapshots 1 and 2 mark ids 3 and
     * 9, ids 5 and 6 are missing, time steps back and the boot changes inside a run, and the
     * highest id ever given, 20, is deleted. One tag is used only by the kernel log.
     */
    private fun historyV4() =
        listOf(
            "UPDATE snapshot SET historyEventId = 3 WHERE id = 1",
            "UPDATE snapshot SET historyEventId = 9 WHERE id = 2",
            "INSERT INTO history_tag VALUES (5, '*alarm*')",
            "INSERT INTO history_tag VALUES (6, 'lock')",
            "INSERT INTO history_tag VALUES (7, 'kernel only')",
            "INSERT INTO history_event VALUES (2, 1, 9500, $LEVEL, $SET, 0, NULL, NULL, 80)",
            "INSERT INTO history_event VALUES (3, 1, 9900, $WAKE_LOCK, $START, 1, 10123, 5, NULL)",
            "INSERT INTO history_event VALUES (4, 1, 15001, $WAKE_LOCK, ${HistoryCodes.changeCode(HistoryChange.STOP)}, 0, 10123, 5, NULL)",
            "INSERT INTO history_event VALUES (7, 1, 21000, $LEVEL, $SET, 0, NULL, NULL, -3)",
            "INSERT INTO history_event VALUES (8, 1, 20990, $WAKE_LOCK, $START, 0, -1, 6, 9223372036854775807)",
            "INSERT INTO history_event VALUES (9, 2, 40, $LEVEL, $SET, 1, NULL, NULL, 79)",
            "INSERT INTO history_event VALUES (10, 2, 86400000000, $LEVEL, $SET, 0, 0, NULL, 78)",
            "INSERT INTO history_event VALUES (11, 2, 86400000001, $WAKE_LOCK, $START, 0, 10124, 6, NULL)",
            "INSERT INTO history_event VALUES (20, 2, 86400000002, $LEVEL, $SET, 0, NULL, NULL, 77)",
            "DELETE FROM history_event WHERE id = 20",
        )

    /** Every `window_stat` and `window_uid` row as text, for a before-and-after comparison. */
    private fun detail(db: SupportSQLiteDatabase): List<String> =
        strings(db, "SELECT 'stat|' || windowId || '|' || uid || '|' || keyId || '|' || value || '|' || provenance FROM window_stat") +
            strings(
                db,
                "SELECT 'uid|' || windowId || '|' || uid || '|' || componentId || '|' || processStateId || '|' || process || '|' || " +
                    "mAh || '|' || provenance FROM window_uid",
            )

    private fun strings(
        db: SupportSQLiteDatabase,
        query: String,
    ): List<String> =
        db.query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    /** More version 4 detail: windows 7 and 8 share keys, and a UID has several rows. */
    private fun detailV4() =
        listOf(
            "INSERT INTO stat_key VALUES (5, 'wifi')",
            "INSERT INTO stat_key VALUES (6, 'any')",
            "INSERT INTO window_stat VALUES (7, 10123, 5, 30, 2)",
            "INSERT INTO window_stat VALUES (7, -1, 4, 14, 0)",
            "INSERT INTO window_stat VALUES (8, 10123, 4, 77, 0)",
            "INSERT INTO window_uid VALUES (7, 10123, 4, 6, '', 4.5, 1)",
            "INSERT INTO window_uid VALUES (7, 10124, 4, 6, 'media', 0.5, 1)",
            "INSERT INTO window_uid VALUES (7, -2, 5, 6, '', 0.25, 3)",
        )

    /** Version 4 records of every kind, with the highest id of each table deleted. */
    private fun recordsV4() =
        listOf(
            "INSERT INTO snapshot (id, bootSeq, elapsedNs, wallMs, `trigger`, kind, sourceStatusJson, partial, countersPruned, " +
                "durationMs) VALUES (1, 1, 10000000000, 1793491200000, 'SCREEN_OFF', 'HEAVY', " +
                "'{\"S4\":\"off\",\"S5\":\"ok\",\"S7\":\"failed\"}', 0, 0, 0)",
            "INSERT INTO snapshot (id, bootSeq, elapsedNs, wallMs, `trigger`, kind, sourceStatusJson, partial, countersPruned, " +
                "durationMs, uptimeMs, privilegeMode) VALUES (2, 1, 20000000000, 1793491210000, 'SCREEN_ON', 'HEAVY', " +
                "'{\"S25\":\"ok\",\"S26\":\"ok\",\"S27\":\"partial\",\"S4\":\"skipped\",\"S5\":\"slept\"," +
                "\"S6\":\"ok\",\"S7\":\"ok\",\"S9\":\"ok\"}', 1, 0, 0, 15000, 2)",
            "INSERT INTO snapshot (id, bootSeq, elapsedNs, wallMs, `trigger`, kind, sourceStatusJson, partial, countersPruned, " +
                "durationMs) VALUES (3, 1, 25000000000, 1793491215000, 'SERVICE_STARTED', 'LIGHT', '{}', 0, 0, 0)",
            "INSERT INTO snapshot (id, bootSeq, elapsedNs, wallMs, `trigger`, kind, sourceStatusJson, partial, countersPruned, " +
                "durationMs) VALUES (4, 1, 26000000000, 1793491216000, 'LEVEL_CHANGED', 'HEAVY', " +
                "'{\"S5\":\"weird\",\"S99\":\"ok\"}', 0, 0, 0)",
            "INSERT INTO snapshot (id, bootSeq, elapsedNs, wallMs, `trigger`, kind, sourceStatusJson, partial, countersPruned, " +
                "durationMs) VALUES (9, 1, 29000000000, 1793491219000, 'PERIODIC_GONE', 'LIGHT', '{}', 0, 0, 0)",
            "DELETE FROM snapshot WHERE id = 9",
            "INSERT INTO session (id, kind, bootSeq, startElapsedNs, endElapsedNs, startWall, endWall, startSnapshotId, " +
                "endSnapshotId, startLevel, endLevel, chargeSource, maxTempDeciC, cycleEquivalents, closeReason, quality) " +
                "VALUES (5, 'CHARGE', 1, 10000000000, 20000000000, 1793491200000, 1793491210000, 1, 2, 40, 41, " +
                "'CHARGE_COUNTER', 310, 0.25, 'STATE_CHANGED', 1.0)",
            "INSERT INTO session (id, kind, bootSeq, startElapsedNs, startWall, startSnapshotId, startLevel, maxTempDeciC, " +
                "quality) VALUES (6, 'SCREEN_OFF', 1, 20000000000, 1793491210000, 2, 41, 300, 1.0)",
            "INSERT INTO session (id, kind, bootSeq, startElapsedNs, startWall, startLevel, maxTempDeciC, quality) " +
                "VALUES (9, 'DISCHARGE', 1, 0, 0, 50, 300, 1.0)",
            "DELETE FROM session WHERE id = 9",
            "INSERT INTO charge_detail (sessionId, plugType, minutesAbove80, minutesAbove38C) VALUES (5, 1, 0, 0)",
            "INSERT INTO window_result (id, kind, fromSnapshotId, toSnapshotId, screenOnMs, screenOffMs, quality, detailPruned) " +
                "VALUES (7, 'CONSECUTIVE', 1, 2, 0, 10000, 1.0, 0)",
            "INSERT INTO window_result (id, kind, sessionId, fromSnapshotId, toSnapshotId, measuredMah, chargeSource, " +
                "screenOnMs, screenOffMs, quality, detailPruned) VALUES (8, 'SESSION', 5, 1, 2, 12.5, 'LEVEL_DELTA', 0, 10000, 0.5, 0)",
            "INSERT INTO window_result (id, kind, fromSnapshotId, toSnapshotId, screenOnMs, screenOffMs, quality, detailPruned) " +
                "VALUES (9, 'REFERENCE', 1, 2, 0, 0, 1.0, 0)",
            "DELETE FROM window_result WHERE id = 9",
            "INSERT INTO stat_key VALUES (4, 'cpu')",
            "INSERT INTO window_stat VALUES (7, 10123, 4, 900, 0)",
        )

    /** Breakdowns, component rows and S4 snapshot rows store and read back on a migrated file. */
    private suspend fun s4StorageWorks(
        db: CoulombDatabase,
        window: WindowResultEntity,
    ) {
        val power = PowerColumns(cpu = 120, wifi = 30, foreground = 100, background = 20)
        val windowId =
            db.windowDao().insert(
                window.copy(id = 0, breakdown = Breakdown.BROKEN_DOWN),
                WindowRows(
                    uids = listOf(WindowUid(0, 10123, "all", "any", "", 4.2, Provenance.MODELED)),
                    components = listOf(WindowComponentEntity(0, 10123, power), WindowComponentEntity(0, -1, power)),
                ),
            )
        assertThat(db.windowDao().window(windowId)?.breakdown).isEqualTo(Breakdown.BROKEN_DOWN)
        assertThat(
            db
                .windowDao()
                .rows(windowId)
                .components
                .map { it.uid to it.power.toPower() },
        ).containsExactly(-1 to power.toPower(), 10123 to power.toPower())
            .inOrder()
        assertThat(long(db, "SELECT COUNT(*) FROM stat_key")).isEqualTo(4)
        db.windowDao().pruneDetail(listOf(windowId))
        assertThat(db.windowDao().rows(windowId).components).isEmpty()

        val snapshotId =
            db.snapshotDao().insert(
                db.snapshotDao().snapshot(2)!!.copy(id = 0, elapsedNs = 30 * NS_PER_S),
                SnapshotRows(componentPower = listOf(UidComponentPowerEntity(0, 10123, power))),
            )
        assertThat(
            db
                .snapshotDao()
                .rows(snapshotId)
                .componentPower
                .single()
                .power,
        ).isEqualTo(power)
    }

    private fun long(
        db: CoulombDatabase,
        query: String,
    ): Long =
        db.openHelper.readableDatabase.query(query).use {
            it.moveToFirst()
            it.getLong(0)
        }

    private suspend fun migrated(check: suspend (CoulombDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db =
            CoulombDatabase.configured(
                Room.databaseBuilder(context, CoulombDatabase::class.java, context.getDatabasePath(NAME).path),
            )
        try {
            check(db)
        } finally {
            db.close()
        }
    }

    /** Version 1 rows: two snapshots marking history ids 2 and 3, and window 7 between them. */
    private fun snapshotsAndWindow() =
        listOf(
            "INSERT INTO snapshot (id, bootSeq, elapsedNs, wallMs, `trigger`, kind, sourceStatusJson, partial, countersPruned, " +
                "durationMs, historyEventId) VALUES (1, 1, 10000000000, 1793491200000, 'SCREEN_OFF', 'HEAVY', '{}', 0, 0, 0, 2)",
            "INSERT INTO snapshot (id, bootSeq, elapsedNs, wallMs, `trigger`, kind, sourceStatusJson, partial, countersPruned, " +
                "durationMs, historyEventId) VALUES (2, 1, 20000000000, 1793491210000, 'SCREEN_ON', 'HEAVY', '{}', 0, 0, 0, 3)",
            "INSERT INTO window_result (id, kind, fromSnapshotId, toSnapshotId, screenOnMs, screenOffMs, quality, detailPruned) " +
                "VALUES (7, 'CONSECUTIVE', 1, 2, 0, 10000, 1.0, 0)",
        )

    /** Version 1 detail of window 7, with every provenance, and the history the snapshots mark. */
    private fun detailAndHistory() =
        listOf(
            "INSERT INTO window_uid VALUES (7, 10123, 'cpu', 'ANY', '', 4.5, 'MODELED')",
            "INSERT INTO window_uid VALUES (7, -2, 'cpu', 'ANY', '', 0.5, 'DERIVED')",
            "INSERT INTO window_wake VALUES (40, 7, NULL, 'qcom_rx_wakelock', 'KERNEL', 1200, 3, NULL)",
            "INSERT INTO window_wake VALUES (41, 7, 10123, '*job*/sync', 'PARTIAL', 900, 1, 0)",
            "INSERT INTO window_wake VALUES (42, 7, 10124, '*job*/sync', 'PARTIAL', 300, 2, 300)",
            "INSERT INTO window_stat VALUES (7, 10123, 'job_runtime_ms', 4000, 'MEASURED')",
            "INSERT INTO window_stat VALUES (7, 10123, 'alarm_wakeups', 3, 'ESTIMATED')",
            "INSERT INTO window_stat VALUES (7, -1, 'alarm_wakeups', 14, 'MEASURED')",
            "INSERT INTO history_tag VALUES (5, '*alarm*')",
            "INSERT INTO history_event VALUES (2, 1, 9500000000, $LEVEL, $SET, 0, NULL, NULL, 80)",
            "INSERT INTO history_event VALUES (3, 1, 15001000000, $WAKE_LOCK, $START, 1, 10123, 5, NULL)",
        )

    private companion object {
        const val NAME = "migration-test"
        val LEVEL = HistoryCodes.kindCode(HistoryKind.BATTERY_LEVEL)
        val WAKE_LOCK = HistoryCodes.kindCode(HistoryKind.WAKE_LOCK)
        val SET = HistoryCodes.changeCode(HistoryChange.SET)
        val START = HistoryCodes.changeCode(HistoryChange.START)
    }
}

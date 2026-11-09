// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.dao.KernelRows
import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.dao.WindowStat
import io.github.akane599.coulomb.core.database.dao.WindowWake
import io.github.akane599.coulomb.core.database.entity.HistoryCodes
import io.github.akane599.coulomb.core.database.entity.KernelReadEntity
import io.github.akane599.coulomb.core.database.entity.KmsgSpan
import io.github.akane599.coulomb.core.database.entity.PowerColumns
import io.github.akane599.coulomb.core.database.entity.UidComponentPowerEntity
import io.github.akane599.coulomb.core.database.entity.WindowComponentEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowUid
import io.github.akane599.coulomb.core.model.Breakdown
import io.github.akane599.coulomb.core.model.PrivilegeMode
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
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
            Room
                .databaseBuilder(context, CoulombDatabase::class.java, context.getDatabasePath(NAME).path)
                .addMigrations(*Migrations.ALL)
                .build()
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

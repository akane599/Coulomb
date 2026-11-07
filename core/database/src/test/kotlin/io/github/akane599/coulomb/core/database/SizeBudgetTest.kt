// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.dao.NewHistoryEvent
import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.dao.WindowStat
import io.github.akane599.coulomb.core.database.dao.WindowWake
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.WakeStatEntity
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Measures stored bytes per row and projects the database after a year at the rates of ADR-0015's
 * budget, against SPEC 7.7's 150 MB. The rates come from the S25 Ultra fixture and SPEC 7. The
 * stress test (`CollectorStressTest`) projects the year at the rates measured on the phone; this
 * test guards the row widths that projection relies on (ADR-0021).
 */
@RunWith(AndroidJUnit4::class)
class SizeBudgetTest : DatabaseTest() {
    private val random = Random(7)

    private fun bytes(): Long = long("PRAGMA page_count") * long("PRAGMA page_size")

    private suspend fun bytesPer(
        rows: Int,
        write: suspend () -> Unit,
    ): Double {
        val before = bytes()
        write()
        return (bytes() - before).toDouble() / rows
    }

    /** The fixture's mix: about half the items name a holder, from some thousands of distinct tags. */
    private fun historyBatch(
        startNs: Long,
        size: Int,
    ): List<NewHistoryEvent> =
        List(size) { i ->
            val tagged = random.nextInt(2) == 0
            NewHistoryEvent(
                bootSeq = 1,
                elapsedMs = (startNs + i * HISTORY_STEP_NS) / 1_000_000,
                type = if (tagged) HistoryKind.WAKE_LOCK else HistoryKind.BATTERY_TEMPERATURE,
                change = if (tagged) HistoryChange.START else HistoryChange.SET,
                baseline = false,
                uid = if (tagged) 10_000 + random.nextInt(300) else null,
                tag = if (tagged) "*job*/com.example.app${random.nextInt(TAGS)}/.sync.Worker" else null,
                value = if (tagged) null else 300L + random.nextInt(100),
            )
        }

    private fun heavyCounters(): SnapshotRows =
        SnapshotRows(
            uidPower =
                List(UID_POWER_ROWS) { i ->
                    UidPowerEntity(
                        0,
                        10_000 + i / 5,
                        COMPONENTS[i % 5],
                        UidPowerEntity.ANY_STATE,
                        random.nextDouble(50.0),
                        Provenance.MODELED,
                    )
                },
            wakeStats =
                List(WAKE_ROWS) { i ->
                    WakeStatEntity(
                        0,
                        0,
                        10_000 + i % 300,
                        "*job*/com.example.app$i/.sync.Worker",
                        WakeKind.PARTIAL,
                        60_000L + i,
                        4,
                        null,
                        null,
                    )
                },
        )

    private fun windowDetail(): WindowRows =
        WindowRows(
            uids =
                List(DETAIL_ROWS) { i ->
                    WindowUidEntity(
                        0,
                        10_000 + i / 3,
                        COMPONENTS[i % 3],
                        UidPowerEntity.ANY_STATE,
                        WindowUidEntity.WHOLE_UID,
                        1.5,
                        Provenance.MODELED,
                    )
                },
            wakes =
                List(DETAIL_ROWS) { i ->
                    WindowWake(10_000 + i, "*job*/com.example.app$i/.sync.Worker", WakeKind.PARTIAL, 1_200L + i, 3, 900)
                },
            stats = List(DETAIL_ROWS) { i -> WindowStat(10_000 + i / 2, STAT_KEYS[i % 2], 4_000L + i, Provenance.MEASURED) },
        )

    /** Aggregates are written only by compaction, which frees the samples' pages; this writes them directly. */
    private fun insertAggregates(count: Int) {
        val database = db.openHelper.writableDatabase
        database.beginTransaction()
        try {
            repeat(count) { m ->
                database.execSQL(
                    "INSERT INTO sample_aggregate VALUES (60, 1, ?, ?, 6, 80, 79, 4000000, 3990000, " +
                        "-400000, -250000, -90000, 4100, 300, 310, 320, 0.5, 0)",
                    arrayOf<Any>(m * 60 * NS_PER_S, 1_700_000_000_000L + m * 60_000L),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    @Test
    fun aYearFitsTheBudget() =
        runTest {
            val history =
                bytesPer(HISTORY_ROWS) {
                    repeat(
                        HISTORY_ROWS / BATCH,
                    ) { b -> db.historyDao().insertRead(historyBatch(b * BATCH * HISTORY_STEP_NS, BATCH), emptyList()) }
                }
            val samples = bytesPer(MEASURED_ROWS) { repeat(MEASURED_ROWS) { db.sampleDao().insert(sample(1, it * 30 * NS_PER_S)) } }
            val aggregates = bytesPer(MEASURED_ROWS) { insertAggregates(MEASURED_ROWS) }
            val snapshots =
                bytesPer(MEASURED_ROWS) {
                    repeat(MEASURED_ROWS) { db.snapshotDao().insert(snapshot(elapsedS = it + 1L).copy(sourceStatusJson = HEAVY_STATUS)) }
                }
            val counters =
                bytesPer(SNAPSHOTS * (UID_POWER_ROWS + WAKE_ROWS)) {
                    repeat(SNAPSHOTS) { db.snapshotDao().insert(snapshot(elapsedS = 100_000L + it), heavyCounters()) }
                }
            val windows = bytesPer(MEASURED_ROWS) { repeat(MEASURED_ROWS) { db.windowDao().insert(window(1, 2)) } }
            val detail = bytesPer(WINDOWS * DETAIL_ROWS * 3) { repeat(WINDOWS) { db.windowDao().insert(window(1, 2), windowDetail()) } }

            val measured =
                mapOf(
                    "history event" to history,
                    "sample" to samples,
                    "aggregate" to aggregates,
                    "snapshot" to snapshots,
                    "snapshot counter" to counters,
                    "window" to windows,
                    "window detail" to detail,
                )
            val projectedMb =
                (
                    history * HISTORY_EVENTS_KEPT +
                        samples * RAW_SAMPLES_KEPT +
                        aggregates * AGGREGATES_KEPT +
                        snapshots * SNAPSHOTS_PER_YEAR +
                        counters * COUNTER_ROWS_KEPT +
                        windows * WINDOWS_PER_YEAR +
                        detail * DETAIL_ROWS_KEPT
                ) / MB + OTHER_TABLES_MB
            println("bytes per row: " + measured.entries.joinToString { (k, v) -> "$k %.1f".format(v) })
            println("projected after a year: %.1f MB".format(projectedMb))

            // Each row's size, with some room: a wider row here is a budget change (ADR-0021).
            assertThat(history).isLessThan(32.0)
            assertThat(detail).isLessThan(60.0)
            assertThat(projectedMb).isLessThan(BUDGET_MB)
        }

    private companion object {
        const val TAGS = 2_000
        const val BATCH = 1_000
        const val HISTORY_ROWS = 40_000
        const val HISTORY_STEP_NS = 1_300_000_000L
        const val MEASURED_ROWS = 5_000
        const val SNAPSHOTS = 10
        const val UID_POWER_ROWS = 1_000
        const val WAKE_ROWS = 900
        const val WINDOWS = 100
        const val DETAIL_ROWS = 60
        val COMPONENTS = listOf("cpu", "screen", "wifi", "mobile_radio", "wakelock")
        val STAT_KEYS = listOf("alarm_wakeups", "job_runtime_ms")

        /** A heavy snapshot's outcome for each source it read. */
        val HEAVY_STATUS =
            (listOf(1, 2) + (4..13) + listOf(15, 16, 17, 19, 21, 22)).joinToString(",", "{", "}") { "\"S$it\":\"ok\"" }

        // ADR-0015 budget: rows kept after a year of use.
        const val HISTORY_EVENTS_KEPT = 1_400_000.0 // 30 days at about 1,950 stored events an hour
        const val RAW_SAMPLES_KEPT = 14 * 5_000.0
        const val AGGREGATES_KEPT = 180 * 900.0 + 365 * 24.0
        const val SNAPSHOTS_PER_YEAR = 365 * 110.0 // heavy ones and the light ones that mark something
        const val COUNTER_ROWS_KEPT = 20 * 2_000.0
        const val WINDOWS_PER_YEAR = 365 * 220.0

        // Detail rows (UID, wakelock and counter): for good, a day window's 220 and 1.5 discharge
        // sessions' 220 a day; 30 days, about 100 consecutive windows' and 2 charge sessions' 60 a day.
        const val DETAIL_ROWS_KEPT = 365 * (220 + 1.5 * 220) + 30 * 102 * 60.0
        const val OTHER_TABLES_MB = 5.0
        const val MB = 1_000_000.0 // SPEC 7.7 writes MB (ADR-0021)
        const val BUDGET_MB = 150.0
    }
}

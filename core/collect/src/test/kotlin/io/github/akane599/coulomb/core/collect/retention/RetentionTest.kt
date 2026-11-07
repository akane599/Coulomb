// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.retention

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.engine.BlobStore
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.dao.NewHistoryEvent
import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.dao.WindowStat
import io.github.akane599.coulomb.core.database.entity.DeviceStatEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetentionTest {
    private lateinit var db: CoulombDatabase
    private val hourNs = 3_600_000_000_000L
    private val wall0 = 1_793_491_200_000L

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CoulombDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    private suspend fun heavy(
        hour: Int,
        blob: String? = null,
        bootSeq: Int = 1,
        kind: SnapshotKind = SnapshotKind.HEAVY,
        historyEventId: Long? = null,
    ): Long =
        db.snapshotDao().insert(
            SnapshotEntity(
                bootSeq = bootSeq,
                elapsedNs = hour * hourNs,
                wallMs = wall0 + hour * hourNs / 1_000_000,
                trigger = SnapshotTrigger.SCREEN_OFF,
                kind = kind,
                levelPct = 50,
                chargeUah = null,
                currentRaw = null,
                voltageMv = null,
                tempDeciC = null,
                plugType = 0,
                status = null,
                statsStartWall = wall0,
                statsRealtimeMs = hour * hourNs / 1_000_000,
                historyCursorMs = null,
                historyEventId = historyEventId,
                sourceStatusJson = "{}",
                blobPath = blob,
                partial = false,
                countersPruned = false,
                durationMs = 0,
            ),
            if (kind ==
                SnapshotKind.HEAVY
            ) {
                SnapshotRows(deviceStats = listOf(DeviceStatEntity(0, "screen_on_ms", hour + 1L)))
            } else {
                SnapshotRows()
            },
        )

    private suspend fun window(
        from: Long,
        to: Long,
        kind: WindowKind,
    ): Long =
        db.windowDao().insert(
            WindowResultEntity(0, kind, null, from, to, 1.0, ChargeSource.CHARGE_COUNTER, 1.0, 0.0, 0, 3_600_000, 0, 1.0, false),
            WindowRows(stats = listOf(WindowStat(10_123, "alarm_wakeups", 2, Provenance.MEASURED))),
        )

    @Test
    fun afterThirtyDaysConsecutiveWindowsAndUnneededSnapshotsGo() =
        runTest {
            val boot = db.deviceDao().enterBoot(1, "fp", wall0, 0)
            val a = heavy(0)
            val light = heavy(1, kind = SnapshotKind.LIGHT)
            val c = heavy(2)
            val d = heavy(3)
            val referenced = heavy(4)
            val historyMark = heavy(5, kind = SnapshotKind.LIGHT, historyEventId = 1)
            val unmarked = heavy(6, kind = SnapshotKind.LIGHT)
            val recent = heavy(DAYS_31_H - 12)
            val consecutive = window(a, c, WindowKind.CONSECUTIVE)
            val day = window(a, d, WindowKind.DAY)
            db.snapshotDao().moveAutoReference(AutoReference.BEFORE_RESET, referenced, wall0)
            val now = DAYS_31_H * hourNs
            Retention(db, Blobs()).pruneCounters(boot, now)

            val report = Retention(db, Blobs()).run(boot, now)

            // The cut is 30 days back: hour 24. The day window and what it, the reference and the
            // history ring's cut need stay; so does the latest snapshot.
            assertThat(db.windowDao().window(consecutive)).isNull()
            assertThat(db.windowDao().rows(day).stats).hasSize(1)
            val left = listOf(a, light, c, d, referenced, historyMark, unmarked, recent).filter { db.snapshotDao().snapshot(it) != null }
            assertThat(left).containsExactly(a, d, referenced, historyMark, recent)
            assertThat(report.windowsDeleted).isEqualTo(1)
            assertThat(report.snapshotsDeleted).isEqualTo(3)
        }

    /** The snapshot each cut was made at survives it, so the next day's run and reads still find their bounds. */
    @Test
    fun theHistoryRingStaysReadableAcrossDailyRuns() =
        runTest {
            val boot = db.deviceDao().enterBoot(1, "fp", wall0, 0)
            val history = db.historyDao()
            val marks = mutableListOf<Long>()
            for (hour in listOf(0, 30, 40, DAYS_31_H - 2)) {
                val event =
                    NewHistoryEvent(1, hour * hourNs / 1_000_000, HistoryKind.BATTERY_LEVEL, HistoryChange.SET, false, null, null, 50)
                marks += heavy(hour + 1, kind = SnapshotKind.LIGHT, historyEventId = history.insertRead(listOf(event), emptyList()))
            }

            suspend fun hoursStored() = history.events(1, 0, 1, Long.MAX_VALUE).map { (it.event.elapsedMs * 1_000_000 / hourNs).toInt() }

            suspend fun left() = marks.filter { db.snapshotDao().snapshot(it) != null }

            Retention(db, Blobs()).run(boot, DAYS_31_H * hourNs)
            assertThat(hoursStored()).containsExactly(30, 40, DAYS_31_H - 2).inOrder()
            assertThat(history.events(1, 24 * hourNs, 1, Long.MAX_VALUE)).hasSize(3)
            // Hour 1 is before the cut (hour 24) but marks the ring's oldest kept event.
            assertThat(left()).containsExactlyElementsIn(marks)

            Retention(db, Blobs()).run(boot, (DAYS_31_H + 24) * hourNs)
            assertThat(hoursStored()).containsExactly(DAYS_31_H - 2)
            assertThat(left()).containsExactly(marks[2], marks[3])
        }

    @Test
    fun countersGoTwoHoursBackButThePinnedSnapshotsKeepThem() =
        runTest {
            val boot = db.deviceDao().enterBoot(1, "fp", wall0, 0)
            val ids = (0..10).map { heavy(it) }
            db.snapshotDao().moveAutoReference(AutoReference.BEFORE_RESET, ids[2], wall0)

            val pruned = Retention(db, Blobs()).pruneCounters(boot, 10 * hourNs)

            val kept =
                ids.filter {
                    db
                        .snapshotDao()
                        .rows(it)
                        .deviceStats
                        .isNotEmpty()
                }
            assertThat(kept).containsExactly(ids[2], ids[8], ids[9], ids[10])
            assertThat(pruned).isEqualTo(7)
        }

    @Test
    fun aCounterCutReachesBackIntoTheEarlierBoot() =
        runTest {
            db.deviceDao().enterBoot(1, "fp", wall0, 0)
            val ids = (0..10).map { heavy(it) }
            // Boot 2 is an hour old, so 2 h back is 1 h before boot 1 was last seen (10 h).
            val boot = db.deviceDao().enterBoot(2, "fp", wall0 + 12 * hourNs / 1_000_000, 0)

            val pruned = Retention(db, Blobs()).pruneCounters(boot, hourNs)

            val kept =
                ids.filter {
                    db
                        .snapshotDao()
                        .rows(it)
                        .deviceStats
                        .isNotEmpty()
                }
            assertThat(kept).containsExactly(ids[9], ids[10])
            assertThat(pruned).isEqualTo(9)
        }

    @Test
    fun blobsPastTheCapGoOldestFirstAndUnnamedFilesGo() =
        runTest {
            val boot = db.deviceDao().enterBoot(1, "fp", wall0, 0)
            val mb = 1024L * 1024
            val blobs = Blobs(mutableListOf("snapshots/a" to 100 * mb, "snapshots/b" to 60 * mb, "snapshots/orphan" to mb))
            val a = heavy(1, "snapshots/a")
            val b = heavy(2, "snapshots/b")

            val report = Retention(db, blobs).run(boot, 3 * hourNs)

            assertThat(blobs.stored.map { it.first }).containsExactly("snapshots/b")
            assertThat(db.snapshotDao().snapshot(a)?.blobPath).isNull()
            assertThat(db.snapshotDao().snapshot(b)?.blobPath).isEqualTo("snapshots/b")
            assertThat(report.blobsDeleted).isEqualTo(2)
        }

    private companion object {
        const val DAYS_31_H = 31 * 24
    }

    private class Blobs(
        val stored: MutableList<Pair<String, Long>> = mutableListOf(),
    ) : BlobStore {
        override fun write(
            baseName: String,
            parts: Map<String, ByteArray>,
        ): String = baseName.also { stored += it to parts.values.sumOf { p -> p.size.toLong() } }

        override fun delete(baseNames: Collection<String>) {
            stored.removeAll { it.first in baseNames }
        }

        override fun list(): List<Pair<String, Long>> = stored.toList()
    }
}

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
import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.database.entity.DeviceStatEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
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
    ): Long =
        db.snapshotDao().insert(
            SnapshotEntity(
                bootSeq = 1,
                elapsedNs = hour * hourNs,
                wallMs = wall0 + hour * hourNs / 1_000_000,
                trigger = SnapshotTrigger.SCREEN_OFF,
                kind = SnapshotKind.HEAVY,
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
                historyEventId = null,
                sourceStatusJson = "{}",
                blobPath = blob,
                partial = false,
                countersPruned = false,
                durationMs = 0,
            ),
            SnapshotRows(deviceStats = listOf(DeviceStatEntity(0, "screen_on_ms", hour + 1L))),
        )

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

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.database.CoulombDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The exports, the archives and "Delete all data" as Settings uses them (SPEC 14.5, ADR-0062). Room
 * refuses the main thread, which is the test's, so the transfer runs on the real IO dispatcher as
 * it does in the app.
 */
@RunWith(AndroidJUnit4::class)
class DataTransferTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: CoulombDatabase

    @Before
    fun open() {
        db = CoulombDatabase.inMemory(context)
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO boot (bootSeq, rawBootCount, fingerprint, firstWallMs, firstElapsedNs, historyOffsetMs, imported) " +
                "VALUES (1, 7, 'lab', 1000, 5, NULL, 0)",
        )
    }

    @After
    fun close() = db.close()

    private fun boots(database: CoulombDatabase): Long =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM boot").use {
            it.moveToFirst()
            it.getLong(0)
        }

    @Test
    fun aBundleBecomesAnArchiveBesideThisPhonesData() =
        runTest {
            val transfer = DataTransfer(context, db, AppDispatchers.production())
            val out = ByteArrayOutputStream()
            transfer.exportBundle(out, nowWallMs = 1_800_000_000_000, settings = "{}")
            assertThat(context.cacheDir.resolve("transfer").exists()).isFalse()

            val result = transfer.import(ByteArrayInputStream(out.toByteArray()))
            assertThat(result).isInstanceOf(ImportResult.Imported::class.java)
            val imported = result as ImportResult.Imported
            assertThat(imported.settings).isEqualTo("{}")
            assertThat(transfer.archives.value).containsExactly(imported.archive)
            assertThat(imported.archive.exportedAtWallMs).isEqualTo(1_800_000_000_000)
            // This phone's own tables did not grow by the import.
            assertThat(boots(db)).isEqualTo(1)
            transfer.open(imported.archive.id)!!.let { archive ->
                assertThat(boots(archive)).isEqualTo(1)
                archive.close()
            }

            // The same bundle a second time is told apart, and a foreign file is refused with nothing left behind.
            assertThat(
                transfer.import(ByteArrayInputStream(out.toByteArray())),
            ).isEqualTo(ImportResult.Refused(ImportProblem.ALREADY_IMPORTED))
            assertThat(
                transfer.import(ByteArrayInputStream("nothing".toByteArray())),
            ).isEqualTo(ImportResult.Refused(ImportProblem.NOT_A_BUNDLE))
            assertThat(context.noBackupFilesDir.resolve("archives").list()).asList().containsExactly(imported.archive.id)

            transfer.deleteArchive(imported.archive.id)
            assertThat(transfer.archives.value).isEmpty()
        }

    @Test
    fun deleteAllDataLeavesNoRowNoSnapshotFileAndNoArchive() =
        runTest {
            val transfer = DataTransfer(context, db, AppDispatchers.production())
            val out = ByteArrayOutputStream()
            transfer.exportBundle(out, nowWallMs = 1_800_000_000_000, settings = "{}")
            transfer.import(ByteArrayInputStream(out.toByteArray()))
            val snapshots = context.noBackupFilesDir.resolve("snapshots").apply { mkdirs() }
            snapshots.resolve("1-5").writeText("x")

            transfer.deleteAll()

            assertThat(boots(db)).isEqualTo(0)
            assertThat(snapshots.exists()).isFalse()
            assertThat(transfer.archives.value).isEmpty()
            assertThat(context.noBackupFilesDir.resolve("archives").exists()).isFalse()
        }

    @Test
    fun anExportIsNamedByItsKindThePhoneAndTheTime() {
        val dispatchers = AppDispatchers.production()
        val name = DataTransfer(context, db, dispatchers).fileName("bundle", 1_800_000_000_000)
        assertThat(name).matches("coulomb-bundle-[A-Za-z0-9-]+-2027011[45]-\\d{4}\\.zip")
    }
}

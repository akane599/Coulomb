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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A captured database through the whole path of SPEC 14.5: exported as a bundle, imported as an
 * archive, and every table counted on both sides. It shows that the import's checks (ADR-0062 §11)
 * take what a phone really stored, which a synthetic database cannot.
 *
 *     ./gradlew :core:data:testDebugUnitTest --tests '*CaptureBundleTest' -Pcoulomb.capture=<capture dir>
 *
 * Skipped without a capture, as in CI.
 */
@RunWith(AndroidJUnit4::class)
class CaptureBundleTest {
    @Test
    fun aCapturedDatabaseComesBackTableForTable() =
        runTest {
            val capture = System.getProperty("coulomb.capture")?.takeIf { it.isNotBlank() }?.let(::File)
            assumeTrue("pass -Pcoulomb.capture", capture != null)
            val context = ApplicationProvider.getApplicationContext<Context>()
            val target = context.noBackupFilesDir.resolve(CoulombDatabase.FILE_NAME)
            for (suffix in listOf("", "-wal", "-shm")) {
                val file = File(checkNotNull(capture), CoulombDatabase.FILE_NAME + suffix)
                if (file.isFile) file.copyTo(File(target.path + suffix), overwrite = true)
            }
            val db = CoulombDatabase.build(context)
            try {
                val transfer = DataTransfer(context, db, AppDispatchers.production())
                val bundle = File(context.cacheDir, "capture-bundle.zip")
                transfer.exportBundle(bundle.outputStream(), 1_800_000_000_000, "{}")
                val result = transfer.import(bundle.inputStream())
                assertThat(result).isInstanceOf(ImportResult.Imported::class.java)
                val archive = checkNotNull(transfer.open((result as ImportResult.Imported).archive.id))
                try {
                    val counted = counts(db)
                    assertThat(counts(archive)).isEqualTo(counted)
                    println("bundle ${bundle.length()} bytes; ${counted.size} tables, ${counted.values.sum()} rows")
                } finally {
                    archive.close()
                }
            } finally {
                db.close()
            }
        }

    private fun counts(database: CoulombDatabase): Map<String, Long> {
        val reader = database.openHelper.readableDatabase
        val tables = ArrayList<String>()
        reader
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'")
            .use { cursor -> while (cursor.moveToNext()) tables += cursor.getString(0) }
        return tables.filter { it != "android_metadata" }.associateWith { table ->
            reader.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
        }
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.akane599.coulomb.core.analytics.LevelSpan
import io.github.akane599.coulomb.core.analytics.ScreenRates
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.model.SessionKind
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Coulomb's own screen-off rate for every closed screen-off session of a validation capture, for
 * protocol B (SPEC 16.6, ADR-0049). It opens a copy of the capture's database the way the app does
 * (`CoulombDatabase.build`, so older schemas migrate), reads each session's level changes through
 * `HistoryDao` and [LevelDropsCache], and rates the session with [ScreenRates.combine]. Only the
 * session's own drops count here, as Now counts them; `tools/validation/report.py` compares the
 * result with its reference.
 *
 *     ./gradlew :core:data:testDebugUnitTest --tests '*CaptureRatesTest' \
 *         -Pcoulomb.capture=<capture dir> -Pcoulomb.captureOut=<rates.json>
 *
 * With `-Pcoulomb.captureDb=<file>` it also writes the migrated database there, checkpointed into
 * one file, so a migration can be checked on real data before a phone runs it.
 *
 * Skipped without a capture, as in CI.
 */
@RunWith(AndroidJUnit4::class)
class CaptureRatesTest {
    @Test
    fun screenOffRatesOfACapture() =
        runTest {
            val capture = System.getProperty("coulomb.capture")?.takeIf { it.isNotBlank() }?.let(::File)
            val out = System.getProperty("coulomb.captureOut")?.takeIf { it.isNotBlank() }?.let(::File)
            assumeTrue("pass -Pcoulomb.capture and -Pcoulomb.captureOut", capture != null && out != null)
            val context = ApplicationProvider.getApplicationContext<Context>()
            val target = context.noBackupFilesDir.resolve(CoulombDatabase.FILE_NAME)
            for (suffix in listOf("", "-wal", "-shm")) {
                val file = File(checkNotNull(capture), CoulombDatabase.FILE_NAME + suffix)
                if (file.isFile) file.copyTo(File(target.path + suffix), overwrite = true)
            }
            val db = CoulombDatabase.build(context)
            try {
                val history = db.historyDao()
                val rows =
                    db.sessionDao().overlapping(listOf(SessionKind.SCREEN_OFF), Long.MIN_VALUE).filter(LevelDropsCache::timed).map { s ->
                        val end = checkNotNull(s.endElapsedNs)
                        val changes =
                            if (history.readAfter(
                                    s.bootSeq,
                                    end,
                                )
                            ) {
                                history.levelChanges(s.bootSeq, s.startElapsedNs, s.bootSeq, end)
                            } else {
                                null
                            }
                        val drops = changes?.let { LevelDropsCache.of(s, it) }
                        val span =
                            LevelSpan(false, s.startWall, checkNotNull(s.endWall), s.startLevel, checkNotNull(s.endLevel), 1.0, drops)
                        val rate = ScreenRates.combine(listOf(span))
                        val points = drops?.let { it.firstLevel - it.lastLevel } ?: (s.startLevel - checkNotNull(s.endLevel))
                        val hours = (drops?.let { it.lastWallMs - it.firstWallMs } ?: (checkNotNull(s.endWall) - s.startWall)) / MS_PER_HOUR
                        "\"${s.id}\": {\"timed\": ${drops != null}, \"points\": $points, \"hours\": $hours, \"rate\": ${rate?.pctPerHour}}"
                    }
                checkNotNull(out).writeText(rows.joinToString(",\n  ", prefix = "{\n  ", postfix = "\n}\n"))
                db.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)")
                    .use { it.moveToFirst() }
            } finally {
                db.close()
            }
            System.getProperty("coulomb.captureDb")?.takeIf { it.isNotBlank() }?.let { target.copyTo(File(it), overwrite = true) }
        }

    private companion object {
        const val MS_PER_HOUR = 3_600_000.0
    }
}

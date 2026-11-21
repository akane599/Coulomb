// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.akane599.coulomb.core.database.CoulombDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the rules find in a validation capture (ADR-0053: P5 is checked on stored phone data). It
 * opens a copy of the capture's database the way the app does, loads the rule context as of the
 * capture's latest snapshot and writes the context's size and every finding to a text file.
 *
 * Skipped unless given `-Pcoulomb.capture=<raw-dir>` and `-Pcoulomb.captureOut=<file>`; optional
 * `-Pcoulomb.captureUid=<uid>` names Coulomb's own UID on that phone.
 */
@RunWith(AndroidJUnit4::class)
class CaptureInsightsTest {
    @Test
    fun findingsOfACapture() =
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
                val ports =
                    object : InsightPorts {
                        override val ownUid = System.getProperty("coulomb.captureUid")?.toIntOrNull() ?: -1

                        override suspend fun capacityMah() = CAPACITY_MAH

                        override fun lastUpdateWallMs(uid: Int): Long? = null
                    }
                val loader = RuleContextLoader(db.snapshotDao(), db.windowDao(), db.sessionDao(), db.sampleDao(), ports)
                val now = checkNotNull(db.snapshotDao().latest()).wallMs
                val engine = InsightEngine(Thresholds.bundled())
                val ctx = loader.load(now, engine.minScreenOffMs)
                val found = engine.evaluate(ctx)
                val lines =
                    listOf(
                        "now=$now day=${ctx.day?.span} screenOffMs=${ctx.day?.screenOffMs} apps=${ctx.day?.apps?.size}",
                        "scan wifi=${ctx.day?.wifiScanMs} ble=${ctx.day?.bleScanMs} signal=${ctx.day?.signal} self=${ctx.day?.self}",
                        "tagHolds=${ctx.tagHolds.size} screenOff=${ctx.screenOffSessions.size} discharge=${ctx.dischargeWindows.size} " +
                            "charge=${ctx.chargeSessions.size} temps=${ctx.temperatures}",
                        "health=${ctx.health.size} trends=${ctx.trends.size}",
                    ) +
                        ctx.screenOffSessions.takeLast(SHOWN).map { "session $it" } +
                        ctx.dischargeWindows.map { "window $it" } +
                        ctx.chargeSessions.map { "charge ${it.copy(samples = it.samples.take(1))} samples=${it.samples.size}" } +
                        found.map {
                            "FOUND ${it.rule.code} ${it.severity} ${it.subject?.key} window=${it.windowId} ${it.evidence.toJson()}"
                        }
                checkNotNull(out).writeText(lines.joinToString("\n", postfix = "\n"))
            } finally {
                db.close()
            }
        }

    private companion object {
        /** The S25 Ultra's design capacity; the capture's own estimate is not settled yet. */
        const val CAPACITY_MAH = 5000.0
        const val SHOWN = 8
    }
}

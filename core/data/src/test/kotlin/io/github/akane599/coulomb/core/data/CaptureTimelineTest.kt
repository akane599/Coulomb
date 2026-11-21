// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.database.CoulombDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * The Timeline and the Night Report read from a validation capture (ADR-0053: P5 is checked on
 * stored phone data). It opens a copy of the capture's database the way the app does and writes,
 * for the last day, the last week and each night, what every track holds and how long the read took.
 *
 * Skipped unless given `-Pcoulomb.capture=<raw-dir>` and `-Pcoulomb.captureOut=<file>`.
 */
@RunWith(AndroidJUnit4::class)
class CaptureTimelineTest {
    @Test
    fun timelineOfACapture() =
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
                val dispatchers = AppDispatchers(Dispatchers.IO, Dispatchers.Default)
                val timelines =
                    TimelineRepository(db.historyDao(), db.snapshotDao(), db.sampleDao(), db.sessionDao(), db.deviceDao(), dispatchers)
                val nights = NightRepository(db.sessionDao(), db.snapshotDao(), db.historyDao(), dispatchers).nights.first()
                val now = checkNotNull(db.snapshotDao().latest()).wallMs
                val lines = ArrayList<String>()

                suspend fun span(
                    name: String,
                    from: Long,
                    to: Long,
                ) {
                    val timeline: Timeline
                    val ms = measureTimeMillis { timeline = timelines.read(from, to) }
                    lines += "$name ${(to - from) / MINUTE_MS} min, read in $ms ms: level=${timeline.level.size} " +
                        "(${timeline.level.firstOrNull()?.levelPct}..${timeline.level.lastOrNull()?.levelPct}) " +
                        "current=${timeline.current.size} temperature=${timeline.temperature.size} " +
                        "unknown=${timeline.unknown.sumOf { it.durationMs } / MINUTE_MS} min in ${timeline.unknown.size}"
                    timeline.tracks.forEach { (track, spans) ->
                        if (spans.isNotEmpty()) {
                            lines += "  $track spans=${spans.size} on=${spans.sumOf { it.durationMs } / MINUTE_MS} min " +
                                "named=${spans.count { it.tag != null }} values=${spans.groupingBy { it.value }.eachCount()}"
                        }
                    }
                }
                span("day", now - DAY_MS, now)
                span("week", now - WEEK_MS, now)
                // SPEC 10.5: which apps follow the day's most frequent wakeup reasons (WakeCorrelation).
                val day = timelines.read(now - DAY_MS, now)
                val stretches = day.tracks.getValue(TimelineTrack.AWAKE)
                lines += "awake stretches=${stretches.size} with a reason=${stretches.count { it.tag != null }}"
                stretches
                    .mapNotNull { it.tag }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(SHOWN)
                    .forEach { (reason, _) ->
                        val by = FOLLOWS.map { WakeCorrelation.apps(day, reason, emptyMap(), it) }
                        val apps = by.last()
                        lines += "  reason ${reason.take(REASON_CHARS)}: wakes=${apps.wakes} known=${apps.knownMs / MINUTE_MS} min " +
                            "unclaimed within ${FOLLOWS.map { it / SECOND_MS }} s=${by.map { it.unclaimed }} unnamed=${apps.unnamed} " +
                            "apps=${apps.apps.size} top=${apps.apps.take(TOP).map { it.wakes }}"
                    }
                nights.take(SHOWN).forEach { night ->
                    lines += "night ${night.id}: ${night.durationMs / MINUTE_MS} min ${night.startLevel}->${night.endLevel} " +
                        "rate=${night.ratePctPerHour} charged=${night.charged} sleep=${night.deepSleepShare} " +
                        "doze=${night.deepDozeReached} after=${night.deepDozeAfterMs}"
                    val timeline = timelines.night(night.id)
                    lines +=
                        "  its timeline: level=${timeline?.level?.size} unknown=${timeline?.unknown?.sumOf { it.durationMs }?.div(
                            MINUTE_MS,
                        )} min " +
                        timeline?.tracks?.filterValues { it.isNotEmpty() }?.map { (track, spans) -> "$track=${spans.size}" }
                }
                checkNotNull(out).writeText(lines.joinToString("\n", postfix = "\n"))
            } finally {
                db.close()
            }
        }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L
        const val WEEK_MS = 7 * DAY_MS
        const val SHOWN = 6
        const val REASON_CHARS = 40
        const val TOP = 3
        const val SECOND_MS = 1_000L
        val FOLLOWS = listOf(1_000L, 5_000L, 10_000L, 60_000L, Long.MAX_VALUE / 2)
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import io.github.akane599.coulomb.core.model.dumpsys.AlarmDump
import io.github.akane599.coulomb.core.model.dumpsys.AlarmTagStats
import io.github.akane599.coulomb.core.model.dumpsys.AlarmType
import io.github.akane599.coulomb.core.model.dumpsys.AppAlarmStats
import io.github.akane599.coulomb.core.model.dumpsys.JobPackageStats
import io.github.akane599.coulomb.core.model.dumpsys.JobSchedulerDump
import io.github.akane599.coulomb.core.model.dumpsys.JobStatsWindow
import io.github.akane599.coulomb.core.model.dumpsys.PendingAlarm
import io.github.akane599.coulomb.core.model.dumpsys.RegisteredJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledNowTest {
    @Test
    fun theAppsJobsWhatTheyWaitForAndHowTheyStopped() {
        val jobs = checkNotNull(ScheduledNow.of(UID, PACKAGE, labJobs(), null).jobs)
        assertEquals(3, jobs.registered)
        assertEquals(1, jobs.ready)
        assertEquals(listOf("CONNECTIVITY" to 2, "CHARGING" to 1), jobs.waitingFor)
        assertEquals(listOf("thermal" to 1), jobs.restrictedBy)
        assertEquals("FREQUENT", jobs.bucket)
        assertEquals(listOf("timeout" to 5, "successful_finish" to 2), jobs.stops)
    }

    @Test
    fun theAppsAlarmsWaitingAndDeliveredWakingOrNot() {
        val alarms = checkNotNull(ScheduledNow.of(UID, PACKAGE, null, labAlarms()).alarms)
        assertEquals(ScheduledAlarms(pending = 2, pendingWakeups = 1, nextInMs = 240_000, delivered = 31, deliveredWakeups = 12), alarms)
        assertEquals(19, alarms.deliveredQuiet)
    }

    /** A clone in another profile: same package, another uid. The per-uid count holds; the split and the next do not. */
    @Test
    fun aPackageAnotherProfileAlsoRunsCountsOnlyThisUidsAlarms() {
        val clone = AppAlarmStats(1_010_123, PACKAGE, runningMs = 0, wakeups = 3, active = false, alarms = emptyList())
        val dump = labAlarms().let { it.copy(appStats = it.appStats.orEmpty() + clone) }
        val alarms = checkNotNull(ScheduledNow.of(UID, PACKAGE, null, dump).alarms)
        assertEquals(2, alarms.pending)
        assertNull(alarms.pendingWakeups)
        assertNull(alarms.nextInMs)
        assertEquals(12, alarms.deliveredWakeups)
    }

    @Test
    fun aDumpThatCouldNotBeReadWholeIsUnknownNotNone() {
        val now = ScheduledNow.of(UID, PACKAGE, JobSchedulerDump(null, null), AlarmDump(1, 1, null, null, null))
        assertNull(now.jobs)
        assertNull(now.alarms)
    }
}

internal fun labJobs() =
    JobSchedulerDump(
        statsWindows =
            listOf(
                JobStatsWindow(false, 3_600_000, 1_800_000, listOf(stats(mapOf("timeout" to 9)))),
                JobStatsWindow(true, 1_800_000, 1_800_000, listOf(stats(mapOf("successful_finish" to 2, "timeout" to 5)))),
            ),
        registeredJobs =
            listOf(
                RegisteredJob(UID, PACKAGE, "FREQUENT", listOf("CONNECTIVITY", "CHARGING"), emptyList(), ready = false),
                RegisteredJob(UID, PACKAGE, "FREQUENT", listOf("CONNECTIVITY"), listOf("thermal"), ready = false),
                RegisteredJob(UID, PACKAGE, "FREQUENT", emptyList(), emptyList(), ready = true),
                RegisteredJob(10_515, "com.northwind.mail", "ACTIVE", listOf("IDLE"), emptyList(), ready = false),
            ),
    )

private fun stats(stops: Map<String, Int>) = JobPackageStats(UID, PACKAGE, null, null, null, stops)

internal fun labAlarms() =
    AlarmDump(
        nowElapsedMs = 1_000_000,
        nowRtcMs = 0,
        pending =
            listOf(
                PendingAlarm(AlarmType.ELAPSED_WAKEUP, PACKAGE, 1_240_000, "*walarm*:app.halcyon.video.SYNC"),
                PendingAlarm(AlarmType.RTC, PACKAGE, 4_600_000, "*alarm*:app.halcyon.video.CLEANUP"),
                PendingAlarm(AlarmType.RTC_WAKEUP, "com.northwind.mail", 1_100_000, null),
            ),
        pendingPerUid = mapOf(UID to 2),
        appStats =
            listOf(
                AppAlarmStats(
                    UID,
                    PACKAGE,
                    runningMs = 4_000,
                    wakeups = 12,
                    active = false,
                    alarms =
                        listOf(
                            AlarmTagStats("*walarm*:app.halcyon.video.SYNC", 3_000, 12, 12, 240_000, false),
                            AlarmTagStats("*alarm*:app.halcyon.video.CLEANUP", 1_000, 0, 19, 60_000, false),
                        ),
                ),
            ),
    )

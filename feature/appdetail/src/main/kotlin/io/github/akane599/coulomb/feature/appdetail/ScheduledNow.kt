// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import androidx.compose.runtime.Immutable
import io.github.akane599.coulomb.core.model.dumpsys.AlarmDump
import io.github.akane599.coulomb.core.model.dumpsys.JobSchedulerDump

/** The state of the on-demand read of [ScheduledNow]. */
internal sealed interface ScheduledState {
    data object Reading : ScheduledState

    data object Failed : ScheduledState

    data class Read(
        val now: ScheduledNow,
    ) : ScheduledState
}

/**
 * What one app has scheduled right now (S13, S10), read when the user asks (ADR-0068). A part is
 * null when its dump could not be read whole.
 */
@Immutable
internal data class ScheduledNow(
    val jobs: ScheduledJobs?,
    val alarms: ScheduledAlarms?,
) {
    companion object {
        fun of(
            uid: Int,
            packageName: String?,
            jobs: JobSchedulerDump?,
            alarms: AlarmDump?,
        ): ScheduledNow = ScheduledNow(jobs?.let { ScheduledJobs.of(uid, it) }, alarms?.let { ScheduledAlarms.of(uid, packageName, it) })
    }
}

/** The app's registered jobs and what holds them back, and how its recent jobs stopped. */
@Immutable
internal data class ScheduledJobs(
    val registered: Int,
    val ready: Int,
    /** Per constraint `JobStatus.dumpConstraints` names, the waiting jobs that lack it; most first. */
    val waitingFor: List<Pair<String, Int>>,
    /** Per restriction, such as `thermal`, the jobs it holds back; most first. */
    val restrictedBy: List<Pair<String, Int>>,
    /** The app's standby bucket, as the job scheduler has it; null without a job. */
    val bucket: String?,
    /** How its jobs stopped in the job scheduler's current statistics window, most first. */
    val stops: List<Pair<String, Int>>,
) {
    companion object {
        fun of(
            uid: Int,
            dump: JobSchedulerDump,
        ): ScheduledJobs? {
            val mine = dump.registeredJobs?.filter { it.sourceUid == uid } ?: return null
            val waiting = mine.filter { !it.ready }
            val current =
                dump.statsWindows
                    ?.lastOrNull { it.current }
                    ?.packages
                    ?.filter { it.uid == uid }
                    .orEmpty()
            return ScheduledJobs(
                registered = mine.size,
                ready = mine.size - waiting.size,
                waitingFor = waiting.flatMap { it.unsatisfiedConstraints }.counted(),
                restrictedBy = waiting.flatMap { it.restrictions }.counted(),
                bucket = mine.firstOrNull()?.standbyBucket,
                stops =
                    current
                        .flatMap { it.stopReasons.entries }
                        .groupBy({ it.key }, { it.value })
                        .map { (reason, counts) -> reason to counts.sum() }
                        .sortedByDescending { it.second },
            )
        }
    }
}

/** The app's alarms waiting to fire and those delivered since boot, waking or not. */
@Immutable
internal data class ScheduledAlarms(
    val pending: Int,
    val pendingWakeups: Int,
    /** Until the soonest pending alarm, in ms; null without one or without the dump's clock. */
    val nextInMs: Long?,
    val delivered: Int,
    val deliveredWakeups: Int,
) {
    /** Deliveries that did not wake the phone: S5 counts only the waking ones. */
    val deliveredQuiet: Int get() = (delivered - deliveredWakeups).coerceAtLeast(0)

    companion object {
        fun of(
            uid: Int,
            packageName: String?,
            dump: AlarmDump,
        ): ScheduledAlarms? {
            val stats = dump.appStats?.filter { it.uid == uid } ?: return null
            val packages = stats.map { it.packageName }.toSet() + listOfNotNull(packageName)
            val pending = dump.pending?.filter { it.packageName in packages }.orEmpty()
            val now = dump.nowElapsedMs
            return ScheduledAlarms(
                pending = pending.size,
                pendingWakeups = pending.count { it.type?.wakeup == true },
                nextInMs = now?.let { pending.minOfOrNull { alarm -> alarm.whenElapsedMs }?.minus(it)?.coerceAtLeast(0) },
                delivered = stats.sumOf { app -> app.alarms.sumOf { it.count } },
                deliveredWakeups = stats.sumOf { it.wakeups },
            )
        }
    }
}

private fun List<String>.counted(): List<Pair<String, Int>> =
    groupingBy {
        it
    }.eachCount().entries.map { it.key to it.value }.sortedByDescending { it.second }

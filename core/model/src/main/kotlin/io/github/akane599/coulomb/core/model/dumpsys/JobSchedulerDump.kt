// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.dumpsys

/**
 * The parts of `dumpsys jobscheduler` (S13) Coulomb reads, as `JobSchedulerService.dumpInternal`
 * prints them (frameworks/base apex/jobscheduler/service/java/com/android/server/job/
 * JobSchedulerService.java, android-16.0.0_r4 L5957-6322, android-17.0.0_r1 L6371-6729). A part is
 * null when it was missing or could not be trusted whole. The `Job history` lines are not read:
 * the S7 battery history carries the same starts and stops with escaped names (ADR-0013).
 */
public data class JobSchedulerDump(
    /** Per-app job statistics in consecutive windows, oldest first; the last is the current one. */
    val statsWindows: List<JobStatsWindow>?,
    /** Every scheduled job and what holds it back, in the platform's order. */
    val registeredJobs: List<RegisteredJob>?,
)

/**
 * One `JobPackageTracker.DataSet` (apex/jobscheduler/service/java/com/android/server/job/
 * JobPackageTracker.java L318-385, the same file in both tags). The platform keeps four finished
 * windows of about 30 minutes; the current one merges the latest finished window with the running one.
 */
public data class JobStatsWindow(
    /** `Current stats` rather than `Historical stats`. */
    val current: Boolean,
    /** Elapsed milliseconds from the window's start to the dump; null when the platform printed none. */
    val startMsBeforeDump: Long?,
    val durationMs: Long,
    val packages: List<JobPackageStats>,
)

/** One app's jobs in a [JobStatsWindow]. Null counts were not printed: zero, and under 0.5% of the window. */
public data class JobPackageStats(
    val uid: Int,
    val packageName: String,
    val pending: JobStateCount?,
    val active: JobStateCount?,
    /** Runs while the app was the top app. */
    val activeTop: JobStateCount?,
    /**
     * Stops by `JobParameters.getInternalReasonCodeDescription`, such as `successful_finish`,
     * `canceled`, [TIMEOUT_ABANDONED] or `unknown:-1`, in the platform's order.
     */
    val stopReasons: Map<String, Int>,
) {
    public companion object {
        /** The stop reason of a job that timed out without calling `jobFinished` (Android 16). */
        public const val TIMEOUT_ABANDONED: String = "timeout_abandoned"
    }
}

/** How often jobs entered a state in a window, and for how long. */
public data class JobStateCount(
    val count: Int,
    /** Share of the window spent in the state, rounded; null when it rounds to 0. */
    val percentOfWindow: Int?,
)

/** One `Registered jobs` entry: who the job works for and why it is not running. */
public data class RegisteredJob(
    val sourceUid: Int,
    val sourcePackage: String,
    /** `JobStatus.bucketName`: ACTIVE, WORKING_SET, FREQUENT, RARE, NEVER, RESTRICTED or EXEMPTED. */
    val standbyBucket: String,
    /**
     * Constraints not met, as `JobStatus.dumpConstraints` names them (android-16.0.0_r4
     * L2729-2774, android-17.0.0_r1 L3155-3200), such as CHARGING, CONNECTIVITY or WITHIN_QUOTA.
     */
    val unsatisfiedConstraints: List<String>,
    /** Restrictions holding the job back, as internal stop reason descriptions such as `thermal`. */
    val restrictions: List<String>,
    /** The job could run now. */
    val ready: Boolean,
)

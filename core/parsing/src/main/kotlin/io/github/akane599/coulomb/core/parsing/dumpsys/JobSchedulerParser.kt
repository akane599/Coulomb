// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.dumpsys

import io.github.akane599.coulomb.core.model.dumpsys.JobPackageStats
import io.github.akane599.coulomb.core.model.dumpsys.JobSchedulerDump
import io.github.akane599.coulomb.core.model.dumpsys.JobStateCount
import io.github.akane599.coulomb.core.model.dumpsys.JobStatsWindow
import io.github.akane599.coulomb.core.model.dumpsys.RegisteredJob
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.parsing.ParseContext
import io.github.akane599.coulomb.core.parsing.batterystats.parseFormattedUid
import io.github.akane599.coulomb.core.parsing.io.Line
import io.github.akane599.coulomb.core.parsing.text.Durations
import io.github.akane599.coulomb.core.parsing.text.TextDump
import java.io.InputStream

/**
 * `dumpsys jobscheduler` (S13) into a [JobSchedulerDump]: the `Registered jobs` list and the
 * `JobPackageTracker` statistics windows (JobSchedulerService.java `dumpInternal`,
 * android-16.0.0_r4 L5957-6322, android-17.0.0_r1 L6371-6729). Never throws on bad input.
 *
 * Job names carry app text that is not escaped: a namespace is only trimmed, may hold line breaks
 * and is rejected over 1000 characters (JobSchedulerService.java `validateNamespace`, 16
 * L4995-5008, 17 L5317-5330), and extras print as the app set them. The service prints through an
 * `IndentingPrintWriter`, which indents every line of a printed string (IndentingPrintWriter.java
 * L199-253, both tags), so app text inside a job entry never reaches column 0.
 *
 * Two places still let app text add lines:
 * - Inside the job list, a namespace printed two spaces in can add lines two or four spaces in. So
 *   the list is kept only when its `JOB` lines match the count printed above it, and a job whose
 *   `Source`, `Unsatisfied constraints`, `Standby bucket`, `Restricted due to` or `Ready` line is
 *   missing or appears twice is dropped and reported.
 * - `Job history` drops the indent to 0 and prints each event's tag, which holds the namespace
 *   (JobPackageTracker.java `dumpHistory` L582-630, both tags), so its lines can look like any
 *   heading. It comes after the statistics windows, and `Current stats` is always the last window
 *   (`dump` L536-553). So nothing is read as a heading once `Current stats` has ended; such a
 *   heading is reported and ignored.
 */
public object JobSchedulerParser {
    public const val SOURCE: String = "dumpsys.jobscheduler"

    public fun parse(input: InputStream): Parsed<JobSchedulerDump> {
        val ctx = ParseContext(SOURCE)
        val dump = TextDump(input, ctx, SOURCE)
        val walk = JobWalk(ctx)
        val clean = dump.forEachLine(walk::read)
        val complete = walk.finish(dump.bytesRead) && clean && dump.lostLines == 0
        return Parsed(walk.result(), ctx.report(dump.bytesRead, complete))
    }
}

private enum class JobSection { OTHER, REGISTERED, WINDOW }

private enum class JobField { SOURCE, UNSATISFIED, BUCKET, RESTRICTED, READY }

/** The fields of one `JOB` entry, each with the number of times its line appeared. */
private class JobBlock {
    val seen = IntArray(JobField.entries.size)
    var uid: Int? = null
    var pkg: String? = null
    var unsatisfied: List<String>? = null
    var bucket: String? = null
    var restricted: List<String>? = null
    var ready: Boolean? = null

    fun build(): RegisteredJob? {
        val values = listOf(uid, pkg, bucket, unsatisfied, restricted, ready)
        if (seen.any { it != 1 } || values.any { it == null }) return null
        return RegisteredJob(
            sourceUid = checkNotNull(uid),
            sourcePackage = checkNotNull(pkg),
            standbyBucket = checkNotNull(bucket),
            unsatisfiedConstraints = checkNotNull(unsatisfied),
            restrictions = checkNotNull(restricted),
            ready = checkNotNull(ready),
        )
    }
}

private class PackageBuilder(
    val uid: Int,
    val name: String,
) {
    var lines = 0
    var counts: MatchResult? = null
    var reasons: Map<String, Int> = emptyMap()

    fun build(): JobPackageStats {
        fun state(group: String): JobStateCount? {
            val groups = counts?.groups ?: return null
            val count = groups["${group}c"]?.value?.toIntOrNull() ?: return null
            return JobStateCount(count, groups["${group}p"]?.value?.toIntOrNull())
        }
        return JobPackageStats(uid, name, state("p"), state("a"), state("t"), reasons)
    }
}

private class WindowBuilder(
    val current: Boolean,
    val startMsBeforeDump: Long?,
    val durationMs: Long,
) {
    val packages = ArrayList<JobPackageStats>()

    fun build() = JobStatsWindow(current, startMsBeforeDump, durationMs, packages.toList())
}

private class JobWalk(
    private val ctx: ParseContext,
) {
    private var section = JobSection.OTHER
    private var problemsWhileReading = 0

    private var registeredHeadings = 0
    private var declaredJobs = 0
    private var jobLines = 0
    private var sawNone = false
    private val jobs = ArrayList<RegisteredJob>()
    private var block: JobBlock? = null
    private var unreadableJobs = 0
    private var firstUnreadableJob = -1L
    private var registeredTrusted = true

    private var currentHeadings = 0
    private var historicalHeadings = 0
    private var pastWindows = false
    private var lateHeadings = 0
    private var firstLateHeading = -1L
    private val windows = ArrayList<JobStatsWindow>()
    private var window: WindowBuilder? = null
    private var pkg: PackageBuilder? = null
    private var windowsTrusted = true

    fun read(line: Line) {
        val text = line.text
        if (text.isNotEmpty() && text[0] != ' ') {
            endSection(line.offset)
            heading(text, line.offset)
            return
        }
        val indent = text.indexOfFirst { it != ' ' }.let { if (it < 0) text.length else it }
        when (section) {
            JobSection.REGISTERED -> registeredLine(line, indent)
            JobSection.WINDOW -> if (text.isEmpty()) endSection(line.offset) else windowLine(line, indent)
            JobSection.OTHER -> Unit
        }
    }

    private fun problem(
        offset: Long,
        message: String,
    ) {
        problemsWhileReading++
        ctx.issue(offset, JobSchedulerParser.SOURCE, message)
    }

    private fun heading(
        text: String,
        offset: Long,
    ) {
        if (pastWindows) {
            if (REGISTERED_HEADING.matches(text) || WINDOW_HEADING.matches(text)) {
                if (lateHeadings++ == 0) firstLateHeading = offset
            }
            return
        }
        REGISTERED_HEADING.matchEntire(text)?.let {
            if (++registeredHeadings == 1) {
                declaredJobs = it.groupValues[1].toInt()
                section = JobSection.REGISTERED
            }
            return
        }
        val match = WINDOW_HEADING.matchEntire(text) ?: return
        val current = match.groupValues[1] == "Current"
        if (current) currentHeadings++ else historicalHeadings++
        val startText = match.groupValues[2]
        val start = if (startText == NONE) null else Durations.parseMs(startText)?.let { -it }
        val duration = Durations.parseMs(match.groupValues.last())
        if (duration == null || (start == null && startText != NONE)) {
            windowsTrusted = false
            return problem(offset, "unreadable stats window heading")
        }
        window = WindowBuilder(current, start, duration)
        section = JobSection.WINDOW
    }

    private fun endSection(offset: Long) {
        when (section) {
            JobSection.REGISTERED -> endRegistered(offset)
            JobSection.WINDOW -> endWindow(offset)
            JobSection.OTHER -> Unit
        }
        section = JobSection.OTHER
    }

    private fun registeredLine(
        line: Line,
        indent: Int,
    ) {
        val text = line.text
        when {
            text.startsWith(JOB_PREFIX) -> {
                endBlock(line.offset)
                jobLines++
                block = JobBlock()
            }

            text == NO_JOBS -> {
                sawNone = true
            }

            indent == FIELD_INDENT -> {
                block?.let { field(it, text) }
            }
        }
    }

    private fun field(
        b: JobBlock,
        text: String,
    ) {
        when {
            text.startsWith(SOURCE_PREFIX) -> {
                b.seen[JobField.SOURCE.ordinal]++
                val match = SOURCE_LINE.matchEntire(text)
                b.uid = match?.groupValues?.get(1)?.let(::parseFormattedUid)
                b.pkg = match?.groupValues?.get(2)
            }

            text.startsWith(UNSATISFIED_PREFIX) -> {
                b.seen[JobField.UNSATISFIED.ordinal]++
                b.unsatisfied = constraints(text.substring(UNSATISFIED_PREFIX.length))
            }

            text.startsWith(BUCKET_PREFIX) -> {
                b.seen[JobField.BUCKET.ordinal]++
                b.bucket = text.substring(BUCKET_PREFIX.length).also { if (it !in BUCKETS) ctx.unknown(NO_NAME_BUCKET) }
            }

            text.startsWith(RESTRICTED_PREFIX) -> {
                b.seen[JobField.RESTRICTED.ordinal]++
                b.restricted = restrictions(text.substring(RESTRICTED_PREFIX.length))
            }

            text.startsWith(READY_PREFIX) -> {
                b.seen[JobField.READY.ordinal]++
                b.ready = text.substring(READY_PREFIX.length).substringBefore(' ').toBooleanStrictOrNull()
            }
        }
    }

    /** ` CHARGING CONNECTIVITY [0x...]`, or nothing when every constraint is met. */
    private fun constraints(text: String): List<String>? {
        val names = text.substringBefore(" [0x").split(' ').filter { it.isNotEmpty() }
        if (names.any { !CONSTRAINT_NAME.matches(it) }) return null
        names.filter { it !in CONSTRAINTS }.forEach { ctx.unknown("constraint.$it") }
        return names
    }

    /** ` none.` or ` thermal quota.` */
    private fun restrictions(text: String): List<String>? {
        if (!text.endsWith(".")) return null
        val names = text.dropLast(1).split(' ').filter { it.isNotEmpty() }
        if (names == listOf("none")) return emptyList()
        if (names.any { !REASON_NAME.matches(it) }) return null
        names.filter { it !in STOP_REASONS && !it.startsWith("unknown:") }.forEach { ctx.unknown("restriction.$it") }
        return names
    }

    private fun endBlock(offset: Long) {
        val b = block ?: return
        block = null
        val job = b.build()
        if (job != null) {
            jobs += job
        } else {
            if (unreadableJobs++ == 0) firstUnreadableJob = offset
        }
    }

    private fun endRegistered(offset: Long) {
        endBlock(offset)
        val matches = if (declaredJobs == 0) jobLines == 0 && sawNone else jobLines == declaredJobs
        if (!matches) {
            registeredTrusted = false
            problem(offset, "JOB lines do not match the registered count")
        }
    }

    private fun windowLine(
        line: Line,
        indent: Int,
    ) {
        val text = line.text
        // The concurrency line and the blank line before the first app.
        if (text.startsWith(MAX_CONCURRENCY) || text == "  ") return
        when {
            indent == 2 -> {
                packageLine(line)
            }

            indent == FIELD_INDENT && pkg != null -> {
                stateLine(line, checkNotNull(pkg))
            }

            else -> {
                unreadableWindow(line.offset)
            }
        }
    }

    private fun packageLine(line: Line) {
        endPackage(line.offset)
        val match = PACKAGE_LINE.matchEntire(line.text) ?: return unreadableWindow(line.offset)
        val uid = parseFormattedUid(match.groupValues[1]) ?: return unreadableWindow(line.offset)
        pkg = PackageBuilder(uid, match.groupValues[2])
    }

    /** The counts line, then the stop reasons line when there were stops. */
    private fun stateLine(
        line: Line,
        p: PackageBuilder,
    ) {
        val text = line.text.substring(FIELD_INDENT)
        when (p.lines++) {
            0 -> p.counts = COUNTS.matchEntire(text) ?: return unreadableWindow(line.offset)
            1 -> p.reasons = stopReasons(text) ?: return unreadableWindow(line.offset)
            else -> unreadableWindow(line.offset)
        }
    }

    /** `2x canceled, 7x successful_finish`; null when any entry is unreadable. */
    private fun stopReasons(text: String): Map<String, Int>? {
        val entries = text.split(", ").map { STOP_REASON.matchEntire(it) }
        if (entries.any { it == null }) return null
        val reasons = LinkedHashMap<String, Int>()
        for (match in entries.filterNotNull()) {
            val name = match.groupValues[2]
            val count = match.groupValues[1].toIntOrNull()
            if (count == null || name in reasons) return null
            if (name !in STOP_REASONS && !name.startsWith("unknown:")) ctx.unknown("stop.$name")
            reasons[name] = count
        }
        return reasons
    }

    private fun unreadableWindow(offset: Long) {
        if (windowsTrusted) problem(offset, "unreadable stats window line")
        windowsTrusted = false
        pkg = null
    }

    private fun endPackage(offset: Long) {
        val p = pkg ?: return
        pkg = null
        if (p.counts == null) return unreadableWindow(offset)
        window?.packages?.add(p.build())
    }

    private fun endWindow(offset: Long) {
        endPackage(offset)
        window?.let {
            windows += it.build()
            if (it.current) pastWindows = true
        }
        window = null
    }

    /** Records what is missing; true when nothing required is. */
    fun finish(offset: Long): Boolean {
        endSection(offset)
        val problems = ArrayList<String>()
        when {
            registeredHeadings == 0 -> problems += "no Registered jobs"
            registeredHeadings > 1 -> problems += "Registered jobs printed more than once"
        }
        if (unreadableJobs > 0) ctx.issue(firstUnreadableJob, JobSchedulerParser.SOURCE, "registered jobs not read: $unreadableJobs")
        // App text in Job history; the real sections were read before it.
        if (lateHeadings > 0) ctx.issue(firstLateHeading, JobSchedulerParser.SOURCE, "headings after Current stats ignored: $lateHeadings")
        when {
            currentHeadings == 0 -> problems += "no Current stats"
            currentHeadings > 1 -> problems += "Current stats printed more than once"
            historicalHeadings > MAX_HISTORICAL -> problems += "more Historical stats than the platform keeps"
        }
        problems.forEach { ctx.issue(offset, JobSchedulerParser.SOURCE, it) }
        return problems.isEmpty() && problemsWhileReading == 0 && unreadableJobs == 0
    }

    fun result(): JobSchedulerDump {
        val windowsRead = windowsTrusted && currentHeadings == 1 && historicalHeadings <= MAX_HISTORICAL
        return JobSchedulerDump(
            // Printed newest historical window first, then the current one.
            statsWindows = if (windowsRead) windows.filter { !it.current }.reversed() + windows.filter { it.current } else null,
            registeredJobs = jobs.toList().takeIf { registeredHeadings == 1 && registeredTrusted },
        )
    }

    private companion object {
        const val JOB_PREFIX = "  JOB "
        const val NO_JOBS = "  None."
        const val FIELD_INDENT = 4
        const val SOURCE_PREFIX = "    Source: uid="
        const val UNSATISFIED_PREFIX = "    Unsatisfied constraints:"
        const val BUCKET_PREFIX = "    Standby bucket: "
        const val RESTRICTED_PREFIX = "    Restricted due to:"
        const val READY_PREFIX = "    Ready: "
        const val MAX_CONCURRENCY = "  Max concurrency: "
        const val NO_NAME_BUCKET = "bucket.<other>"
        const val NONE = "--"

        /** `JobPackageTracker.NUM_HISTORY` (L41) windows, of which the newest is merged into the current one. */
        const val MAX_HISTORICAL = 4

        val REGISTERED_HEADING = Regex("Registered ([0-9]{1,9}) jobs:")
        val WINDOW_HEADING = Regex("(Current|Historical) stats at [0-9-]{1,40} \\((\\S+)\\) over (\\S+):")
        val SOURCE_LINE = Regex("    Source: uid=([0-9a-z]{1,16}) user=-?[0-9]{1,10} pkg=(\\S+)")
        val PACKAGE_LINE = Regex("  ([0-9a-z]{1,16}) / (\\S+):")
        val COUNTS =
            Regex(
                "(?:(?:(?<pp>[0-9]{1,3})% )?(?<pc>[0-9]{1,10})x pending ?)?" +
                    "(?:(?:(?<ap>[0-9]{1,3})% )?(?<ac>[0-9]{1,10})x active ?)?" +
                    "(?:(?:(?<tp>[0-9]{1,3})% )?(?<tc>[0-9]{1,10})x active-top)?" +
                    "(?: \\((?:pending|active|active-top)\\))*",
            )
        val STOP_REASON = Regex("([0-9]{1,10})x ([a-z_]{1,40}(?::-?[0-9]{1,11})?)")
        val CONSTRAINT_NAME = Regex("[A-Z_]{1,40}")
        val REASON_NAME = Regex("[a-z_]{1,40}(?::-?[0-9]{1,11})?")

        /** `JobStatus.dumpConstraints`, the same names in both tags. */
        val CONSTRAINTS =
            setOf(
                "CHARGING",
                "BATTERY_NOT_LOW",
                "STORAGE_NOT_LOW",
                "TIMING_DELAY",
                "DEADLINE",
                "IDLE",
                "CONNECTIVITY",
                "FLEXIBILITY",
                "CONTENT_TRIGGER",
                "DEVICE_NOT_DOZING",
                "BACKGROUND_NOT_RESTRICTED",
                "PREFETCH",
                "WITHIN_QUOTA",
            )

        /** `JobStatus.bucketName` (16 L2887-2899, 17 L3313-3325). */
        val BUCKETS = setOf("ACTIVE", "WORKING_SET", "FREQUENT", "RARE", "NEVER", "RESTRICTED", "EXEMPTED")

        /**
         * `JobParameters.getInternalReasonCodeDescription` (apex/jobscheduler/framework/java/android/
         * app/job/JobParameters.java, 16 L169-187, 17 L177-196); 17 adds `battery_saver`. Anything
         * else prints as `unknown:<code>`.
         */
        val STOP_REASONS =
            setOf(
                "canceled",
                "constraints",
                "preempt",
                "timeout",
                "device_idle",
                "thermal",
                "restricted_bucket",
                "uninstall",
                "data_cleared",
                "rtc_updated",
                "successful_finish",
                "user_ui_stop",
                "anr",
                JobPackageStats.TIMEOUT_ABANDONED,
                "battery_saver",
            )
    }
}

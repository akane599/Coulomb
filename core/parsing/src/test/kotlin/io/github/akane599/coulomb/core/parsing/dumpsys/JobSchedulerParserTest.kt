// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.dumpsys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.model.dumpsys.JobPackageStats
import io.github.akane599.coulomb.core.model.dumpsys.JobStateCount
import io.github.akane599.coulomb.core.model.dumpsys.RegisteredJob
import io.github.akane599.coulomb.core.parsing.Fixtures
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class JobSchedulerParserTest {
    private fun parse(bytes: ByteArray) = JobSchedulerParser.parse(ByteArrayInputStream(bytes))

    private fun parse(text: String) = parse(text.encodeToByteArray())

    private fun fixture(device: String) = Fixtures.bytes(device, "jobscheduler.txt")

    private fun lines(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    private fun job(
        uid: String,
        unsatisfied: String,
        header: String = "  JOB #$uid/1: 1a #$uid/1 com.example/.Job",
    ) = arrayOf(
        header,
        "    $uid tag=*job*r/com.example/.Job",
        "    Source: uid=$uid user=0 pkg=com.example",
        "    JobInfo:",
        "      Service: com.example/.Job",
        "    Unsatisfied constraints:$unsatisfied",
        "    Standby bucket: RARE",
        "    Restricted due to: none.",
        "    Ready: false (job=false user=true !restricted=true !pending=true !active=true !backingup=true comp=true)",
    )

    /** A dump with the given job lines under `Registered N jobs` and one stats window. */
    private fun dump(
        count: Int,
        vararg jobLines: String,
    ) = lines(
        "Registered $count jobs:",
        *jobLines,
        "",
        "PrefetchController:",
        "  Nothing",
        "",
        "Current stats at 2026-11-06-06-46-54 (-1m0s0ms) over +1m0s0ms:",
        "  Max concurrency: 1 total, 0 foreground",
        "  ",
        "  u0a5 / com.example:",
        "    2x pending 50% 1x active 1x active-top (active)",
        "    1x timeout_abandoned",
        "",
        "Pending queue:",
        "  None",
    )

    // Expected values below come from awk over the fixture files, not from this parser.

    @Test
    fun theEmulatorDumpReadsWhole() {
        val bytes = fixture(Fixtures.AVD)
        val (dump, report) = parse(bytes)
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).isEmpty()
        assertThat(report.bytesRead).isEqualTo(bytes.size.toLong())

        val jobs = checkNotNull(dump.registeredJobs)
        assertThat(jobs).hasSize(155)
        assertThat(jobs.count { it.ready }).isEqualTo(0)
        assertThat(jobs.groupingBy { it.standbyBucket }.eachCount()).containsExactly("ACTIVE", 118, "EXEMPTED", 37)
        assertThat(jobs.first())
            .isEqualTo(RegisteredJob(1000, "android", "EXEMPTED", listOf("TIMING_DELAY", "DEADLINE"), emptyList(), false))

        val window = checkNotNull(dump.statsWindows).single()
        assertThat(window.current).isTrue()
        assertThat(window.startMsBeforeDump).isEqualTo(1_628_291L)
        assertThat(window.durationMs).isEqualTo(1_628_291L)
        assertThat(window.packages).hasSize(24)
        assertThat(window.packages.first())
            .isEqualTo(
                JobPackageStats(
                    1000,
                    "android",
                    JobStateCount(9, null),
                    JobStateCount(6, null),
                    null,
                    mapOf("successful_finish" to 11),
                ),
            )
        // `7x pending 1% 4x active`.
        val search = window.packages.single { it.packageName == "com.google.android.googlequicksearchbox" }
        assertThat(search.uid).isEqualTo(10162)
        assertThat(search.pending).isEqualTo(JobStateCount(7, null))
        assertThat(search.active).isEqualTo(JobStateCount(4, 1))
        val stops =
            window.packages
                .flatMap { it.stopReasons.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { it.value.sum() }
        assertThat(stops).containsExactly("successful_finish", 103, "canceled", 20, "unknown:-1", 6)
    }

    @Test
    fun thePhoneDumpHasFiveWindowsAndCountsSamsungsStopReason() {
        val (dump, report) = parse(fixture(Fixtures.PHONE))
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).containsExactly("stop.olaf", 12)

        val jobs = checkNotNull(dump.registeredJobs)
        assertThat(jobs).hasSize(461)
        assertThat(jobs.count { "CONNECTIVITY" in it.unsatisfiedConstraints }).isEqualTo(28)
        assertThat(jobs.count { it.unsatisfiedConstraints.isEmpty() }).isEqualTo(6)
        assertThat(jobs.groupingBy { it.standbyBucket }.eachCount()).containsExactly("EXEMPTED", 459, "RESTRICTED", 2)

        val windows = checkNotNull(dump.statsWindows)
        // Oldest first: -2h50m30s876ms, -2h19m46s262ms, -1h49m6s539ms, -1h15m23s681ms, then the current one.
        assertThat(windows.map { it.startMsBeforeDump })
            .containsExactly(10_230_876L, 8_386_262L, 6_546_539L, 4_523_681L, 2_692_616L)
            .inOrder()
        assertThat(windows.map { it.current }).containsExactly(false, false, false, false, true).inOrder()
        assertThat(windows.map { it.packages.size }).containsExactly(19, 17, 33, 12, 43).inOrder()
        assertThat(windows.first().durationMs).isEqualTo(1_844_614L)
        val stops =
            windows
                .flatMap { w ->
                    w.packages.flatMap { it.stopReasons.entries }
                }.groupBy({ it.key }, { it.value })
                .mapValues { it.value.sum() }
        assertThat(stops).containsExactly("successful_finish", 435, "canceled", 108, "unknown:-1", 23, "olaf", 12, "anr", 2)
        val app = windows.last().packages.single { it.packageName == "com.suvantyto.android" }
        assertThat(app.activeTop).isEqualTo(JobStateCount(4, null))
        assertThat(app.stopReasons).containsExactly("canceled", 14, "successful_finish", 5).inOrder()
    }

    @Test
    fun aNamespaceCannotAddAJobOrChangeAnothersFields() {
        val (dump, report) = parse(dump(2, *job("u0a5", " CONNECTIVITY [0x10000000]"), *job("u0a6", "")))
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(dump.registeredJobs)
            .containsExactly(
                RegisteredJob(10005, "com.example", "RARE", listOf("CONNECTIVITY"), emptyList(), false),
                RegisteredJob(10006, "com.example", "RARE", emptyList(), emptyList(), false),
            ).inOrder()
        val window = checkNotNull(dump.statsWindows).single()
        assertThat(window.packages.single())
            .isEqualTo(
                JobPackageStats(
                    10005,
                    "com.example",
                    JobStateCount(2, null),
                    JobStateCount(1, 50),
                    JobStateCount(1, null),
                    mapOf(JobPackageStats.TIMEOUT_ABANDONED to 1),
                ),
            )

        // A namespace with a line break printed two spaces in: a forged JOB line breaks the count.
        val forgedJob = "  JOB secret\n  JOB #u0a6/1: forged:u0a5/1: 1a #u0a5/1 com.example/.Job"
        val (extra, extraReport) = parse(dump(1, *job("u0a5", " CONNECTIVITY", header = forgedJob)))
        assertThat(extra.registeredJobs).isNull()
        assertThat(extraReport.complete).isFalse()
        // The real entry's start, cut off by the forged line, has no fields of its own.
        assertThat(extraReport.issues.map { it.message })
            .containsExactly("JOB lines do not match the registered count", "registered jobs not read: 1")
        assertThat(extra.statsWindows).hasSize(1)

        // A forged field line inside its own entry: that job is dropped, the others stay.
        val forgedField = "  JOB secret\n    Unsatisfied constraints:\n    Ready: true (x): 1a #u0a5/1 com.example/.Job"
        val (own, ownReport) = parse(dump(2, *job("u0a5", " CHARGING", header = forgedField), *job("u0a6", " IDLE")))
        assertThat(own.registeredJobs).containsExactly(RegisteredJob(10006, "com.example", "RARE", listOf("IDLE"), emptyList(), false))
        assertThat(ownReport.complete).isFalse()
        assertThat(ownReport.issues.map { it.message }).containsExactly("registered jobs not read: 1")
        assertThat(own.toString()).doesNotContain("secret")
    }

    @Test
    fun jobHistoryCannotForgeAWindowOrAJobList() {
        val avd = fixture(Fixtures.AVD).decodeToString()
        val (real, _) = parse(avd)
        // A namespace with line breaks, as Job history prints it at indent 0 after the windows.
        val namespace =
            lines(
                "x",
                "Historical stats at 2026-11-06-06-00-00 (-1h0m0s0ms) over +30m0s0ms:",
                "  u0a50 / com.victim:",
                "    100x pending 100x active",
                "    99x timeout_abandoned",
                "",
                "Current stats at 2026-11-06-06-46-54 (-1m0s0ms) over +1m0s0ms:",
                "  u0a50 / com.victim:",
                "    100x pending",
                "Registered 0 jobs:",
                "  None.",
            )
        val event = "       -1m0s0ms   START: #u0a50/1 @$namespace@com.example/.Job\n"
        val forged = avd.replace("  Job history:\n", "  Job history:\n$event")
        assertThat(forged).isNotEqualTo(avd)

        val (dump, report) = parse(forged)
        assertThat(report.complete).isTrue()
        assertThat(report.issues.map { it.message }).containsExactly("headings after Current stats ignored: 3")
        assertThat(dump).isEqualTo(real)
        assertThat(dump.toString()).doesNotContain("com.victim")
    }

    @Test
    fun anEmptyListAndRestrictionsRead() {
        val (empty, emptyReport) = parse(dump(0, "  None."))
        assertWithMessage(emptyReport.issues.toString()).that(emptyReport.complete).isTrue()
        assertThat(empty.registeredJobs).isEmpty()

        val restricted =
            job("u0a5", " WITHIN_QUOTA [0x1000000]").map {
                if (it.contains("Restricted due to")) "    Restricted due to: thermal battery_saver." else it
            }
        val (dump, report) = parse(dump(1, *restricted.toTypedArray()))
        assertWithMessage(report.issues.toString()).that(report.complete).isTrue()
        assertThat(checkNotNull(dump.registeredJobs).single().restrictions).containsExactly("thermal", "battery_saver").inOrder()
    }

    @Test
    fun aDumpWithoutItsSectionsIsIncomplete() {
        val (dump, report) = parse("Can't find service: jobscheduler\n")
        assertThat(report.complete).isFalse()
        assertThat(report.issues.map { it.message }).containsExactly("no Registered jobs", "no Current stats")
        assertThat(dump.registeredJobs).isNull()
        assertThat(dump.statsWindows).isNull()

        val avd = fixture(Fixtures.AVD).decodeToString()
        val (twice, twiceReport) = parse(avd.replace("Current stats at ", "Current stats at x"))
        assertThat(twice.statsWindows).isNull()
        assertThat(twiceReport.issues.map { it.message }).containsExactly("no Current stats")

        val (bad, badReport) = parse(avd.replace("    9x pending 6x active ", "    9x waiting"))
        assertThat(bad.statsWindows).isNull()
        assertThat(badReport.issues.map { it.message }).containsExactly("unreadable stats window line")
    }

    @Test
    fun noInputEverThrowsAndOnlyTheWholeDumpIsComplete() {
        val whole = fixture(Fixtures.AVD)
        val ends = whole.indices.filter { whole[it] == '\n'.code.toByte() }.toSet()
        // The fixture is 600 KB, so cuts are sampled: every 997th byte and the last 300.
        for (n in (0 until whole.size step 997) + (whole.size - 300 until whole.size)) {
            val (_, report) = parse(whole.copyOf(n))
            assertThat(report.bytesRead).isEqualTo(n.toLong())
            // A cut right after a newline is invisible here (ADR-0009).
            if (n - 1 !in ends) assertWithMessage("cut at $n").that(report.complete).isFalse()
        }
        val phone = fixture(Fixtures.PHONE)
        val random = Random(19)
        repeat(20) {
            val damaged = phone.copyOf()
            repeat(50) { damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
            parse(damaged)
        }
        parse(ByteArray(0))
        parse(Random(21).nextBytes(100_000))
    }
}

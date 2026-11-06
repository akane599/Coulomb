// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.dumpsys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.model.dumpsys.AlarmTagStats
import io.github.akane599.coulomb.core.model.dumpsys.AlarmType
import io.github.akane599.coulomb.core.model.dumpsys.PendingAlarm
import io.github.akane599.coulomb.core.parsing.Fixtures
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class AlarmParserTest {
    private fun parse(bytes: ByteArray) = AlarmParser.parse(ByteArrayInputStream(bytes))

    private fun parse(text: String) = parse(text.encodeToByteArray())

    private fun fixture(device: String) = Fixtures.bytes(device, "alarm.txt")

    private fun lines(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    /** A dump with the given pending block (its heading included) and `Alarm Stats` lines. */
    private fun dump(
        pending: List<String>,
        perUid: String,
        stats: List<String>,
    ) = lines(
        "Current Alarm Manager state:",
        "  nowRTC=1793938442623=2026-11-06 07:14:02.623 nowELAPSED=1636007",
        "  ",
        *pending.toTypedArray(),
        "  ",
        "  Pending alarms per uid: [$perUid]",
        "  ",
        "  Alarm Stats:",
        *stats.toTypedArray(),
        "  ",
        "  Alarm manager stats:",
    )

    // Expected values below come from reading the fixture files and from awk over them, not from this parser.

    @Test
    fun theEmulatorDumpReadsWhole() {
        val bytes = fixture(Fixtures.AVD)
        val (dump, report) = parse(bytes)
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).isEmpty()
        assertThat(report.bytesRead).isEqualTo(bytes.size.toLong())
        assertThat(dump.nowElapsedMs).isEqualTo(1_636_007L)
        assertThat(dump.nowRtcMs).isEqualTo(1_793_938_442_623L)

        val pending = checkNotNull(dump.pending)
        assertThat(pending).hasSize(47)
        assertThat(pending[0])
            .isEqualTo(
                PendingAlarm(AlarmType.ELAPSED, "com.google.android.gms", 1_584_355L, "*alarm*:com.google.android.gms.vision.DEPENDENCY"),
            )
        assertThat(pending[3])
            .isEqualTo(
                PendingAlarm(
                    AlarmType.ELAPSED_WAKEUP,
                    "com.google.android.gms",
                    1_702_917L,
                    "*walarm*:com.google.android.gms.gcm.HEARTBEAT_ALARM",
                ),
            )
        assertThat(pending.map { it.whenElapsedMs }).isInOrder()
        assertThat(pending.all { it.tag != null }).isTrue()

        assertThat(dump.pendingPerUid)
            .containsExactly(1000, 19, 1001, 1, 1002, 1, 1073, 3, 10110, 1, 10147, 1, 10156, 1, 10160, 1, 10162, 2, 10201, 1, 10212, 16)
            .inOrder()

        val apps = checkNotNull(dump.appStats)
        assertThat(apps).hasSize(8)
        assertThat(apps.sumOf { it.wakeups }).isEqualTo(27)
        assertThat(apps.sumOf { it.alarms.size }).isEqualTo(26)
        val system = apps.first()
        assertThat(system.uid).isEqualTo(1000)
        assertThat(system.packageName).isEqualTo("android")
        assertThat(system.runningMs).isEqualTo(74L)
        assertThat(system.wakeups).isEqualTo(8)
        assertThat(system.alarms).hasSize(10)
        // +56ms 0 wakes 1 alarms, last -26m52s739ms.
        assertThat(
            system.alarms.first(),
        ).isEqualTo(AlarmTagStats("*alarm*:android.intent.action.UPDATE_CT_LOGS", 56L, 0, 1, 1_612_739L, false))
        assertThat(system.alarms.last().tag).isEqualTo("*alarm*:WifiHealthMonitor Schedule Post-Boot Detection Timer")
        assertThat(apps.last().uid).isEqualTo(10212)
        assertThat(apps.last().wakeups).isEqualTo(15)
    }

    @Test
    fun thePhoneDumpReadsWholeWithSamsungsExtraSections() {
        val (dump, report) = parse(fixture(Fixtures.PHONE))
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).isEmpty()
        assertThat(dump.nowElapsedMs).isEqualTo(45_179_478L)
        val pending = checkNotNull(dump.pending)
        assertThat(pending).hasSize(106)
        assertThat(pending[2])
            .isEqualTo(
                PendingAlarm(
                    AlarmType.ELAPSED_WAKEUP,
                    "io.github.ezkuhsew.wlzaeluitd",
                    45_253_863L,
                    "*walarm*:io.github.ezkuhsew.wlzaeluitd.WATCHDOG_CHECK",
                ),
            )
        // One UI's per-UID counts do not add up to the pending list; they are kept as printed.
        assertThat(checkNotNull(dump.pendingPerUid).values.sum()).isEqualTo(68)
        assertThat(dump.pendingPerUid).containsEntry(10338, 22)

        val apps = checkNotNull(dump.appStats)
        assertThat(apps).hasSize(31)
        assertThat(apps.sumOf { it.wakeups }).isEqualTo(794)
        assertThat(apps.sumOf { it.alarms.size }).isEqualTo(111)
        val android = apps.single { it.uid == 1000 && it.packageName == "android" }
        assertThat(android.runningMs).isEqualTo(10_895L)
        assertThat(android.wakeups).isEqualTo(238)
    }

    @Test
    fun eachAppsWakeupsAreTheSumOfItsTags() {
        // The platform counts a waking delivery on both at once (16 L5513-5514, 17 L5650-5651).
        for (device in Fixtures.ALL) {
            for (app in checkNotNull(parse(fixture(device)).value.appStats)) {
                assertWithMessage("$device ${app.packageName}").that(app.alarms.sumOf { it.wakeups }).isEqualTo(app.wakeups)
            }
        }
    }

    @Test
    fun aTagCannotPrintPlatformLines() {
        // Every line of a tag carries the writer's six-space indent, so a forged app line sits deeper.
        val text =
            dump(
                pending =
                    listOf(
                        "  1 pending alarms: ",
                        "    RTC_WAKEUP #1: Alarm{1a type 0 origWhen 5 whenElapsed 900 com.example}",
                        "      tag=*walarm*:first line",
                        "        RTC_WAKEUP #2: Alarm{1b type 0 origWhen 5 whenElapsed 1 com.forged}",
                        "      type=RTC_WAKEUP origWhen=2026-11-06 07:14:02.623 window=0 repeatInterval=0 count=0 flags=0x0",
                    ),
                perUid = "u0a5:1",
                stats =
                    listOf(
                        "  *ACTIVE* u0a5:com.example +1s0ms running, 2 wakeups:",
                        "    *ACTIVE* +1s0ms 2 wakes 3 alarms, last --:",
                        "      *walarm*:secret",
                        "        u0a6:com.victim +1h0m0s0ms running, 99 wakeups:",
                        "      ",
                    ),
            )
        val (dump, report) = parse(text)
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(dump.pending).containsExactly(PendingAlarm(AlarmType.RTC_WAKEUP, "com.example", 900L, "*walarm*:first line"))
        assertThat(dump.pendingPerUid).containsExactly(10005, 1)
        val app = checkNotNull(dump.appStats).single()
        assertThat(app.uid).isEqualTo(10005)
        assertThat(app.active).isTrue()
        assertThat(app.alarms)
            .containsExactly(
                AlarmTagStats("*walarm*:secret\n  u0a6:com.victim +1h0m0s0ms running, 99 wakeups:\n", 1_000L, 2, 3, null, true),
            )
    }

    @Test
    fun aListThatDoesNotMatchItsCountIsDropped() {
        val stats = listOf("  1000:android 0 running, 0 wakeups:", "    0 0 wakes 1 alarms, last -5s0ms:", "      *alarm*:TIME_TICK")
        val (short, shortReport) =
            parse(
                dump(
                    listOf("  2 pending alarms: ", "    ELAPSED #1: Alarm{1a type 3 origWhen 5 whenElapsed 900 android}"),
                    "1000:2",
                    stats,
                ),
            )
        assertThat(short.pending).isNull()
        assertThat(short.appStats).hasSize(1)
        assertThat(shortReport.complete).isFalse()
        assertThat(shortReport.issues.map { it.message }).containsExactly("pending alarm lines do not match their count")

        // An empty store prints no pending block at all.
        val (empty, emptyReport) = parse(dump(emptyList(), "", stats))
        assertWithMessage(emptyReport.issues.toString()).that(emptyReport.complete).isTrue()
        assertThat(empty.pending).isEmpty()
        assertThat(empty.pendingPerUid).isEmpty()
        assertThat(
            checkNotNull(empty.appStats)
                .single()
                .alarms
                .single()
                .lastMsBeforeDump,
        ).isEqualTo(5_000L)

        // A filter line with no tag under it, and an app whose UID form is not read.
        val (bad, badReport) =
            parse(
                dump(
                    emptyList(),
                    "u0i5:1",
                    listOf(
                        "  u0i5:com.isolated 0 running, 0 wakeups:",
                        "    0 0 wakes 1 alarms, last -5s0ms:",
                        "      *alarm*:x",
                        "  1000:android 0 running, 0 wakeups:",
                        "    0 0 wakes 1 alarms, last -5s0ms:",
                    ),
                ),
            )
        assertThat(bad.pendingPerUid).isNull()
        assertThat(bad.appStats).isNull()
        assertThat(badReport.complete).isFalse()
        assertThat(badReport.issues.map { it.message })
            .containsExactly("unreadable uid in Alarm Stats", "unreadable Alarm Stats line", "unreadable Pending alarms per uid")
    }

    @Test
    fun aDumpWithoutItsSectionsIsIncomplete() {
        val (dump, report) = parse("Can't find service: alarm\n")
        assertThat(report.complete).isFalse()
        assertThat(report.issues.map { it.message })
            .containsExactly(
                "no alarm manager header",
                "no nowELAPSED line",
                "no pending alarms",
                "no Pending alarms per uid line",
                "no Alarm Stats",
            )
        assertThat(dump.pending).isNull()
        assertThat(dump.appStats).isNull()

        val avd = fixture(Fixtures.AVD).decodeToString()
        val (twice, twiceReport) = parse(avd + "  Alarm Stats:\n")
        assertThat(twice.appStats).isNull()
        assertThat(twiceReport.issues.map { it.message }).containsExactly("Alarm Stats printed more than once")
    }

    @Test
    fun noInputEverThrowsAndOnlyTheWholeDumpIsComplete() {
        val whole = fixture(Fixtures.AVD)
        val ends = whole.indices.filter { whole[it] == '\n'.code.toByte() }.toSet()
        for (n in (0 until whole.size step 7) + (whole.size - 200 until whole.size)) {
            val (_, report) = parse(whole.copyOf(n))
            assertThat(report.bytesRead).isEqualTo(n.toLong())
            // A cut right after a newline is invisible here (ADR-0009).
            if (n - 1 !in ends) assertWithMessage("cut at $n").that(report.complete).isFalse()
        }
        val phone = fixture(Fixtures.PHONE)
        val random = Random(17)
        repeat(100) {
            val damaged = phone.copyOf()
            repeat(20) { damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
            parse(damaged)
        }
        parse(ByteArray(0))
        parse(Random(9).nextBytes(100_000))
    }
}

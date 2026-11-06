// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing

import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.model.parse.ParseReport
import io.github.akane599.coulomb.core.parsing.batterystats.BatteryHistoryParser
import io.github.akane599.coulomb.core.parsing.batterystats.BatteryStatsCheckinParser
import io.github.akane599.coulomb.core.parsing.batterystats.BatteryStatsProtoParser
import io.github.akane599.coulomb.core.parsing.dumpsys.AlarmParser
import io.github.akane599.coulomb.core.parsing.dumpsys.BatteryServiceParser
import io.github.akane599.coulomb.core.parsing.dumpsys.DeviceIdleParser
import io.github.akane599.coulomb.core.parsing.dumpsys.JobSchedulerParser
import io.github.akane599.coulomb.core.parsing.dumpsys.PowerParser
import io.github.akane599.coulomb.core.parsing.dumpsys.ThermalServiceParser
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * The P2 gate's "100% fixtures parse" (SPEC 17): every file in every committed fixture set is
 * either read whole by its parser, with no issue, or listed below as not a parser input, with the
 * reason. A new fixture file fails this test until it is given one or the other.
 */
class FixtureGateTest {
    private val parsers: Map<String, (InputStream) -> ParseReport> =
        mapOf(
            "batterystats.proto.bin" to { BatteryStatsProtoParser.parse(it).report },
            "batterystats.checkin.txt" to { BatteryStatsCheckinParser.parse(it, FIXTURE_SDK).report },
            "alarm.txt" to { AlarmParser.parse(it).report },
            "battery.txt" to { BatteryServiceParser.parse(it).report },
            "deviceidle.txt" to { DeviceIdleParser.parse(it).report },
            "jobscheduler.txt" to { JobSchedulerParser.parse(it).report },
            "power.txt" to { PowerParser.parse(it).report },
            "thermal.txt" to { ThermalServiceParser.parse(it).report },
        )

    /** Files that no parser reads, and why. */
    private val notParserInputs: Map<String, String> =
        mapOf(
            "README.md" to "describes the set",
            "captured_at.txt" to "capture time",
            "status.tsv" to "each capture command's exit code and size",
            "batterystats.help.txt" to "the flag list, checked by hand for `-c` and `--history-start`",
            "batterystats.history.txt" to "the human `--history` format; Coulomb reads `-c --history-start` (ADR-0014)",
            "suspend_control.txt" to "S8 is not registered on either device (P0 finding)",
            "suspend_control.txt.err" to "S8's error output",
        )

    @Test
    fun everyFixtureFileIsParsedWholeOrListedAsNoParserInput() {
        for (device in Fixtures.ALL) {
            val set = Fixtures.file(device, "")
            val files =
                set
                    .walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(set).invariantSeparatorsPath }
                    .sorted()
            for (path in files) {
                when {
                    path in parsers -> assertWhole(device, path, parsers.getValue(path))

                    path.startsWith("history-checkin/") && path.endsWith(".txt") -> assertWhole(device, path, history(set, path))

                    path.startsWith("history-checkin/") && path.endsWith(".cursor") -> Unit

                    path.startsWith("probe/") -> Unit

                    // capability probe inputs, read by tools/capabilities/report.py
                    else -> assertWithMessage("$device/$path has no parser and no reason").that(notParserInputs).containsKey(path)
                }
            }
        }
    }

    @Test
    fun everyParserHasAFixtureOnEachDevice() {
        for (device in Fixtures.ALL) {
            for (name in parsers.keys) {
                assertWithMessage("$device/$name").that(Fixtures.file(device, name).isFile).isTrue()
            }
        }
    }

    /** S7 reads go from the cursor saved next to them; the first read starts at 0. */
    private fun history(
        set: File,
        path: String,
    ): (InputStream) -> ParseReport {
        val cursorFile = File(set, path.removeSuffix(".txt") + ".cursor")
        val cursor = if (cursorFile.isFile) cursorFile.readText().trim().toLong() else 0L
        return { BatteryHistoryParser.parse(it, cursor) {}.report }
    }

    private fun assertWhole(
        device: String,
        path: String,
        parse: (InputStream) -> ParseReport,
    ) {
        val file = Fixtures.file(device, path)
        val report = file.inputStream().buffered().use(parse)
        val what = "$device/$path: ${report.issues}"
        assertWithMessage(what).that(report.issueCount).isEqualTo(0)
        assertWithMessage(what).that(report.complete).isTrue()
        assertWithMessage(what).that(report.bytesRead).isEqualTo(file.length())
    }

    private companion object {
        /** Both fixture devices run Android 17 (`ro.build.version.sdk` in each set's `probe/getprop.txt`). */
        const val FIXTURE_SDK = 37
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.dumpsys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.parsing.Fixtures
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class BatteryServiceParserTest {
    private fun parse(bytes: ByteArray) = BatteryServiceParser.parse(ByteArrayInputStream(bytes))

    private fun parse(text: String) = parse(text.encodeToByteArray())

    private fun fixture(device: String) = Fixtures.bytes(device, "battery.txt")

    @Test
    fun thePhoneDumpFillsEveryAospFieldAndKeepsSamsungsLinesAsExtras() {
        val bytes = fixture(Fixtures.PHONE)
        val (dump, report) = parse(bytes)
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.bytesRead).isEqualTo(bytes.size.toLong())
        assertThat(dump.updatesStopped).isFalse()
        assertThat(dump.acPowered).isTrue()
        assertThat(dump.usbPowered).isFalse()
        assertThat(dump.wirelessPowered).isFalse()
        assertThat(dump.dockPowered).isFalse()
        assertThat(dump.maxChargingCurrentUa).isEqualTo(0)
        assertThat(dump.maxChargingVoltageUv).isEqualTo(0)
        assertThat(dump.chargeCounterUah).isEqualTo(4_096_400)
        assertThat(dump.status).isEqualTo(2)
        assertThat(dump.health).isEqualTo(2)
        assertThat(dump.present).isTrue()
        assertThat(dump.level).isEqualTo(83)
        assertThat(dump.scale).isEqualTo(100)
        assertThat(dump.voltageMv).isEqualTo(4387)
        assertThat(dump.temperatureDeciC).isEqualTo(338)
        assertThat(dump.technology).isEqualTo("Li-ion")
        assertThat(dump.chargingState).isEqualTo(0)
        assertThat(dump.chargingPolicy).isEqualTo(0)
        assertThat(dump.capacityLevel).isEqualTo(-1)

        val extras = dump.extras
        assertThat(extras).containsEntry("current now", "3525781")
        assertThat(extras).containsEntry("charge counter", "4096400")
        assertThat(extras).containsEntry("mSavedBatteryAsoc", "[100]")
        assertThat(extras).containsEntry("mSavedBatteryBsoh", "96")
        assertThat(extras).containsEntry("mProtectBatteryMode", "3")
        assertThat(extras).containsEntry("LLB MAN", "")
        assertThat(extras).containsEntry("batttest_init (AT+BATTTEST=2,1)", "Not found or empty")
        assertThat(
            extras,
        ).containsEntry("mBatteryChangedConditionCounts", "[6, 1, 1, 144, 6, 78, 31, 0, 0, 6, 5, 0, 1, 0, 0, 5, 15, 6, 0, 0, 0, 1]")
        // Log buffers hold past events and the battery's QR code; none of it is kept.
        assertThat(extras.keys.filter { it.first().isDigit() }).isEmpty()
        assertThat(extras.values.filter { "efsValue" in it || "ACTION_" in it }).isEmpty()
        // The AOSP lines are fields, not extras.
        assertThat(extras.keys).containsNoneOf("level", "Charge counter", "AC powered")

        // Every extra key is counted, and so is every log buffer, by name.
        assertThat(report.unknown).containsEntry("[BattInfoLogBuffer]", 1)
        assertThat(report.unknown).containsEntry("[EventLogBuffer]", 1)
        assertThat(report.unknown).containsEntry("<bracketed line>", 1)
        assertThat(report.unknown.keys.filter { " " in it && it.startsWith("[") }).isEmpty()
        assertThat(report.unknown).containsEntry("mSavedBatteryBsoh", 1)
        assertThat(report.unknown).containsEntry("<line without key>", 2)
        assertThat(report.unknown.keys).containsAtLeastElementsIn(extras.keys)
    }

    @Test
    fun theEmulatorDumpHasOnlyAospLines() {
        val (dump, report) = parse(fixture(Fixtures.AVD))
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).isEmpty()
        assertThat(dump.extras).isEmpty()
        assertThat(dump.level).isEqualTo(100)
        assertThat(dump.maxChargingCurrentUa).isEqualTo(5_000_000)
        assertThat(dump.chargeCounterUah).isEqualTo(10_000)
        assertThat(dump.temperatureDeciC).isEqualTo(250)
        assertThat(dump.voltageMv).isEqualTo(5000)
    }

    @Test
    fun frozenValuesAreFlagged() {
        val text = fixture(Fixtures.AVD).decodeToString()
        val frozen = text.replaceFirst("state:\n", "state:\n  (UPDATES STOPPED -- use 'reset' to restart)\n")
        val (dump, report) = parse(frozen)
        assertThat(dump.updatesStopped).isTrue()
        assertThat(report.complete).isTrue()
    }

    @Test
    fun aMissingOrUnreadableAospLineMakesTheDumpIncompleteWithoutEchoingTheValue() {
        val text = fixture(Fixtures.AVD).decodeToString()
        val (missing, missingReport) = parse(text.replace("  level: 100\n", ""))
        assertThat(missing.level).isNull()
        assertThat(missingReport.complete).isFalse()
        assertThat(missingReport.issues.map { it.message }).containsExactly("no level line")

        val (bad, badReport) = parse(text.replace("  level: 100\n", "  level: secret42\n"))
        assertThat(bad.level).isNull()
        assertThat(bad.voltageMv).isEqualTo(5000)
        assertThat(badReport.complete).isFalse()
        assertThat(badReport.issues.map { it.message }).containsExactly("unreadable level")
    }

    @Test
    fun aLineTheStreamCutIsNotRead() {
        val text = fixture(Fixtures.AVD).decodeToString()
        val cut = text.substring(0, text.indexOf("  Capacity level: -1") + "  Capacity level: ".length + 1)
        val (dump, report) = parse(cut)
        assertThat(dump.capacityLevel).isNull()
        assertThat(dump.chargingPolicy).isEqualTo(0)
        assertThat(report.complete).isFalse()
        assertThat(report.issues.map { it.message }).contains("ended inside a line")
    }

    @Test
    fun aRepeatedKeyKeepsTheFirstValue() {
        val text = fixture(Fixtures.AVD).decodeToString() + "  level: 5\n  oem key: 1\n  oem key: 2\n"
        val (dump, report) = parse(text)
        assertThat(dump.level).isEqualTo(100)
        assertThat(dump.extras).containsExactly("oem key", "1")
        assertThat(report.issues.map { it.message }).containsExactly("repeated key level", "repeated key oem key")
        assertThat(report.complete).isTrue()
    }

    @Test
    fun oversizedValuesAndTooManyKeysAreBoundedAndReportedByName() {
        val base = fixture(Fixtures.AVD).decodeToString()
        val long = "x".repeat(300)
        val many = (1..300).joinToString("") { "k$it: v\n" }
        val (dump, report) = parse("$base  big: $long\n$many")
        assertThat(dump.extras).doesNotContainKey("big")
        assertThat(dump.extras).hasSize(256)
        assertThat(report.issues.map { it.message }).containsExactly("value of big longer than 256", "more than 256 extra keys")
        assertThat(report.issues.none { long in it.message }).isTrue()
    }

    @Test
    fun anythingElseIsNotABatteryDump() {
        val (dump, report) = parse("Can't find service: battery\n")
        assertThat(report.complete).isFalse()
        assertThat(report.issues.map { it.message }).contains("no battery service header")
        assertThat(dump.extras).containsExactly("Can't find service", "battery")
    }

    @Test
    fun noInputEverThrowsAndOnlyTheWholeDumpIsComplete() {
        val whole = fixture(Fixtures.PHONE)
        val ends = whole.indices.filter { whole[it] == '\n'.code.toByte() }.toSet()
        for (n in 0 until whole.size) {
            val (_, report) = parse(whole.copyOf(n))
            assertThat(report.bytesRead).isEqualTo(n.toLong())
            // A cut right after a newline is invisible here once the AOSP lines are in (ADR-0009).
            if (n - 1 !in ends) assertWithMessage("cut at $n").that(report.complete).isFalse()
        }
        val random = Random(21)
        repeat(200) {
            val damaged = whole.copyOf()
            repeat(20) { damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
            parse(damaged)
        }
        parse(ByteArray(0))
        parse(Random(7).nextBytes(200_000))
    }
}

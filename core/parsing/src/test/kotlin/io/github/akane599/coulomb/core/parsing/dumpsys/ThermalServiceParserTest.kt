// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.dumpsys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.model.dumpsys.ThermalReading
import io.github.akane599.coulomb.core.model.dumpsys.ThermalThreshold
import io.github.akane599.coulomb.core.parsing.Fixtures
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class ThermalServiceParserTest {
    private fun parse(bytes: ByteArray) = ThermalServiceParser.parse(ByteArrayInputStream(bytes))

    private fun parse(text: String) = parse(text.encodeToByteArray())

    private fun fixture(device: String) = Fixtures.bytes(device, "thermal.txt")

    @Test
    fun thePhoneDumpReadsEverySensorAndThreshold() {
        val bytes = fixture(Fixtures.PHONE)
        val (dump, report) = parse(bytes)
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).isEmpty()
        assertThat(report.bytesRead).isEqualTo(bytes.size.toLong())
        assertThat(dump.statusOverridden).isFalse()
        assertThat(dump.status).isEqualTo(0)
        assertThat(dump.halReady).isTrue()
        assertThat(dump.currentTemperatures)
            .containsExactly(
                ThermalReading("AP", 0, 408, 0),
                ThermalReading("BAT", 2, 339, 0),
                ThermalReading("PA", 5, 410, 0),
                ThermalReading("SKIN", 3, 362, 0),
                ThermalReading("SUBBAT", 2, 0, 0),
                ThermalReading("USB", 4, 322, 0),
            ).inOrder()
        // The cached values are older: AP read 35.5 there and 40.8 from the HAL.
        assertThat(dump.cachedTemperatures).hasSize(6)
        assertThat(dump.cachedTemperatures.single { it.name == "AP" }.deciC).isEqualTo(355)
        assertThat(dump.coolingDevices).isEmpty()
        val none = List<Int?>(7) { null }
        assertThat(dump.thresholds)
            .containsExactly(
                ThermalThreshold("BAT", 2, listOf(null, null, null, null, null, 550, 850), none),
                ThermalThreshold("SKIN", 3, listOf(380, 400, 420, 450, 470, 600, 900), none),
            ).inOrder()
    }

    @Test
    fun theEmulatorDumpHasEmptyColdThresholds() {
        val (dump, report) = parse(fixture(Fixtures.AVD))
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).isEmpty()
        assertThat(dump.currentTemperatures.map { it.name to it.deciC }).containsExactly("skin" to 301, "battery" to 302).inOrder()
        assertThat(dump.thresholds.map { it.coldDeciC }).containsExactly(emptyList<Int?>(), emptyList<Int?>())
        assertThat(dump.thresholds.first().hotDeciC).containsExactly(300, 310, 320, 330, 340, 350, 360).inOrder()
    }

    @Test
    fun virtualDeviceLinesAndNamesWithPunctuationAreHandled() {
        val text =
            """
            IsStatusOverride: true
            ThermalStatusListeners:
            ${'\t'}callbacks: 1
            ThermalStatusListenersPerDevice (DeviceId=3):
            ${'\t'}callbacks: 2
            Thermal Status: 2
            Thermal Status (DeviceId=3): 4
            Cached temperatures:
            ${'\t'}Temperature{mValue=NaN, mType=3, mName=skin, front}, mStatus=1}
            HAL Ready: true
            HAL connection:
            ${'\t'}ThermalHAL AIDL 3  connected: yes
            Current temperatures from HAL:
            ${'\t'}Temperature{mValue=-12.25, mType=-1, mName=odd, name, mStatus=6}
            Current cooling devices from HAL:
            ${'\t'}CoolingDevice{mValue=3, mType=0, mName=fan, big}
            Temperature static thresholds from HAL:
            ${'\t'}TemperatureThreshold{mType=0, mName=cpu, mHotThrottlingThresholds=null, mColdThrottlingThresholds=[1.0E-4, Infinity]}
            Temperature headroom thresholds:
            [NaN, 1.0]

            """.trimIndent()
        val (dump, report) = parse(text)
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).isEmpty()
        assertThat(dump.statusOverridden).isTrue()
        assertThat(dump.status).isEqualTo(2)
        assertThat(dump.cachedTemperatures).containsExactly(ThermalReading("skin, front}", 3, null, 1))
        // -12.25 rounds half up to -122 tenths.
        assertThat(dump.currentTemperatures).containsExactly(ThermalReading("odd, name", -1, -122, 6))
        assertThat(dump.coolingDevices.single().name).isEqualTo("fan, big")
        assertThat(dump.coolingDevices.single().value).isEqualTo(3L)
        assertThat(dump.thresholds.single().hotDeciC).isEmpty()
        assertThat(dump.thresholds.single().coldDeciC).containsExactly(0, null).inOrder()
    }

    @Test
    fun withoutTheHalOnlyTheServiceLinesAreNeeded() {
        val text = "IsStatusOverride: false\nThermal Status: 0\nCached temperatures:\nHAL Ready: false\n"
        val (dump, report) = parse(text)
        assertThat(report.complete).isTrue()
        assertThat(dump.halReady).isFalse()
        assertThat(dump.currentTemperatures).isEmpty()
    }

    @Test
    fun aMissingHalSectionOrBadItemMakesTheDumpIncompleteWithoutEchoingIt() {
        val text = fixture(Fixtures.PHONE).decodeToString()
        val (_, cut) = parse(text.substring(0, text.indexOf("Current cooling devices")))
        assertThat(cut.complete).isFalse()
        assertThat(cut.issues.map { it.message })
            .containsExactly("no Current cooling devices from HAL", "no Temperature static thresholds from HAL")

        val (dump, bad) = parse(text.replace("mValue=40.8, mType=0", "mValue=hot, mType=0"))
        assertThat(bad.complete).isFalse()
        assertThat(bad.issues.map { it.message }).containsExactly("unreadable current item")
        assertThat(dump.currentTemperatures.map { it.name }).doesNotContain("AP")
        assertThat(dump.currentTemperatures).hasSize(5)
    }

    @Test
    fun otherTextIsCountedWithoutItsContent() {
        val (_, report) = parse("Permission Denial: can't dump thermalservice from pid=1\nsome secret words\n\tchild\n")
        assertThat(report.complete).isFalse()
        assertThat(report.unknown)
            .containsExactly("Permission Denial", 1, "<line without key>", 1, "<item under an unknown heading>", 1)
        assertThat(report.issues.map { it.message }).containsExactly("no IsStatusOverride", "no Thermal Status", "no HAL Ready")
    }

    @Test
    fun noInputEverThrowsAndOnlyTheWholeDumpIsComplete() {
        val whole = fixture(Fixtures.PHONE)
        val ends = whole.indices.filter { whole[it] == '\n'.code.toByte() }.toSet()
        for (n in 0 until whole.size) {
            val (_, report) = parse(whole.copyOf(n))
            assertThat(report.bytesRead).isEqualTo(n.toLong())
            // A cut right after a newline in the trailing headroom section is invisible here (ADR-0009).
            if (n - 1 !in ends) assertWithMessage("cut at $n").that(report.complete).isFalse()
        }
        val random = Random(19)
        repeat(300) {
            val damaged = whole.copyOf()
            repeat(10) { damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
            parse(damaged)
        }
        parse(ByteArray(0))
        parse(Random(3).nextBytes(100_000))
    }
}

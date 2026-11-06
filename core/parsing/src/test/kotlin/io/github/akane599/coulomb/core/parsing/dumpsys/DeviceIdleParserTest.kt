// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.dumpsys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.model.dumpsys.DeepDozeState
import io.github.akane599.coulomb.core.model.dumpsys.DozeEvent
import io.github.akane599.coulomb.core.model.dumpsys.DozeEventKind
import io.github.akane599.coulomb.core.model.dumpsys.LightDozeState
import io.github.akane599.coulomb.core.parsing.Fixtures
import io.github.akane599.coulomb.core.parsing.text.Durations
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class DeviceIdleParserTest {
    private fun parse(bytes: ByteArray) = DeviceIdleParser.parse(ByteArrayInputStream(bytes))

    private fun parse(text: String) = parse(text.encodeToByteArray())

    private fun fixture(device: String) = Fixtures.bytes(device, "deviceidle.txt")

    // Expected counts below come from awk over the fixture files, not from this parser.

    @Test
    fun theEmulatorDumpHasTheAospKeysAndAllowlists() {
        val bytes = fixture(Fixtures.AVD)
        val (dump, report) = parse(bytes)
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).isEmpty()
        assertThat(report.bytesRead).isEqualTo(bytes.size.toLong())
        assertThat(dump.settings).hasSize(36)
        assertThat(dump.settings.keys.first()).isEqualTo("flex_time_short")
        assertThat(dump.settings).containsEntry("idle_to", "+1h0m0s0ms")
        assertThat(dump.settings).containsEntry("location_accuracy", "20.0m")
        assertThat(dump.settings).containsEntry("use_mode_manager", "false")
        assertThat(dump.deepState).isEqualTo(DeepDozeState.ACTIVE)
        assertThat(dump.lightState).isEqualTo(LightDozeState.ACTIVE)
        assertThat(dump.lightEnabled).isTrue()
        assertThat(dump.deepEnabled).isTrue()
        assertThat(dump.forceIdle).isFalse()
        assertThat(dump.screenOn).isTrue()
        assertThat(dump.screenLocked).isFalse()
        assertThat(dump.charging).isTrue()
        assertThat(dump.networkConnected).isTrue()
        assertThat(dump.useMotionSensor).isTrue()
        assertThat(dump.motionActive).isFalse()
        assertThat(dump.notMoving).isFalse()
        assertThat(dump.idlingHistory).isEmpty()
        val allow = dump.allowlist
        assertThat(allow.systemAppsExceptIdle).hasSize(14)
        assertThat(allow.systemApps).hasSize(10)
        assertThat(allow.systemApps).contains("com.google.android.gms")
        assertThat(allow.userApps).isEmpty()
        assertThat(allow.appIdsExceptIdle).hasSize(14)
        assertThat(allow.allAppIds).containsExactly(2000, 10108, 10110, 10112, 10145, 10203, 10204, 10205, 10209, 10212).inOrder()
    }

    @Test
    fun thePhoneDumpHasItsIdlingHistoryAndCountsSamsungsAdditions() {
        val (dump, report) = parse(fixture(Fixtures.PHONE))
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown)
            .containsExactly(
                "settings.skip_sensing",
                1,
                "settings.skip_locating",
                1,
                "settings.dna_request_version",
                1,
                "Allowlist History",
                1,
                "- history count",
                1,
                "Doze Allowlist Gatekeeper",
                1,
            )
        assertThat(dump.settings).hasSize(39)
        assertThat(dump.settings).containsEntry("inactive_to", "+3m0s0ms")
        assertThat(dump.settings).containsEntry("skip_sensing", "true")
        assertThat(dump.motionActive).isTrue()

        val history = dump.idlingHistory
        assertThat(history).hasSize(70)
        assertThat(history.count { it.kind == DozeEventKind.LIGHT_IDLE }).isEqualTo(33)
        assertThat(history.count { it.kind == DozeEventKind.DEEP_IDLE }).isEqualTo(4)
        assertThat(history.count { it.kind == DozeEventKind.NORMAL }).isEqualTo(33)
        assertThat(history.mapNotNull { it.reason }.groupingBy { it }.eachCount())
            .containsExactly("unlocked", 31, "charging", 1, "notification interaction", 1)
        assertThat(history.first()).isEqualTo(DozeEvent(DozeEventKind.LIGHT_IDLE, 42_351_810L, null))
        assertThat(history.last()).isEqualTo(DozeEvent(DozeEventKind.NORMAL, 11_694_606L, "unlocked"))
        // Oldest first.
        assertThat(history.map { checkNotNull(it.msBeforeDump) }.zipWithNext().all { (a, b) -> a >= b }).isTrue()

        val allow = dump.allowlist
        assertThat(allow.systemAppsExceptIdle).hasSize(76)
        assertThat(allow.systemApps).hasSize(73)
        assertThat(allow.userApps).hasSize(35)
        assertThat(allow.userApps.first()).isEqualTo("sw.ovbgd.app")
        assertThat(allow.appIdsExceptIdle).hasSize(109)
        assertThat(allow.userAppIds).hasSize(35)
        assertThat(allow.allAppIds).hasSize(106)
        assertThat(allow.allAppIds.take(2)).containsExactly(1000, 1001).inOrder()
    }

    @Test
    fun linesOnlyPrintedInSomeStatesAreRead() {
        val text =
            listOf(
                "  Settings:",
                "    idle_to=+1h0m0s0ms",
                "  Idling history:",
                "     deep-maint: -1m0s0ms",
                "    light-maint: --",
                "             ??: -5s0ms",
                "  Removed from whitelist system apps:",
                "    com.example.removed",
                "  Temp whitelist schedule:",
                "    UID=10123: -5s0ms - reason with secret",
                "  Temp whitelist app ids:",
                "    10123",
                "  mLightEnabled=true  mDeepEnabled=false",
                "  mForceIdle=true",
                "  mUseMotionSensor=false",
                "  mConstraints={",
                "    \"secret constraint\"=true",
                "  }",
                "  mLocating=true mHasGps=true mHasFused=true mLocated=false",
                "  mLastGpsLocation=Location[gps 12.345678,98.765432 hAcc=5.0 et=+1s0ms]",
                "  mState=IDLE mLightState=OVERRIDE",
                "  mNextLightIdleDelay=+5m0s0ms (flex=+1m0s0ms)",
            ).joinToString("\n", postfix = "\n")
        val (dump, report) = parse(text)
        assertThat(report.complete).isFalse()
        // The `??` label is the platform's own for an event code it does not know.
        assertThat(report.issues.map { it.message }).containsExactly("unreadable history item")
        assertThat(report.unknown).containsExactly("event.??", 1)
        assertThat(dump.deepState).isEqualTo(DeepDozeState.IDLE)
        assertThat(dump.lightState).isEqualTo(LightDozeState.OVERRIDE)
        assertThat(dump.deepEnabled).isFalse()
        assertThat(dump.forceIdle).isTrue()
        assertThat(dump.useMotionSensor).isFalse()
        assertThat(dump.motionActive).isNull()
        assertThat(dump.idlingHistory)
            .containsExactly(
                DozeEvent(DozeEventKind.DEEP_MAINTENANCE, 60_000L, null),
                DozeEvent(DozeEventKind.LIGHT_MAINTENANCE, null, null),
            ).inOrder()
        assertThat(dump.allowlist.removedSystemApps).containsExactly("com.example.removed")
        assertThat(dump.allowlist.tempAppIds).containsExactly(10123)
        assertThat(dump.toString()).doesNotContain("secret")
        assertThat(dump.toString()).doesNotContain("12.345678")
    }

    @Test
    fun aDumpWithoutSettingsOrStateIsIncomplete() {
        val text = fixture(Fixtures.AVD).decodeToString()
        val (_, cut) = parse(text.substring(0, text.indexOf("  mState=")))
        assertThat(cut.complete).isFalse()
        assertThat(cut.issues.map { it.message }).containsExactly("no mState line")

        val (_, other) = parse("Permission Denial: can't dump DeviceIdleController from pid=1\n")
        assertThat(other.complete).isFalse()
        assertThat(other.unknown).containsExactly("<line without key>", 1)
        assertThat(other.issues.map { it.message }).containsExactly("no Settings", "no mState line")

        val (bad, badReport) = parse(text.replace("  mScreenOn=true", "  mScreenOn=maybe").replace("mState=ACTIVE", "mState=DREAMING"))
        assertThat(bad.screenOn).isNull()
        assertThat(bad.deepState).isNull()
        assertThat(badReport.complete).isFalse()
        assertThat(badReport.issues.map { it.message }).containsExactly("unreadable mScreenOn")
        assertThat(badReport.unknown).containsExactly("mState.DREAMING", 1)
    }

    @Test
    fun durationsReadBackAsTimeUtilsPrintsThem() {
        assertThat(Durations.parseMs("0")).isEqualTo(0L)
        assertThat(Durations.parseMs("+500ms")).isEqualTo(500L)
        assertThat(Durations.parseMs("+5s0ms")).isEqualTo(5_000L)
        assertThat(Durations.parseMs("+1m0s0ms")).isEqualTo(60_000L)
        assertThat(Durations.parseMs("+1h0m0s0ms")).isEqualTo(3_600_000L)
        assertThat(Durations.parseMs("-11h45m51s810ms")).isEqualTo(-42_351_810L)
        assertThat(Durations.parseMs("+2d3h0m7s1ms")).isEqualTo(2 * 86_400_000L + 3 * 3_600_000L + 7_001L)
        for (bad in listOf("", "--", "+", "5s0ms", "+1m", "+1x0ms", "+1s 0ms", "+99999999999ms")) {
            assertWithMessage(bad).that(Durations.parseMs(bad)).isNull()
        }
    }

    @Test
    fun noInputEverThrowsAndOnlyTheWholeDumpIsComplete() {
        val whole = fixture(Fixtures.AVD)
        val ends = whole.indices.filter { whole[it] == '\n'.code.toByte() }.toSet()
        for (n in 0 until whole.size) {
            val (_, report) = parse(whole.copyOf(n))
            assertThat(report.bytesRead).isEqualTo(n.toLong())
            // Once the mState line is in, a cut right after a newline is invisible here (ADR-0009).
            if (n - 1 !in ends) assertWithMessage("cut at $n").that(report.complete).isFalse()
        }
        val phone = fixture(Fixtures.PHONE)
        val random = Random(11)
        repeat(200) {
            val damaged = phone.copyOf()
            repeat(20) { damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
            parse(damaged)
        }
        parse(ByteArray(0))
        parse(Random(5).nextBytes(100_000))
    }
}

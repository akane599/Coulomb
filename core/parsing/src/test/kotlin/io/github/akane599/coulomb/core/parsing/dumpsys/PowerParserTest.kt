// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.dumpsys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.model.dumpsys.BatterySaverState
import io.github.akane599.coulomb.core.model.dumpsys.HeldWakeLock
import io.github.akane599.coulomb.core.model.dumpsys.WakeLockLevel
import io.github.akane599.coulomb.core.parsing.Fixtures
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class PowerParserTest {
    private fun parse(bytes: ByteArray) = PowerParser.parse(ByteArrayInputStream(bytes))

    private fun parse(text: String) = parse(text.encodeToByteArray())

    private fun fixture(device: String) = Fixtures.bytes(device, "power.txt")

    private fun partial(
        tag: String,
        uid: Int,
        pid: Int?,
        heldUptimeMs: Long,
        workSourceUid: Int? = null,
    ) = HeldWakeLock(
        level = WakeLockLevel.PARTIAL,
        tag = tag,
        uid = uid,
        pid = pid,
        heldUptimeMs = heldUptimeMs,
        long = true,
        disabled = false,
        acquireCausesWakeup = false,
        onAfterRelease = false,
        systemWakelock = false,
        workSourceUid = workSourceUid,
    )

    /** The smallest dump this parser calls complete, with [wakeLocks] as the entry lines. */
    private fun minimal(vararg wakeLocks: String) =
        (
            listOf(
                "POWER MANAGER (dumpsys power)",
                "",
                "Power Manager State:",
                "  mWakefulness=Asleep",
                "  mIsPowered=false",
                "  mPlugType=0",
                "  mBatteryLevel=41",
                "  mLightDeviceIdleMode=true",
                "  mDeviceIdleMode=false",
                "",
                "Wake Locks: size=${wakeLocks.size}",
            ) + wakeLocks.map { "  $it" } +
                listOf(
                    "",
                    "Suspend Blockers: size=0",
                    "",
                    "Battery Saver: DISABLED",
                )
        ).joinToString("\n", postfix = "\n")

    // Expected values below come from reading the fixture files, not from this parser.

    @Test
    fun theEmulatorDumpHoldsNoWakeLocks() {
        val bytes = fixture(Fixtures.AVD)
        val (dump, report) = parse(bytes)
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).isEmpty()
        assertThat(report.bytesRead).isEqualTo(bytes.size.toLong())
        assertThat(dump.wakefulness).isEqualTo("Awake")
        assertThat(dump.powered).isTrue()
        assertThat(dump.plugType).isEqualTo(1)
        assertThat(dump.batteryLevel).isEqualTo(100)
        assertThat(dump.deviceIdle).isFalse()
        assertThat(dump.lightDeviceIdle).isFalse()
        assertThat(dump.wakeLocks).isEmpty()
        assertThat(dump.batterySaverSupported).isTrue()
        assertThat(dump.batterySaver).isEqualTo(BatterySaverState(false, false, false, false, 0, 0))
    }

    @Test
    fun thePhoneDumpListsItsHeldWakeLocksAndCountsSamsungsAdditions() {
        val (dump, report) = parse(fixture(Fixtures.PHONE))
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).containsExactly("mInterceptedKeyForProximity", 1, "mDefaultDisplayReadyByProximity", 1)
        assertThat(dump.wakefulness).isEqualTo("Awake")
        assertThat(dump.powered).isTrue()
        assertThat(dump.plugType).isEqualTo(1)
        assertThat(dump.batteryLevel).isEqualTo(83)
        assertThat(dump.deviceIdle).isFalse()
        assertThat(dump.batterySaver).isEqualTo(BatterySaverState(false, false, false, false, 0, 0))
        assertThat(dump.wakeLocks)
            .containsExactly(
                // ACQ=-4h45m44s760ms, -32m8s925ms and -10m15s662ms.
                partial("AkaTerminal:terminal", 10525, 4178, 17_144_760L),
                partial("com.gnmiz82.caohghcxtxs::charging_session_measuring", 10526, 13988, 1_928_925L),
                // ws=WorkSource{ chains=WorkChain{(10310), (1041)}}: the chain's first node.
                partial("AudioMix", 1041, null, 615_662L, workSourceUid = 10310),
            ).inOrder()
    }

    @Test
    fun tagsThatLookLikeThePlatformsOwnTextStayInsideTheirEntry() {
        val text =
            minimal(
                "PARTIAL_WAKE_LOCK                 'it's' DISABLED (uid=1)' LONG (uid=10123 isFrozen=true pid=77)",
                "???                               '' ACQUIRE_CAUSES_WAKEUP ON_AFTER_RELEASE SYSTEM_WAKELOCK DISABLED ACQ=0 " +
                    "(uid=1000 isFrozen=false isAttributedUidCached=false ws=WorkSource{10200 com.example} powerGroupId=0)",
                "NEW_WAKE_LOCK                     'x' (uid=-1 isFrozen=false)",
            )
        val (dump, report) = parse(text)
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).containsExactly("level.NEW_WAKE_LOCK", 1)
        assertThat(dump.batterySaverSupported).isFalse()
        assertThat(dump.batterySaver).isNull()
        assertThat(dump.lightDeviceIdle).isTrue()
        val (first, second, third) = checkNotNull(dump.wakeLocks)
        assertThat(first.tag).isEqualTo("it's' DISABLED (uid=1)")
        assertThat(first.uid).isEqualTo(10123)
        assertThat(first.pid).isEqualTo(77)
        assertThat(first.disabled).isFalse()
        assertThat(first.long).isTrue()
        assertThat(first.heldUptimeMs).isNull()
        assertThat(second)
            .isEqualTo(
                HeldWakeLock(
                    level = null,
                    tag = "",
                    uid = 1000,
                    pid = null,
                    heldUptimeMs = 0L,
                    long = false,
                    disabled = true,
                    acquireCausesWakeup = true,
                    onAfterRelease = true,
                    systemWakelock = true,
                    workSourceUid = 10200,
                ),
            )
        assertThat(third.level).isNull()
        assertThat(third.uid).isEqualTo(-1)
    }

    @Test
    fun aTagThatPrintsLinesOfItsOwnIsNotBelieved() {
        val phone = fixture(Fixtures.PHONE).decodeToString()
        val real = "'AkaTerminal:terminal'"
        assertThat(phone).contains(real)

        // An extra entry line: the count no longer matches.
        val (extra, extraReport) = parse(phone.replace(real, "'secret\n  PARTIAL_WAKE_LOCK 'forged' (uid=1000)'"))
        assertThat(extra.wakeLocks).isNull()
        assertThat(extraReport.complete).isFalse()
        assertThat(extraReport.issues.map { it.message }).containsExactly("wake lock lines do not match their count")

        // A forged end of the list.
        val (cut, cutReport) = parse(phone.replace(real, "'secret\n\nSuspend Blockers: size=0'"))
        assertThat(cut.wakeLocks).isNull()
        assertThat(cutReport.issues.map { it.message }).containsExactly("wake lock headings not printed once")

        // Forged state and battery saver sections printed later, as a released tag would be.
        val forged =
            phone.replace(
                "screen on profiler V2:",
                listOf(
                    "Power Manager State:",
                    "  mWakefulness=Asleep",
                    "  my secret=1",
                    "Battery saver state machine:",
                    "  Enabled=true",
                    "screen on profiler V2:",
                ).joinToString("\n"),
            )
        val (later, laterReport) = parse(forged)
        assertThat(later.wakefulness).isEqualTo("Awake")
        assertThat(later.batterySaver).isNull()
        assertThat(later.batterySaverSupported).isNull()
        assertThat(later.wakeLocks).hasSize(3)
        assertThat(laterReport.complete).isFalse()
        assertThat(laterReport.issues.map { it.message })
            .containsExactly("Power Manager State printed more than once", "battery saver printed more than once")
        assertThat(laterReport.unknown.keys).containsExactly("mInterceptedKeyForProximity", "mDefaultDisplayReadyByProximity")
    }

    @Test
    fun linesTooLongToKeepCannotSwapAnEntry() {
        val phone = fixture(Fixtures.PHONE).decodeToString()
        val real = "'AkaTerminal:terminal'"
        val long = "A".repeat(70_000)
        val tail = "B".repeat(70_000)

        // Both long lines are dropped, leaving exactly the declared count of lines.
        val forged = "  PARTIAL_WAKE_LOCK  'victim' ACQ=-5h0m0s0ms LONG (uid=10123 isFrozen=false pid=77)"
        val (swap, swapReport) = parse(phone.replace(real, "'$long\n$forged\n$tail'"))
        assertThat(swap.wakeLocks).isNull()
        assertThat(swapReport.complete).isFalse()
        assertThat(swapReport.issues.map { it.message })
            .containsExactly("line longer than 65536 bytes", "line longer than 65536 bytes", "wake lock lines do not match their count")

        // A line that is only the platform's trailer once threw from the entry reader.
        val (bare, bareReport) = parse(minimal("$long\n  (uid=5)\n$tail"))
        assertThat(bare.wakeLocks).isNull()
        assertThat(bareReport.complete).isFalse()
        assertThat(parse(minimal("(uid=5)")).value.wakeLocks).isNull()
    }

    @Test
    fun aDumpWithoutItsSectionsIsIncomplete() {
        val (dump, report) = parse("Permission Denial: can't dump PowerManager from pid=1\n")
        assertThat(report.complete).isFalse()
        assertThat(report.issues.map { it.message })
            .containsExactly(
                "no power manager header",
                "no mWakefulness line",
                "no mIsPowered line",
                "no mPlugType line",
                "no mBatteryLevel line",
                "no mDeviceIdleMode line",
                "no mLightDeviceIdleMode line",
                "no battery saver state",
                "no Wake Locks heading",
            )
        assertThat(dump.wakeLocks).isNull()
        assertThat(dump.batterySaverSupported).isNull()

        val (bad, badReport) =
            parse(minimal().replace("mIsPowered=false", "mIsPowered=maybe").replace("  mBatteryLevel=41", "  mBatteryLevel=\n  odd line=1"))
        assertThat(bad.powered).isNull()
        assertThat(bad.batteryLevel).isNull()
        assertThat(badReport.complete).isFalse()
        assertThat(badReport.issues.map { it.message }).containsExactly("unreadable mIsPowered", "unreadable mBatteryLevel")
        assertThat(badReport.unknown).containsExactly("<line without key>", 1)
    }

    @Test
    fun noInputEverThrowsAndOnlyTheWholeDumpIsComplete() {
        val whole = fixture(Fixtures.AVD)
        val ends = whole.indices.filter { whole[it] == '\n'.code.toByte() }.toSet()
        // Every cut through the sections read here, then a sample of the rest.
        val sectionsEnd = whole.decodeToString().indexOf("AttentionDetector:")
        val cuts = (0..sectionsEnd + 1) + (sectionsEnd + 1 until whole.size step 101)
        for (n in cuts) {
            val (_, report) = parse(whole.copyOf(n))
            assertThat(report.bytesRead).isEqualTo(n.toLong())
            // A cut right after a newline is invisible here (ADR-0009).
            if (n - 1 !in ends) assertWithMessage("cut at $n").that(report.complete).isFalse()
        }
        val phone = fixture(Fixtures.PHONE)
        val random = Random(13)
        repeat(200) {
            val damaged = phone.copyOf()
            repeat(20) { damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
            parse(damaged)
        }
        parse(ByteArray(0))
        parse(Random(7).nextBytes(100_000))
    }
}

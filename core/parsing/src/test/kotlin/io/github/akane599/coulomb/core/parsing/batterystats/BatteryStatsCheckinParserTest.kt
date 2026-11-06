// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.model.stats.BatteryStatsDump
import io.github.akane599.coulomb.core.model.stats.TimerStat
import io.github.akane599.coulomb.core.model.stats.UidStats
import io.github.akane599.coulomb.core.parsing.Fixtures
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class BatteryStatsCheckinParserTest {
    private fun parse(
        bytes: ByteArray,
        sdk: Int = SDK_37,
    ) = BatteryStatsCheckinParser.parse(ByteArrayInputStream(bytes), sdk)

    private fun parse(text: String) = parse(text.encodeToByteArray())

    private fun checkin(device: String) = Fixtures.bytes(device, "batterystats.checkin.txt")

    private fun proto(device: String) =
        BatteryStatsProtoParser.parse(ByteArrayInputStream(Fixtures.bytes(device, "batterystats.proto.bin"))).value

    @Test
    fun thePhoneDumpParsesWholeAndCountsSamsungsExtraStopReasonColumn() {
        val bytes = checkin(Fixtures.PHONE)
        val (dump, report) = parse(bytes)
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.bytesRead).isEqualTo(bytes.size.toLong())
        // One UI 9 appends stop reason 100 (the proto names it); checkin prints only its count.
        assertThat(report.unknown).containsExactly("jbc.18", 190)
        assertThat(dump.uidsComplete).isTrue()
        assertThat(dump.uids).hasSize(212)
        assertThat(dump.header?.reportVersion).isEqualTo(36)
        assertThat(dump.header?.parcelVersion).isEqualTo(1_376_472L)
        val device = requireNotNull(dump.device)
        assertThat(device.misc?.screenOnMs).isGreaterThan(0L)
        assertThat(device.discharge?.totalMah).isGreaterThan(0L)
        assertThat(device.kernelWakelocks).isNotEmpty()
        assertThat(device.powerUse).isNotEmpty()
        assertThat(dump.uids.single { it.uid == 1000 }.partialWakelocks).isNotEmpty()
        assertThat(dump.uids.flatMap { u -> u.jobStopReasons.map { it.reason } }).doesNotContain("OEM_100")
    }

    @Test
    fun theEmulatorDumpParsesWholeWithNoUnknownSections() {
        val (dump, report) = parse(checkin(Fixtures.AVD))
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).isEmpty()
        assertThat(dump.header?.parcelVersion).isEqualTo(216L)
        assertThat(dump.device).isNotNull()
    }

    @Test
    fun android16HasOneStopReasonFewer() {
        val (dump, report) = parse(checkin(Fixtures.PHONE), sdk = SDK_36)
        assertThat(report.unknown).containsExactly("jbc.17", 190, "jbc.18", 190)
        assertThat(
            dump.uids.flatMap { u ->
                u.jobStopReasons.map { it.reason }
            },
        ).doesNotContain("INTERNAL_STOP_REASON_DEVICE_STATE_BATTERY_SAVER")
    }

    /**
     * Each fixture set holds a proto and a checkin dump taken about 0.3 s apart, so both parsers
     * must agree on everything checkin carries (ADR-0013). Known gaps: checkin truncates CPU times
     * to the millisecond where the proto rounds; it has no timer maxima for jobs, syncs, sensors,
     * Wi-Fi and BLE scans, no brightness counts, and replaces commas in wakelock and alarm names.
     * Power estimates are left out: they differed between the two dumps (see docs/progress.md).
     */
    @Test
    fun checkinAndProtoAgreeOnEveryUidBoth() {
        for (device in Fixtures.ALL) {
            val checkin = parse(checkin(device)).value
            val proto = proto(device)
            val byUid = proto.uids.associateBy { it.uid }
            val shared = checkin.uids.filter { it.uid in byUid }
            assertWithMessage(device).that(shared).isNotEmpty()
            for (c in shared) {
                val p = checkNotNull(byUid[c.uid])
                assertWithMessage("$device uid ${c.uid}").that(comparable(c)).isEqualTo(comparable(p))
                assertWithMessage("$device uid ${c.uid} cpu").that(c.cpuUserMs!! - p.cpuUserMs!!).isIn(-1L..0L)
                assertWithMessage("$device uid ${c.uid} cpu").that(c.cpuSystemMs!! - p.cpuSystemMs!!).isIn(-1L..0L)
            }
            assertWithMessage(device).that(comparableDevice(checkin)).isEqualTo(comparableDevice(proto))
        }
    }

    /** Checkin has no line for a UID with nothing to report, so only UIDs with data are shared. */
    @Test
    fun checkinLeavesOutUidsThatHaveNothingToReport() {
        val checkin =
            parse(checkin(Fixtures.PHONE))
                .value.uids
                .map { it.uid }
                .toSet()
        val missing = proto(Fixtures.PHONE).uids.filter { it.uid !in checkin }
        assertThat(missing).isNotEmpty()
        for (u in missing) {
            assertWithMessage("uid ${u.uid}").that(u.cpuUserMs).isEqualTo(0L)
            assertWithMessage("uid ${u.uid}").that(u.partialWakelocks + u.jobs + u.processStates).isEmpty()
        }
    }

    @Test
    fun aCutStreamKeepsEveryUidBeforeTheCutAndSaysItIsIncomplete() {
        val bytes = checkin(Fixtures.PHONE)
        val whole = parse(bytes).value.uids.associateBy { it.uid }
        var previous = -1
        for (fraction in listOf(0.0, 0.3, 0.6, 0.9, 0.97, 0.999)) {
            val (dump, report) = parse(bytes.copyOf((bytes.size * fraction).toInt()))
            assertThat(report.complete).isFalse()
            assertThat(dump.uidsComplete).isFalse()
            assertThat(dump.uids.size).isAtLeast(previous)
            previous = dump.uids.size
            // What survives is exactly what the whole dump says.
            for (u in dump.uids) assertThat(u).isEqualTo(whole[u.uid])
        }
        // The history fills most of the dump; a cut inside it has no device totals yet.
        val early = parse(bytes.copyOf(bytes.size / 2)).value
        assertThat(early.device).isNull()
        assertThat(early.uids).isEmpty()
    }

    @Test
    fun randomDamageNeverThrows() {
        val bytes = checkin(Fixtures.PHONE)
        val random = Random(SEED)
        repeat(DAMAGE_RUNS) {
            val damaged = bytes.copyOf()
            repeat(DAMAGED_BYTES) { damaged[random.nextInt(damaged.size)] = random.nextInt().toByte() }
            val (_, report) = parse(damaged)
            assertThat(report.bytesRead).isEqualTo(bytes.size.toLong())
        }
        assertThat(parse(ByteArray(0)).report.complete).isFalse()
        assertThat(parse(ByteArray(DAMAGED_BYTES) { 0xFF.toByte() }).report.complete).isFalse()
    }

    @Test
    fun anotherFormatVersionIsNotGuessedAt() {
        val (dump, report) = parse("10,0,i,vers,37,1,A,A\n10,0,l,bt,0,1,1,1,1,1,1,1,1,1,1,1\n")
        assertThat(report.complete).isFalse()
        assertThat(report.issues.map { it.message }).contains("checkin format 10")
        assertThat(dump.header).isNull()
        assertThat(dump.device).isNull()
    }

    @Test
    fun unknownSectionsExtraColumnsAndBadNumbersAreReportedWithoutValues() {
        val text =
            """
            9,0,i,vers,36,216,A,A
            9,0,l,bt,0,1,1,1,1,1,1,1,1,1,1,1
            9,0,l,zz,secret-value
            9,0,l,dc,1,2,3,4,5,6,7,8,9,10,11
            9,1000,l,cpu,10,x,0
            9,1000,l,pr,"name,with,commas",1,2,3,4,5
            9,1000,l,jb,"job",5,1,-1,-1

            """.trimIndent()
        val (dump, report) = parse(text)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).containsExactly("zz", 1, "dc.11", 1)
        assertThat(report.issues.map { it.section to it.message })
            .containsExactly("cpu" to "column 2 is not a number", "pr" to "6 of 7 columns")
        assertThat(report.issues.joinToString()).doesNotContain("secret")
        val uid = dump.uids.single()
        assertThat(uid.cpuUserMs).isEqualTo(10L)
        assertThat(uid.cpuSystemMs).isEqualTo(0L)
        assertThat(uid.processes.single().name).isEqualTo("name,with,commas")
        assertThat(uid.processes.single().crashes).isNull()
        // -1: no background timer, which is not the same as no background time.
        assertThat(uid.jobs.single().backgroundMs).isNull()
        assertThat(report.issues.first { it.section == "cpu" }.offset).isEqualTo(text.indexOf("9,1000,l,cpu").toLong())
    }

    @Test
    fun appPackagesFollowTheAppIdIntoEveryUser() {
        val text =
            """
            9,0,i,vers,36,216,A,A
            9,0,i,uid,10123,com.example
            9,0,l,bt,0,1,1,1,1,1,1,1,1,1,1,1
            9,10123,l,cpu,1,1,0
            9,1010123,l,cpu,1,1,0

            """.trimIndent()
        val uids = parse(text).value.uids
        assertThat(uids.map { it.uid }).containsExactly(10123, 1010123).inOrder()
        assertThat(uids.map { it.packages }).containsExactly(listOf("com.example"), listOf("com.example"))
    }

    private fun TimerStat.withoutMax() = copy(maxMs = null)

    /** A UID with the fields checkin cannot carry exactly cleared. */
    private fun comparable(u: UidStats): UidStats =
        u.copy(
            packages = u.packages.sorted(),
            powerMah = null,
            cpuUserMs = null,
            cpuSystemMs = null,
            partialWakelocks = u.partialWakelocks.map { it.copy(tag = it.tag.replace(NAME_BREAKS, "_")) }.sortedBy { it.tag },
            wakeupAlarms = u.wakeupAlarms.map { it.copy(name = it.name.replace(',', '_')) }.sortedBy { it.name },
            jobs = u.jobs.map { it.copy(total = it.total.withoutMax()) }.sortedBy { it.name },
            jobStopReasons = u.jobStopReasons.filterNot { it.reason.startsWith("OEM_") }.sortedBy { it.job + it.reason },
            syncs = u.syncs.map { it.copy(total = it.total.withoutMax()) }.sortedBy { it.name },
            sensors = u.sensors.map { it.copy(total = it.total.withoutMax()) }.sortedBy { it.id },
            wifi = u.wifi?.let { it.copy(scan = it.scan?.withoutMax()) },
            bluetooth = u.bluetooth?.let { it.copy(bleScan = it.bleScan?.withoutMax()) },
            processes = u.processes.sortedBy { it.name },
        )

    /** Device totals without the realtime clocks (the dumps are 0.3 s apart) and power estimates. */
    private fun comparableDevice(d: BatteryStatsDump) =
        requireNotNull(d.device).let { device ->
            device.copy(
                totalRealtimeMs = null,
                totalUptimeMs = null,
                kernelWakelocks = device.kernelWakelocks.sortedBy { it.name },
                wakeupReasons = device.wakeupReasons.map { it.copy(timer = it.timer.withoutMax()) }.sortedBy { it.name },
                screenBrightness = device.screenBrightness.map { it.copy(count = null) },
                powerUse = device.powerUse.map { it.copy(mah = 0.0) },
                powerSummary = null,
            )
        }

    private companion object {
        const val SDK_36 = 36
        const val SDK_37 = 37
        const val SEED = 9
        const val DAMAGE_RUNS = 30
        const val DAMAGED_BYTES = 16
        val NAME_BREAKS = Regex("[,\\n\\r]")
    }
}

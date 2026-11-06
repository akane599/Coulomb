// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.model.stats.SensorStat
import io.github.akane599.coulomb.core.model.stats.UidProcessState
import io.github.akane599.coulomb.core.parsing.Fixtures
import io.github.akane599.coulomb.core.parsing.aosp.os.BatteryStatsProto
import io.github.akane599.coulomb.core.parsing.aosp.os.SystemProto
import io.github.akane599.coulomb.core.parsing.aosp.os.UidProto
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class BatteryStatsProtoParserTest {
    private fun parse(bytes: ByteArray) = BatteryStatsProtoParser.parse(ByteArrayInputStream(bytes))

    @Test
    fun thePhoneDumpParsesWholeAndCountsSamsungsExtraStopReason() {
        val bytes = Fixtures.bytes(Fixtures.PHONE, "batterystats.proto.bin")
        val (dump, report) = parse(bytes)
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        // One UI 9 adds job stop reason 100, which the AOSP enum lacks (SPEC 16.2: counts are asserted).
        assertThat(report.unknown).containsExactly("UidProto.JobCompletion.ReasonCount.1", 190)
        assertThat(dump.uids.flatMap { u -> u.jobStopReasons.map { it.reason } }).contains("OEM_100")
        assertThat(report.bytesRead).isEqualTo(bytes.size.toLong())
        assertThat(dump.uidsComplete).isTrue()
        assertThat(dump.uids).hasSize(557)
        assertThat(dump.header?.reportVersion).isEqualTo(36)
        assertThat(dump.header?.endPlatformVersion).isEqualTo("CP2A.260605.016")
        val device = requireNotNull(dump.device)
        assertThat(device.startClockTimeWallMs).isNotNull()
        assertThat(device.totalRealtimeMs).isGreaterThan(0L)
        assertThat(device.screenBrightness).isNotEmpty()
        assertThat(device.phoneSignalStrength).isNotEmpty()
        // Android System (uid 1000) always holds partial wakelocks.
        assertThat(dump.uids.single { it.uid == 1000 }.partialWakelocks).isNotEmpty()
        // Zero values are never written: root has no uid field, the top state no state field.
        assertThat(dump.uids.map { it.uid }).contains(0)
        assertThat(dump.uids.flatMap { u -> u.processStates.map { it.state } }).contains(UidProcessState.TOP)
        assertThat(device.phoneSignalStrength.map { it.bin }).contains(0)
        assertThat(device.screenBrightness.map { it.bin }).contains(0)
    }

    @Test
    fun theEmulatorDumpParsesWholeWithNoUnknownFields() {
        val (dump, report) = parse(Fixtures.bytes(Fixtures.AVD, "batterystats.proto.bin"))
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).isEmpty()
        assertThat(dump.uids).hasSize(57)
        assertThat(dump.device).isNotNull()
    }

    @Test
    fun uidsAreUniqueAndGnssIsASensor() {
        val (dump, _) = parse(Fixtures.bytes(Fixtures.PHONE, "batterystats.proto.bin"))
        assertThat(dump.uids.map { it.uid }).containsNoDuplicates()
        val sensorIds = dump.uids.flatMap { u -> u.sensors.map { it.id } }
        assertThat(sensorIds).contains(SensorStat.GPS_SENSOR_ID)
    }

    @Test
    fun aCutStreamKeepsEveryCompleteUidAndSaysItIsIncomplete() {
        val bytes = Fixtures.bytes(Fixtures.PHONE, "batterystats.proto.bin")
        val whole = parse(bytes).value.uids
        var previous = -1
        for (fraction in listOf(0.0, 0.01, 0.1, 0.5, 0.9, 0.97, 0.999)) {
            val cut = bytes.copyOf((bytes.size * fraction).toInt())
            val (dump, report) = parse(cut)
            assertThat(report.complete).isFalse()
            assertThat(dump.uidsComplete).isFalse()
            assertThat(dump.uids.size).isAtLeast(previous)
            previous = dump.uids.size
            // What survives is the whole dump's first UIDs, unchanged.
            assertThat(dump.uids).isEqualTo(whole.take(dump.uids.size))
        }
        // Cut inside the system message (the last field): all UIDs, no device totals.
        val (late, _) = parse(bytes.copyOf(bytes.size - 100))
        assertThat(late.uids).hasSize(557)
        assertThat(late.device).isNull()
    }

    @Test
    fun aBrokenUidCostsOnlyItselfButMakesTheListIncomplete() {
        val good = UidProto(uid = 10_050).encode()
        val stats =
            BatteryStatsProto(
                report_version = 36,
                parcel_version = 216,
                system = SystemProto(battery = SystemProto.Battery(start_clock_time_ms = 1)),
            )
        // uids (field 5) entries: a good one, one whose only byte has wire type 7, another good one.
        val broken = byteArrayOf(UIDS_TAG, 1, 0x0F)
        val inner = stats.encode() + uidEntry(good) + broken + uidEntry(UidProto(uid = 10_051).encode())
        val brokenAt = stats.encode().size + uidEntry(good).size
        val dump = byteArrayOf(DUMP_TAG) + varint(inner.size) + inner
        val (value, report) = parse(dump)
        assertThat(value.uids.map { it.uid }).containsExactly(10_050, 10_051).inOrder()
        assertThat(value.uidsComplete).isFalse()
        assertThat(value.device).isNotNull()
        assertThat(report.complete).isFalse()
        val issue = report.issues.single()
        assertThat(issue.section).isEqualTo("UidProto")
        // The entry's tag byte, after the outer tag and its one-byte length.
        assertThat(issue.offset).isEqualTo((1 + varint(inner.size).size + brokenAt).toLong())
    }

    private fun uidEntry(bytes: ByteArray) = byteArrayOf(UIDS_TAG) + varint(bytes.size) + bytes

    private fun varint(value: Int): ByteArray {
        val out = ArrayList<Byte>()
        var v = value
        while (v >= VARINT_MORE) {
            out += ((v and VARINT_BITS) or VARINT_MORE).toByte()
            v = v ushr VARINT_SHIFT
        }
        out += v.toByte()
        return out.toByteArray()
    }

    @Test
    fun randomDamageNeverThrows() {
        val bytes = Fixtures.bytes(Fixtures.PHONE, "batterystats.proto.bin")
        val random = Random(SEED)
        repeat(DAMAGE_RUNS) {
            val damaged = bytes.copyOf()
            repeat(DAMAGED_BYTES) { damaged[random.nextInt(damaged.size)] = random.nextInt().toByte() }
            val (_, report) = parse(damaged)
            assertThat(report.bytesRead).isAtMost(bytes.size.toLong())
        }
        assertThat(parse(ByteArray(0)).report.complete).isFalse()
        assertThat(parse(ByteArray(DAMAGED_BYTES) { 0xFF.toByte() }).report.complete).isFalse()
    }

    private companion object {
        const val SEED = 36
        const val DAMAGE_RUNS = 40
        const val DAMAGED_BYTES = 16

        /** Field 1 and field 5, length-delimited. */
        const val DUMP_TAG: Byte = 0x0A
        const val UIDS_TAG: Byte = 0x2A
        const val VARINT_MORE = 0x80
        const val VARINT_BITS = 0x7F
        const val VARINT_SHIFT = 7
    }
}

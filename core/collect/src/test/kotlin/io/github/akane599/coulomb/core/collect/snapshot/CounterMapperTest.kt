// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.database.entity.PowerColumns
import io.github.akane599.coulomb.core.database.entity.UidComponentPowerEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.stats.BatteryStatsDump
import io.github.akane599.coulomb.core.model.stats.ComponentPower
import io.github.akane599.coulomb.core.model.stats.DeviceKeys
import io.github.akane599.coulomb.core.model.stats.PowerColumn
import io.github.akane599.coulomb.core.model.stats.TimerStat
import io.github.akane599.coulomb.core.model.stats.UidKeys
import io.github.akane599.coulomb.core.model.stats.UidStats
import io.github.akane599.coulomb.core.model.stats.UsageStatsDump
import io.github.akane599.coulomb.core.model.stats.WakelockStat
import io.github.akane599.coulomb.core.parsing.batterystats.BatteryStatsProtoParser
import org.junit.Test
import java.io.File

class CounterMapperTest {
    private val fixtures = File(requireNotNull(System.getProperty("coulomb.fixtures")) { "coulomb.fixtures not set" })

    private fun phoneDump() =
        File(fixtures, "sm-s938b")
            .listFiles()
            .orEmpty()
            .single { it.isDirectory }
            .let { File(it, "batterystats.proto.bin") }
            .inputStream()
            .use { BatteryStatsProtoParser.parse(it) }
            .value

    @Test
    fun thePhoneDumpBecomesRowsWithUniqueKeysAndNoZeros() {
        val rows = CounterMapper.rows(phoneDump())
        assertThat(rows.uidPower).isNotEmpty()
        assertThat(rows.uidPower.map { Triple(it.uid, it.component, it.processState) }).containsNoDuplicates()
        assertThat(rows.wakeStats.map { Triple(it.uid, it.tag, it.kind) }).containsNoDuplicates()
        assertThat(rows.wakeStats.map { it.kind }.toSet()).containsAtLeast(WakeKind.PARTIAL, WakeKind.KERNEL, WakeKind.WAKEUP_REASON)
        assertThat(rows.alarmStats.map { it.uid }).containsNoDuplicates()
        assertThat(rows.jobStats.map { it.uid }).containsNoDuplicates()
        assertThat(rows.netStats.map { it.uid }).containsNoDuplicates()
        assertThat(rows.uidStats.map { it.uid to it.key }).containsNoDuplicates()
        assertThat(rows.deviceStats.map { it.key }).containsNoDuplicates()
        assertThat(rows.uidStats.all { it.value > 0 }).isTrue()
        assertThat(rows.deviceStats.all { it.value > 0 }).isTrue()
        assertThat(rows.uidPower.all { it.mAh > 0 }).isTrue()
        assertThat(rows.deviceStats.map { it.key }).containsAtLeast(DeviceKeys.TOTAL_REALTIME_MS, DeviceKeys.SCREEN_ON_MS)
        assertThat(
            rows.uidStats
                .map {
                    it.key
                }.toSet(),
        ).containsAtLeast(
            UidKeys.CPU_USER_MS,
            UidKeys.processState(io.github.akane599.coulomb.core.model.stats.UidProcessState.TOP),
        )
        // ADR-0015 assumed 1,000 to 2,500 counter rows per heavy snapshot on this phone.
        val total =
            rows.uidPower.size + rows.wakeStats.size + rows.alarmStats.size + rows.jobStats.size + rows.netStats.size + rows.uidStats.size +
                rows.deviceStats.size
        println("counter rows from the phone fixture: $total")
        assertThat(total).isGreaterThan(100)
    }

    /**
     * The device rows are the power model's device total per component, kept apart from the UIDs;
     * the summary's "computed" total is the measured discharge (`PowerSummary.computedMah`).
     */
    @Test
    fun theDeviceRowsAreTheModelAndTheSummaryIsTheDischarge() {
        for (device in fixtures.listFiles().orEmpty().filter { it.isDirectory }) {
            val set = device.listFiles().orEmpty().single { it.isDirectory }
            val dump = File(set, "batterystats.proto.bin").inputStream().use { BatteryStatsProtoParser.parse(it) }.value
            val summary = checkNotNull(dump.device?.powerSummary) { "${device.name}: no power summary" }
            assertWithMessage(device.name).that(summary.computedMah).isEqualTo(summary.maxDrainedMah)
            val rows = CounterMapper.rows(dump)
            val devicePower = rows.uidPower.filter { it.uid == UidPowerEntity.DEVICE_UID }
            assertWithMessage(device.name).that(devicePower).isNotEmpty()
            assertWithMessage(device.name).that(devicePower.map { it.component }).containsNoDuplicates()
            val uidTotal = rows.uidPower.filter { it.uid >= 0 }.sumOf { it.mAh }
            assertWithMessage(device.name).that(devicePower.sumOf { it.mAh }).isAtLeast(uidTotal - 0.01)
        }
    }

    @Test
    fun packagesComeWithTheirUid() {
        val packages = CounterMapper.packages(phoneDump())
        assertThat(packages).isNotEmpty()
        assertThat(packages.values.all { it.isNotEmpty() }).isTrue()
    }

    /**
     * A tag listed twice under one UID becomes one counter: the actual times held add up, so the
     * row is an upper bound (ADR-0039); counts add, and the longest hold is the longer one.
     */
    @Test
    fun aTagListedTwiceIsOneCounter() {
        val uid =
            UidStats(
                uid = 10_123,
                partialWakelocks =
                    listOf(
                        WakelockStat("sync", TimerStat(totalMs = 500, count = 2, maxMs = 400)),
                        WakelockStat("sync", TimerStat(totalMs = 300, count = 1, maxMs = 300)),
                        WakelockStat("idle", TimerStat(totalMs = 0, count = 0)),
                    ),
            )
        val rows = CounterMapper.rows(BatteryStatsDump(header = null, device = null, uids = listOf(uid), uidsComplete = true))
        val partial = rows.wakeStats.filter { it.kind == WakeKind.PARTIAL }
        assertThat(partial.map { listOf(it.tag, it.totalMs, it.count.toLong(), it.maxMs) })
            .containsExactly(listOf("sync", 800L, 3L, 400L))
    }

    /** ADR-0035 §6: one row per S4 consumer, the device under DEVICE_UID, none for an empty one. */
    @Test
    fun s4BecomesOneRowPerConsumer() {
        val dump =
            UsageStatsDump(
                sessionStartMillis = 1,
                sessionEndMillis = 2,
                device = ComponentPower.of(mapOf(PowerColumn.SCREEN to 900, PowerColumn.CPU to 300)),
                uids =
                    mapOf(
                        10_100 to ComponentPower.of(mapOf(PowerColumn.CPU to 200, PowerColumn.CACHED to 200)),
                        10_200 to ComponentPower.ZERO,
                    ),
                failures = emptyMap(),
            )
        assertThat(CounterMapper.componentRows(dump))
            .containsExactly(
                UidComponentPowerEntity(0, 10_100, PowerColumns(cpu = 200, cached = 200)),
                UidComponentPowerEntity(0, UidPowerEntity.DEVICE_UID, PowerColumns(screen = 900, cpu = 300)),
            )
    }
}

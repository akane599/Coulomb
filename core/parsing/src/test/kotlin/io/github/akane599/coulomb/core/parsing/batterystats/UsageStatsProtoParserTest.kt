// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.model.stats.ComponentPower
import io.github.akane599.coulomb.core.model.stats.PowerColumn
import io.github.akane599.coulomb.core.model.stats.UsageCheck
import io.github.akane599.coulomb.core.parsing.Fixtures
import io.github.akane599.coulomb.core.parsing.aosp.os.BatteryUsageStatsAtomsProto
import io.github.akane599.coulomb.core.parsing.aosp.os.BatteryUsageStatsAtomsProto.BatteryConsumerData
import io.github.akane599.coulomb.core.parsing.aosp.os.BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsage
import io.github.akane599.coulomb.core.parsing.aosp.os.BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsageSlice
import io.github.akane599.coulomb.core.parsing.aosp.os.BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsageSlice.ProcessState
import io.github.akane599.coulomb.core.parsing.aosp.os.BatteryUsageStatsAtomsProto.UidBatteryConsumer
import okio.Buffer
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class UsageStatsProtoParserTest {
    private fun parse(bytes: ByteArray) = UsageStatsProtoParser.parse(ByteArrayInputStream(bytes))

    private fun parse(message: BatteryUsageStatsAtomsProto) = parse(message.encode())

    @Test
    fun theEmulatorReadKeepsOnlyTheFirstEntryOfEachComponentAndSlice() {
        val bytes = Fixtures.bytes(Fixtures.AVD, "usage/unplugged.bin")
        val (dump, report) = parse(bytes)
        assertWithMessage(report.issues.toString()).that(report.issueCount).isEqualTo(0)
        assertThat(report.complete).isTrue()
        assertThat(report.unknown).isEmpty()
        assertThat(report.bytesRead).isEqualTo(bytes.size.toLong())
        val d = requireNotNull(dump)
        assertThat(d.sessionStartMillis).isEqualTo(1_794_164_934_514L)
        assertThat(d.sessionEndMillis).isEqualTo(1_794_165_066_763L)
        // Only Android System has energy: 2 dC of sensors, filed as background. The dump repeats the
        // sensor slice four times and the wakelock slice (time only: 44, 44, 22, 22, 22, 22 ms) six;
        // summing them would give 8 dC of background.
        assertThat(d.uids).containsExactly(1000, power(PowerColumn.SENSORS to 2, PowerColumn.BACKGROUND to 2))
        // No power model: the measured discharge rounds to 0, so the device consumer is left out.
        assertThat(d.device).isNull()
        assertThat(d.failures).containsExactly(UsageCheck.NO_DEVICE, 1)
    }

    @Test
    fun aWholeDumpMapsToTheColumnsAndPasses() {
        val dump =
            parse(
                usage(
                    device =
                        consumer(
                            // The device total is the measured discharge: it need not match.
                            total = 9_000,
                            components = listOf(entry(SCREEN, 3_000), entry(CPU, 2_000), entry(CPU, 1_500), entry(GNSS, 400)),
                        ),
                    uids =
                        listOf(
                            uid(
                                10_123,
                                consumer(
                                    total = 1_210,
                                    components =
                                        listOf(
                                            entry(CPU, 1_000),
                                            entry(CPU, 600), // a screen × power part
                                            entry(WIFI, 150),
                                            entry(1_001, 40),
                                            entry(1_002, 20),
                                            entry(BASE, 0, durationMs = 5_000),
                                        ),
                                    slices =
                                        listOf(
                                            slice(CPU, 700, ProcessState.FOREGROUND),
                                            slice(CPU, 300, ProcessState.FOREGROUND), // a part of it
                                            slice(CPU, 250, ProcessState.BACKGROUND),
                                            slice(CPU, 50, ProcessState.CACHED),
                                            slice(WIFI, 150, ProcessState.FOREGROUND_SERVICE),
                                            slice(1_001, 40, ProcessState.BACKGROUND),
                                        ),
                                ),
                            ),
                        ),
                ),
            ).value!!
        assertThat(dump.failures).isEmpty()
        assertThat(dump.device).isEqualTo(power(PowerColumn.SCREEN to 3_000, PowerColumn.CPU to 2_000, PowerColumn.GNSS to 400))
        val app = dump.uids.getValue(10_123)
        assertThat(app)
            .isEqualTo(
                power(
                    PowerColumn.CPU to 1_000,
                    PowerColumn.WIFI to 150,
                    PowerColumn.CUSTOM to 60,
                    PowerColumn.FOREGROUND to 700,
                    PowerColumn.BACKGROUND to 290,
                    PowerColumn.FOREGROUND_SERVICE to 150,
                    PowerColumn.CACHED to 50,
                ),
            )
        assertThat(app.componentSum).isEqualTo(1_210)
    }

    @Test
    fun aUidMustAddUpWithinHalfADeciCoulombPerRounding() {
        // 19 component ids and no custom ones: 20 roundings, so 10 dC either way.
        fun failuresFor(total: Long) = parse(usage(uids = listOf(uid(10_001, consumer(total, listOf(entry(CPU, 500))))))).value!!.failures
        assertThat(failuresFor(510)).isEmpty()
        assertThat(failuresFor(490)).isEmpty()
        assertThat(failuresFor(511)).containsExactly(UsageCheck.UID_SUM, 1)
        assertThat(failuresFor(489)).containsExactly(UsageCheck.UID_SUM, 1)
    }

    @Test
    fun customIdsAnywhereInTheDumpWidenTheRounding() {
        // Two custom ids in the layout (one only in the component models): 22 roundings, 11 dC.
        val message =
            usage(uids = listOf(uid(10_001, consumer(511, listOf(entry(CPU, 500))))), customElsewhere = listOf(1_000, 1_001))
        assertThat(parse(message).value!!.failures).isEmpty()
    }

    @Test
    fun theReattributedAndBaseIdsMustCarryNoEnergy() {
        for (id in listOf(REATTRIBUTED, BASE)) {
            val asComponent = usage(uids = listOf(uid(10_001, consumer(500, listOf(entry(CPU, 500), entry(id, 3))))))
            assertThat(parse(asComponent).value!!.failures).containsExactly(UsageCheck.NO_ENERGY_ID, 1)
            val asSlice =
                usage(
                    uids =
                        listOf(
                            uid(10_001, consumer(500, listOf(entry(CPU, 500)), listOf(slice(id, 3, ProcessState.BACKGROUND)))),
                        ),
                )
            assertThat(parse(asSlice).value!!.failures).containsKey(UsageCheck.NO_ENERGY_ID)
        }
    }

    @Test
    fun noValueMayBeNegative() {
        val energy = usage(uids = listOf(uid(10_001, consumer(500, listOf(entry(CPU, 510), entry(WIFI, -10))))))
        assertThat(parse(energy).value!!.failures).containsExactly(UsageCheck.NEGATIVE, 1)
        val duration = usage(uids = listOf(uid(10_001, consumer(500, listOf(entry(CPU, 500, durationMs = -1))))))
        assertThat(parse(duration).value!!.failures).containsExactly(UsageCheck.NEGATIVE, 1)
        val deviceTotal = usage(device = consumer(-5, listOf(entry(CPU, 1))))
        assertThat(parse(deviceTotal).value!!.failures).containsExactly(UsageCheck.NEGATIVE, 1)
    }

    @Test
    fun slicesMayNotAddUpToMoreThanTheirComponent() {
        fun failuresFor(vararg parts: Long) =
            parse(
                usage(
                    uids =
                        listOf(
                            uid(
                                10_001,
                                consumer(
                                    100,
                                    listOf(entry(CPU, 100)),
                                    parts.mapIndexed { i, p -> slice(CPU, p, ProcessState.entries[i + 1]) },
                                ),
                            ),
                        ),
                ),
            ).value!!.failures
        // Two slices and the total: three roundings, so 1.5 dC over is still rounding.
        assertThat(failuresFor(60, 40)).isEmpty()
        assertThat(failuresFor(61, 40)).isEmpty()
        assertThat(failuresFor(62, 40)).containsExactly(UsageCheck.SLICES_ABOVE_TOTAL, 1)
        // Less than the total is fine: some energy may have no state to file it under.
        assertThat(failuresFor(10, 10)).isEmpty()
    }

    @Test
    fun aSliceWithoutAComponentTotalCountsAgainstZero() {
        val message =
            usage(uids = listOf(uid(10_001, consumer(100, listOf(entry(CPU, 100)), listOf(slice(GNSS, 30, ProcessState.BACKGROUND))))))
        assertThat(parse(message).value!!.failures).containsExactly(UsageCheck.SLICES_ABOVE_TOTAL, 1)
    }

    @Test
    fun aMissingDeviceConsumerFails() {
        val dump = parse(BatteryUsageStatsAtomsProto(session_start_millis = 1L)).value!!
        assertThat(dump.device).isNull()
        assertThat(dump.uids).isEmpty()
        assertThat(dump.failures).containsExactly(UsageCheck.NO_DEVICE, 1)
    }

    @Test
    fun unknownIdsGoToOtherWithOneIssuePerDump() {
        val (dump, report) =
            parse(
                usage(
                    uids =
                        listOf(
                            uid(
                                10_001,
                                consumer(
                                    100,
                                    listOf(
                                        entry(SYSTEM_SERVICES, 10),
                                        entry(MEMORY, 10),
                                        entry(IDLE, 10),
                                        entry(40, 20),
                                        entry(12_000, 50),
                                    ),
                                ),
                            ),
                        ),
                ),
            )
        assertThat(dump!!.failures).isEmpty()
        assertThat(dump.uids.getValue(10_001)).isEqualTo(power(PowerColumn.OTHER to 100))
        assertThat(report.issueCount).isEqualTo(1)
        assertThat(report.unknown)
            .containsExactly("PowerComponentUsage.component.40", 1, "PowerComponentUsage.component.12000", 1)
    }

    @Test
    fun anUnknownProcessStateStaysOutOfTheStateColumns() {
        val slice = slice(CPU, 40, ProcessState.BACKGROUND)
        val unknownState =
            slice.copy(
                process_state = null,
                unknownFields = Buffer().writeByte(PROCESS_STATE_KEY).writeByte(NEW_STATE).readByteString(),
            )
        val unspecified = slice(CPU, 30, ProcessState.UNSPECIFIED)
        val (dump, report) =
            parse(usage(uids = listOf(uid(10_001, consumer(100, listOf(entry(CPU, 100)), listOf(unknownState, unspecified))))))
        assertThat(dump!!.failures).isEmpty()
        assertThat(dump.uids.getValue(10_001)).isEqualTo(power(PowerColumn.CPU to 100))
        assertThat(report.unknown)
            .containsExactly("PowerComponentUsageSlice.process_state.$NEW_STATE", 1, "PowerComponentUsageSlice.process_state.0", 1)
    }

    @Test
    fun aUidWithNoComponentEntryIsAbsentButStillChecked() {
        val timeOnly = UidBatteryConsumer(uid = 10_002, time_in_background_millis = 1L)
        val dropped = uid(10_003, consumer(50, emptyList()))
        val dump = parse(usage(uids = listOf(timeOnly, dropped))).value!!
        assertThat(dump.uids).isEmpty()
        assertThat(dump.failures).containsExactly(UsageCheck.UID_SUM, 1)
    }

    @Test
    fun anAbsentUidIsRootAndAnAbsentComponentIsTheScreen() {
        val root = UidBatteryConsumer(battery_consumer_data = consumer(40, listOf(PowerComponentUsage(power_deci_coulombs = 40L))))
        val dump = parse(usage(uids = listOf(root))).value!!
        assertThat(dump.uids).containsExactly(0, power(PowerColumn.SCREEN to 40))
    }

    @Test
    fun aRepeatedUidFails() {
        val a = uid(10_001, consumer(100, listOf(entry(CPU, 100))))
        val dump = parse(usage(uids = listOf(a, a))).value!!
        assertThat(dump.failures).containsExactly(UsageCheck.DUPLICATE_UID, 1)
        assertThat(dump.uids.keys).containsExactly(10_001)
    }

    @Test
    fun aCutOrBrokenStreamGivesNoDumpAndNeverThrows() {
        val bytes = Fixtures.bytes(Fixtures.AVD, "usage/unplugged.bin")
        // Cut inside the first UID: the length prefix promises more than there is.
        val (cut, cutReport) = parse(bytes.copyOf(30))
        assertThat(cut).isNull()
        assertThat(cutReport.complete).isFalse()
        assertThat(cutReport.issueCount).isEqualTo(1)
        val random = Random(35)
        repeat(500) {
            val noise = bytes.copyOf()
            repeat(1 + random.nextInt(8)) { noise[random.nextInt(noise.size)] = random.nextInt(256).toByte() }
            parse(noise)
            parse(random.nextBytes(random.nextInt(64)))
        }
    }

    private fun power(vararg columns: Pair<PowerColumn, Int>): ComponentPower =
        ComponentPower.of(
            columns.associate {
                it.first to
                    it.second.toLong()
            },
        )

    private fun usage(
        device: BatteryConsumerData? = consumer(1_000, listOf(entry(CPU, 1_000))),
        uids: List<UidBatteryConsumer> = emptyList(),
        customElsewhere: List<Int> = emptyList(),
    ) = BatteryUsageStatsAtomsProto(
        session_start_millis = 1_794_000_000_000L,
        session_end_millis = 1_794_000_600_000L,
        device_battery_consumer = device,
        uid_battery_consumers = uids,
        component_models = customElsewhere.map { BatteryUsageStatsAtomsProto.PowerComponentModel(component = it) },
    )

    private fun uid(
        uid: Int,
        data: BatteryConsumerData,
    ) = UidBatteryConsumer(uid = uid, battery_consumer_data = data)

    private fun consumer(
        total: Long,
        components: List<PowerComponentUsage>,
        slices: List<PowerComponentUsageSlice> = emptyList(),
    ) = BatteryConsumerData(total_consumed_power_deci_coulombs = total, power_components = components, slices = slices)

    private fun entry(
        component: Int,
        deciCoulombs: Long,
        durationMs: Long = 0,
    ) = PowerComponentUsage(component = component, power_deci_coulombs = deciCoulombs, duration_millis = durationMs)

    private fun slice(
        component: Int,
        deciCoulombs: Long,
        state: ProcessState,
    ) = PowerComponentUsageSlice(power_component = entry(component, deciCoulombs), process_state = state)

    private companion object {
        // BatteryConsumer.java L78-111.
        const val SCREEN = 0
        const val CPU = 1
        const val SYSTEM_SERVICES = 7
        const val GNSS = 10
        const val WIFI = 11
        const val MEMORY = 13
        const val IDLE = 16
        const val REATTRIBUTED = 17
        const val BASE = 18

        /** Field 2 (process_state), varint. */
        const val PROCESS_STATE_KEY = 2 shl 3
        const val NEW_STATE = 9
    }
}

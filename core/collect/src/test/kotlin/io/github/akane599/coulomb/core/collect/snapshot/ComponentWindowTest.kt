// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.database.entity.DeviceStatEntity
import io.github.akane599.coulomb.core.database.entity.PowerColumns
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.UidComponentPowerEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity.Companion.DEVICE_UID
import io.github.akane599.coulomb.core.database.entity.WindowComponentEntity
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity.Companion.OTHER_UID
import io.github.akane599.coulomb.core.model.Breakdown
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.stats.DeviceKeys
import org.junit.Test

/** ADR-0035 §5, rule by rule, in the order they run. */
class ComponentWindowTest {
    private val start = 1_000_000_000_000L

    /** One snapshot end: its S4 status, S5's two realtimes and S4's cumulative rows. */
    private fun end(
        id: Long,
        power: Map<Int, PowerColumns>,
        realtimeMs: Long,
        batteryMs: Long = realtimeMs,
        s4: String? = "ok",
        trigger: SnapshotTrigger = SnapshotTrigger.SCREEN_OFF,
        statsStart: Long? = start,
    ) = WindowEnd(
        SnapshotEntity(
            id = id,
            bootSeq = 1,
            elapsedNs = realtimeMs * 1_000_000,
            wallMs = start + realtimeMs,
            trigger = trigger,
            kind = SnapshotKind.HEAVY,
            levelPct = 50,
            chargeUah = null,
            currentRaw = null,
            voltageMv = null,
            tempDeciC = null,
            plugType = 0,
            status = null,
            statsStartWall = statsStart,
            statsRealtimeMs = realtimeMs,
            historyCursorMs = null,
            historyEventId = null,
            sourceStatusJson = if (s4 == null) """{"S5":"ok"}""" else """{"S4":"$s4","S5":"ok"}""",
            blobPath = null,
            partial = false,
            countersPruned = false,
            durationMs = 0,
        ),
        SnapshotRows(
            deviceStats =
                listOf(
                    DeviceStatEntity(id, DeviceKeys.TOTAL_REALTIME_MS, realtimeMs),
                    DeviceStatEntity(id, DeviceKeys.BATTERY_REALTIME_MS, batteryMs),
                ),
            componentPower = power.map { (uid, columns) -> UidComponentPowerEntity(id, uid, columns) },
        ),
    )

    private val before =
        mapOf(
            DEVICE_UID to PowerColumns(cpu = 1_000, screen = 400),
            10_100 to PowerColumns(cpu = 500, foreground = 500),
            10_200 to PowerColumns(wifi = 40),
        )

    /**
     * Over the window: the device 10 mAh (360 dC) and 50 dC of custom energy; the kept app 6 mAh
     * (216 dC) in CPU and custom; two other apps 2 mAh each, one new at the later end.
     */
    private val after =
        mapOf(
            DEVICE_UID to PowerColumns(cpu = 1_300, screen = 460, custom = 50),
            10_100 to PowerColumns(cpu = 680, custom = 36, foreground = 716),
            10_200 to PowerColumns(wifi = 112),
            10_300 to PowerColumns(gnss = 72),
        )

    private val uidMah = mapOf(10_100 to 6.0, OTHER_UID to 4.0)
    private val kept = setOf(10_100, OWN_UID)

    private fun build(
        from: WindowEnd = end(1, before, realtimeMs = 0),
        to: WindowEnd = end(2, after, realtimeMs = 600_000),
        modeledMah: Double? = 10.0,
        uids: Map<Int, Double> = uidMah,
    ) = ComponentWindow.build(from, to, modeledMah, uids, kept)

    @Test
    fun aWindowWhollyOnBatteryIsBrokenDownIntoKeptOtherAndDevice() {
        val result = build()

        assertThat(result.breakdown).isEqualTo(Breakdown.BROKEN_DOWN)
        // Coulomb's own UID changed in no column, so it has no row.
        assertThat(result.rows)
            .containsExactly(
                WindowComponentEntity(0, 10_100, PowerColumns(cpu = 180, custom = 36, foreground = 216)),
                WindowComponentEntity(0, OTHER_UID, PowerColumns(wifi = 72, gnss = 72)),
                WindowComponentEntity(0, DEVICE_UID, PowerColumns(cpu = 300, screen = 60, custom = 50)),
            )
    }

    @Test
    fun s4OffOrNeverReadAtAnEndIsTheFirstReason() {
        assertThat(build(from = end(1, before, 0, s4 = "off")).breakdown).isEqualTo(Breakdown.S4_OFF)
        assertThat(build(from = end(1, before, 0, s4 = null)).breakdown).isEqualTo(Breakdown.S4_OFF)
        // Off wins over every later rule.
        assertThat(build(to = end(2, after, 600_000, s4 = "off"), modeledMah = null).breakdown).isEqualTo(Breakdown.S4_OFF)
    }

    @Test
    fun anEndNotOkOrWithoutItsDeviceRowIsNotUsed() {
        for (status in listOf("partial", "failed", "skipped", "slept")) {
            assertThat(build(to = end(2, after, 600_000, s4 = status)).breakdown).isEqualTo(Breakdown.END_NOT_OK)
        }
        assertThat(build(from = end(1, before - DEVICE_UID, 0)).breakdown).isEqualTo(Breakdown.END_NOT_OK)
    }

    @Test
    fun withoutS5sModeledTotalThereIsNothingToCheckAgainst() {
        val result = build(modeledMah = null)
        assertThat(result.breakdown).isEqualTo(Breakdown.NO_MODELED_TOTAL)
        assertThat(result.rows).isEmpty()
    }

    @Test
    fun batteryTimeMayTrailByOneSecondOrFiveAtThePlugIn() {
        assertThat(build(to = end(2, after, 600_000, batteryMs = 599_000)).breakdown).isEqualTo(Breakdown.BROKEN_DOWN)
        assertThat(build(to = end(2, after, 600_000, batteryMs = 598_999)).breakdown).isEqualTo(Breakdown.NOT_ON_BATTERY)
        val plugIn = SnapshotTrigger.POWER_CONNECTED
        assertThat(build(to = end(2, after, 600_000, 595_000, trigger = plugIn)).breakdown).isEqualTo(Breakdown.BROKEN_DOWN)
        assertThat(build(to = end(2, after, 600_000, 594_999, trigger = plugIn)).breakdown).isEqualTo(Breakdown.NOT_ON_BATTERY)
        // A plug-in at the earlier end gets no such allowance.
        assertThat(build(from = end(1, before, 0, trigger = plugIn), to = end(2, after, 600_000, 598_000)).breakdown)
            .isEqualTo(Breakdown.NOT_ON_BATTERY)
    }

    @Test
    fun anotherSessionOrNoneIsNotOnBattery() {
        assertThat(build(to = end(2, after, 600_000, statsStart = start + 1)).breakdown).isEqualTo(Breakdown.NOT_ON_BATTERY)
        assertThat(build(from = end(1, before, 0, statsStart = null)).breakdown).isEqualTo(Breakdown.NOT_ON_BATTERY)
    }

    @Test
    fun aColumnLowerByMoreThanOneDcOrAMissingConsumerWentDown() {
        val oneLower = after + (10_200 to PowerColumns(wifi = 39, gnss = 73))
        assertThat(build(to = end(2, oneLower, 600_000)).breakdown).isEqualTo(Breakdown.BROKEN_DOWN)
        val twoLower = after + (10_200 to PowerColumns(wifi = 38, gnss = 74))
        assertThat(build(to = end(2, twoLower, 600_000)).breakdown).isEqualTo(Breakdown.WENT_DOWN)
        assertThat(build(to = end(2, after - 10_200, 600_000)).breakdown).isEqualTo(Breakdown.WENT_DOWN)
        val deviceLower = after + (DEVICE_UID to PowerColumns(cpu = 1_660, screen = 398, custom = 50))
        assertThat(build(to = end(2, deviceLower, 600_000)).breakdown).isEqualTo(Breakdown.WENT_DOWN)
    }

    @Test
    fun theDeviceRowWithoutCustomMustMatchTheModeledTotal() {
        // The device's 360 dC (50 dC of custom left out) against 318 dC: 42 away, inside 36 plus 2% of 318.
        assertThat(build(modeledMah = 318 / 36.0).breakdown).isEqualTo(Breakdown.BROKEN_DOWN)
        assertThat(build(modeledMah = 8.5).breakdown).isEqualTo(Breakdown.DISAGREES)
        assertThat(build(modeledMah = 11.25).breakdown).isEqualTo(Breakdown.DISAGREES)
    }

    @Test
    fun eachUidRowIncludingOtherMustMatchItsS5Total() {
        // The kept app's 216 dC include custom; 6.5 mAh is 234 dC, 18 away, inside 18 plus 2% of 234.
        assertThat(build(uids = uidMah + (10_100 to 6.5)).breakdown).isEqualTo(Breakdown.BROKEN_DOWN)
        assertThat(build(uids = uidMah + (10_100 to 6.7)).breakdown).isEqualTo(Breakdown.DISAGREES)
        assertThat(build(uids = uidMah + (OTHER_UID to 5.0)).breakdown).isEqualTo(Breakdown.DISAGREES)
        // A UID S5 charged that S4 has no change for.
        assertThat(build(uids = uidMah + (OWN_UID to 1.0)).breakdown).isEqualTo(Breakdown.DISAGREES)
    }

    private companion object {
        const val OWN_UID = 10_250
    }
}

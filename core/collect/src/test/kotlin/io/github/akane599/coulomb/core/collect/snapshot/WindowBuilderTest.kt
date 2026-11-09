// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.database.entity.DeviceStatEntity
import io.github.akane599.coulomb.core.database.entity.NetStatEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.WakeStatEntity
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.stats.DeviceKeys
import io.github.akane599.coulomb.core.model.stats.UidKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowBuilderTest {
    private val hourMs = 3_600_000L

    private fun snapshot(
        id: Long,
        elapsedMs: Long,
        chargeUah: Long?,
        levelPct: Int?,
        statsRealtimeMs: Long = elapsedMs,
        bootSeq: Int = 1,
    ) = SnapshotEntity(
        id = id,
        bootSeq = bootSeq,
        elapsedNs = elapsedMs * 1_000_000,
        wallMs = 1_000_000_000_000 + elapsedMs,
        trigger = SnapshotTrigger.SCREEN_OFF,
        kind = SnapshotKind.HEAVY,
        levelPct = levelPct,
        chargeUah = chargeUah,
        currentRaw = null,
        voltageMv = null,
        tempDeciC = null,
        plugType = 0,
        status = null,
        statsStartWall = 1_000_000_000_000,
        statsRealtimeMs = statsRealtimeMs,
        historyCursorMs = null,
        historyEventId = null,
        sourceStatus = 0,
        blobPath = null,
        partial = false,
        countersPruned = false,
        durationMs = 0,
    )

    private fun power(
        uid: Int,
        mAh: Double,
    ) = UidPowerEntity(0, uid, "cpu", UidPowerEntity.ANY_STATE, mAh, Provenance.MODELED)

    private fun device(
        realtimeMs: Long,
        screenOnMs: Long,
        screenOffUptimeMs: Long,
        dischargeUah: Long? = null,
    ) = listOfNotNull(
        DeviceStatEntity(0, DeviceKeys.TOTAL_REALTIME_MS, realtimeMs),
        DeviceStatEntity(0, DeviceKeys.SCREEN_ON_MS, screenOnMs),
        DeviceStatEntity(0, DeviceKeys.SCREEN_OFF_UPTIME_MS, screenOffUptimeMs),
        dischargeUah?.let { DeviceStatEntity(0, DeviceKeys.STATS_DISCHARGE_UAH, it) },
    )

    @Test
    fun aWindowTakesTheCounterDeltaAndTheModeledTotal() {
        val from =
            WindowEnd(
                snapshot(1, hourMs, chargeUah = 3_000_000, levelPct = 80),
                SnapshotRows(
                    uidPower = listOf(power(10_100, 5.0), power(10_200, 1.0), devicePower("UNKNOWN", 6.0)),
                    deviceStats = device(hourMs, 600_000, 100_000),
                ),
            )
        val to =
            WindowEnd(
                snapshot(2, 3 * hourMs, chargeUah = 2_900_000, levelPct = 78),
                SnapshotRows(
                    uidPower = listOf(power(10_100, 65.0), power(10_200, 21.0), devicePower("UNKNOWN", 86.0)),
                    deviceStats = device(3 * hourMs, 1_800_000, 400_000),
                ),
            )

        val (window, rows) =
            WindowBuilder.build(
                WindowKind.CONSECUTIVE,
                null,
                listOf(from, to),
                SETTINGS,
                withDetail = true,
            )

        assertEquals(100.0, window.measuredMah!!, 1e-9)
        assertEquals(ChargeSource.CHARGE_COUNTER, window.chargeSource)
        assertEquals(80.0, window.modeledMah!!, 1e-9)
        assertEquals(20.0, window.unattributedMah!!, 1e-9)
        assertEquals(1_200_000L, window.screenOnMs)
        assertEquals(2 * hourMs - 1_200_000, window.screenOffMs)
        assertEquals(300_000L, window.awakeMsScreenOff)
        assertEquals(0.99, window.quality, 1e-9)
        assertEquals(
            setOf(10_100 to 60.0, 10_200 to 20.0, UidPowerEntity.DEVICE_UID to 80.0),
            rows.uids.map { it.uid to it.mAh }.toSet(),
        )
    }

    @Test
    fun anUnreliableCounterFallsBackToTheLevelTimesTheCapacity() {
        val from = WindowEnd(snapshot(1, 0, chargeUah = 3_000_000, levelPct = 80), SnapshotRows())
        val to = WindowEnd(snapshot(2, hourMs, chargeUah = 3_000_000, levelPct = 70), SnapshotRows())

        val (window, _) =
            WindowBuilder.build(
                WindowKind.CONSECUTIVE,
                null,
                listOf(from, to),
                SETTINGS.copy(counterReliable = false),
                withDetail = false,
            )

        assertEquals(500.0, window.measuredMah!!, 1e-9)
        assertEquals(ChargeSource.LEVEL_DELTA, window.chargeSource)
        assertNull("no power model read: no modeled total", window.modeledMah)
        assertNull("no device counters: awake time unknown", window.awakeMsScreenOff)
        assertNull(window.unattributedMah)
        assertTrue(window.detailPruned)
        assertEquals(0.45, window.quality, 1e-9)
    }

    @Test
    fun smallUidsAreSummedIntoOther() {
        val many = (0 until 40).map { power(10_000 + it, 0.0) }
        val later = (0 until 40).map { power(10_000 + it, if (it < 35) it + 1.0 else 0.05) }
        val from = WindowEnd(snapshot(1, 0, 3_000_000, 80), SnapshotRows(uidPower = many))
        val to = WindowEnd(snapshot(2, hourMs, 2_000_000, 60), SnapshotRows(uidPower = later))

        val (window, rows) = WindowBuilder.build(WindowKind.CONSECUTIVE, null, listOf(from, to), SETTINGS, withDetail = true)

        val uids = rows.uids.map { it.uid }.toSet()
        assertEquals(WindowBuilder.MAX_UIDS + 1, uids.size)
        assertTrue(WindowUidEntity.OTHER_UID in uids)
        // The five smallest of 35 (1 to 5 mAh) and the five below the threshold (0.05 each).
        assertEquals(15.25, rows.uids.single { it.uid == WindowUidEntity.OTHER_UID }.mAh, 1e-9)
        assertEquals(630.25, rows.uids.sumOf { it.mAh }, 1e-9)
        assertNull("no model total stored: modeled drain unknown", window.modeledMah)
    }

    @Test
    fun aResetBetweenTheEndsCountsTheLaterValuesWhole() {
        val from =
            WindowEnd(
                snapshot(1, 10 * hourMs, 3_000_000, 80, statsRealtimeMs = 10 * hourMs),
                SnapshotRows(uidPower = listOf(power(10_100, 90.0)), wakeStats = listOf(wake(10_100, 50_000))),
            )
        val to =
            WindowEnd(
                snapshot(2, 11 * hourMs, 2_950_000, 79, statsRealtimeMs = hourMs / 2),
                SnapshotRows(uidPower = listOf(power(10_100, 7.0)), wakeStats = listOf(wake(10_100, 4_000))),
            )

        val (window, rows) = WindowBuilder.build(WindowKind.CONSECUTIVE, null, listOf(from, to), SETTINGS, withDetail = true)

        assertEquals(7.0, rows.uids.single().mAh, 1e-9)
        assertEquals(4_000L, rows.wakes.single().totalMs)
        assertTrue("a reset leaves part of the window uncounted", window.quality < 1.0)
    }

    @Test
    fun statsCarryNetworkAndDeviceCountersPerUid() {
        val from =
            WindowEnd(
                snapshot(1, 0, 3_000_000, 80),
                SnapshotRows(
                    uidPower = listOf(power(10_100, 0.0)),
                    netStats = listOf(NetStatEntity(0, 10_100, 1_000, 0, null, null, null)),
                    deviceStats = device(0, 0, 0),
                ),
            )
        val to =
            WindowEnd(
                snapshot(2, hourMs, 2_900_000, 78),
                SnapshotRows(
                    uidPower = listOf(power(10_100, 2.0)),
                    netStats = listOf(NetStatEntity(0, 10_100, 6_000, 0, null, null, null)),
                    deviceStats = device(hourMs, 60_000, 30_000),
                ),
            )

        val (_, rows) = WindowBuilder.build(WindowKind.CONSECUTIVE, null, listOf(from, to), SETTINGS, withDetail = true)

        val stats = rows.stats.associate { (it.uid to it.key) to it.value }
        assertEquals(5_000L, stats[10_100 to UidKeys.MOBILE_RX_BYTES])
        assertEquals(60_000L, stats[UidPowerEntity.DEVICE_UID to DeviceKeys.SCREEN_ON_MS])
        assertTrue(rows.stats.all { it.provenance == Provenance.MEASURED })
    }

    @Test
    fun aScreenOffCounterMissingAtBothEndsMeansNoScreenOffAwakeTime() {
        val onOnly = { ms: Long -> listOf(DeviceStatEntity(0, DeviceKeys.SCREEN_ON_MS, ms)) }
        val from = WindowEnd(snapshot(1, 0, 3_000_000, 80), SnapshotRows(deviceStats = onOnly(1_000)))
        val to = WindowEnd(snapshot(2, hourMs, 2_900_000, 78), SnapshotRows(deviceStats = onOnly(hourMs)))

        val (window, _) = WindowBuilder.build(WindowKind.CONSECUTIVE, null, listOf(from, to), SETTINGS, withDetail = false)

        assertEquals(0L, window.awakeMsScreenOff)
    }

    @Test
    fun aChainKeepsItsEndsAndTheSnapshotsAroundEachReset() {
        val chain =
            listOf(
                snapshot(1, hourMs, 3_000_000, 80),
                snapshot(2, 2 * hourMs, 2_900_000, 78),
                snapshot(3, 3 * hourMs, 2_800_000, 76),
                // Reset between 3 and 4: the counted realtime starts again.
                snapshot(4, 4 * hourMs, 2_700_000, 74, statsRealtimeMs = hourMs / 4),
                snapshot(5, 5 * hourMs, 2_600_000, 72, statsRealtimeMs = hourMs + hourMs / 4),
                snapshot(6, 6 * hourMs, 2_500_000, 70, statsRealtimeMs = 2 * hourMs + hourMs / 4),
            )

        assertEquals(listOf(1L, 3L, 4L, 6L), WindowBuilder.links(chain).map { it.id })
        assertEquals(listOf(1L, 2L), WindowBuilder.links(chain.take(2)).map { it.id })
    }

    @Test
    fun theModeledTotalIsTheDeviceRowsNotTheAppsOrTheDischarge() {
        val from =
            WindowEnd(
                snapshot(1, 0, 3_000_000, 80),
                SnapshotRows(
                    uidPower = listOf(power(10_100, 5.0), power(10_200, 1.0), devicePower("CPU", 10.0), devicePower("SCREEN", 20.0)),
                    deviceStats = device(0, 0, 0, dischargeUah = 30_000),
                ),
            )
        val to =
            WindowEnd(
                snapshot(2, hourMs, 2_820_000, 77),
                SnapshotRows(
                    uidPower = listOf(power(10_100, 65.0), power(10_200, 21.0), devicePower("CPU", 110.0), devicePower("SCREEN", 60.0)),
                    deviceStats = device(hourMs, 0, 0, dischargeUah = 210_000),
                ),
            )

        val (window, rows) = WindowBuilder.build(WindowKind.CONSECUTIVE, null, listOf(from, to), SETTINGS, withDetail = true)

        // 140 mAh: the device rows, with the apps' 80 inside them; the platform measured 180.
        assertEquals(140.0, window.modeledMah!!, 1e-9)
        assertEquals(40.0, window.unattributedMah!!, 1e-9)
        assertEquals(80.0, rows.uids.filter { it.uid >= 0 }.sumOf { it.mAh }, 1e-9)
        assertEquals(140.0, rows.uids.filter { it.uid == UidPowerEntity.DEVICE_UID }.sumOf { it.mAh }, 1e-9)
        val discharge = rows.stats.single { it.key == DeviceKeys.STATS_DISCHARGE_UAH }
        assertEquals(180_000L, discharge.value)
        assertEquals(Provenance.MEASURED, discharge.provenance)
    }

    @Test
    fun theChargeCounterIsNeverComparedAcrossABoot() {
        val from = WindowEnd(snapshot(1, 5 * hourMs, chargeUah = 3_000_000, levelPct = 80), SnapshotRows())
        val to = WindowEnd(snapshot(2, hourMs, chargeUah = 4_000_000, levelPct = 70, bootSeq = 2), SnapshotRows())

        val (window, _) = WindowBuilder.build(WindowKind.SESSION, 1, listOf(from, to), SETTINGS, withDetail = false)

        assertEquals(ChargeSource.LEVEL_DELTA, window.chargeSource)
        assertEquals(500.0, window.measuredMah!!, 1e-9)
    }

    @Test
    fun coulombsOwnRowsAreAlwaysKept() {
        val own = SETTINGS.ownUid
        val others = (0 until 40).map { 10_000 + it }
        val from =
            WindowEnd(
                snapshot(1, 0, 3_000_000, 80),
                SnapshotRows(
                    uidPower = others.map { power(it, 0.0) } + power(own, 0.0),
                    wakeStats = others.map { wake(it, 0) } + wake(own, 0),
                    netStats = listOf(NetStatEntity(0, own, 0, 0, null, null, null)),
                ),
            )
        val to =
            WindowEnd(
                snapshot(2, hourMs, 2_000_000, 60),
                SnapshotRows(
                    uidPower = others.map { power(it, 10.0) } + power(own, 0.01),
                    wakeStats = others.map { wake(it, 60_000) } + wake(own, 200),
                    netStats = listOf(NetStatEntity(0, own, 100, 0, null, null, null)),
                ),
            )

        val (_, rows) = WindowBuilder.build(WindowKind.DAY, null, listOf(from, to), SETTINGS, withDetail = true)

        assertEquals(0.01, rows.uids.single { it.uid == own }.mAh, 1e-9)
        assertEquals(200L, rows.wakes.single { it.uid == own }.totalMs)
        assertEquals(WindowBuilder.MAX_WAKES_PER_KIND + 1, rows.wakes.size)
        assertEquals(100L, rows.stats.single { it.uid == own }.value)
    }

    private fun devicePower(
        component: String,
        mAh: Double,
    ) = UidPowerEntity(0, UidPowerEntity.DEVICE_UID, component, UidPowerEntity.ANY_STATE, mAh, Provenance.MODELED)

    private fun wake(
        uid: Int,
        totalMs: Long,
    ) = WakeStatEntity(0, 0, uid, "sync", WakeKind.PARTIAL, totalMs, 1, null, null)

    private companion object {
        val SETTINGS = WindowSettings(counterReliable = true, capacityMah = 5_000.0, ownUid = 10_250)
    }
}

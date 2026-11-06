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
        sourceStatusJson = "{}",
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
    ) = listOf(
        DeviceStatEntity(0, DeviceKeys.TOTAL_REALTIME_MS, realtimeMs),
        DeviceStatEntity(0, DeviceKeys.SCREEN_ON_MS, screenOnMs),
        DeviceStatEntity(0, DeviceKeys.SCREEN_OFF_UPTIME_MS, screenOffUptimeMs),
    )

    @Test
    fun aWindowTakesTheCounterDeltaAndTheModeledTotal() {
        val from =
            WindowEnd(
                snapshot(1, hourMs, chargeUah = 3_000_000, levelPct = 80),
                SnapshotRows(uidPower = listOf(power(10_100, 5.0), power(10_200, 1.0)), deviceStats = device(hourMs, 600_000, 100_000)),
            )
        val to =
            WindowEnd(
                snapshot(2, 3 * hourMs, chargeUah = 2_900_000, levelPct = 78),
                SnapshotRows(
                    uidPower = listOf(power(10_100, 65.0), power(10_200, 21.0)),
                    deviceStats = device(3 * hourMs, 1_800_000, 400_000),
                ),
            )

        val (window, rows) =
            WindowBuilder.build(
                WindowKind.CONSECUTIVE,
                null,
                listOf(from, to),
                counterReliable = true,
                capacityMah = 5_000.0,
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
        assertEquals(setOf(10_100 to 60.0, 10_200 to 20.0), rows.uids.map { it.uid to it.mAh }.toSet())
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
                counterReliable = false,
                capacityMah = 5_000.0,
                withDetail = false,
            )

        assertEquals(500.0, window.measuredMah!!, 1e-9)
        assertEquals(ChargeSource.LEVEL_DELTA, window.chargeSource)
        assertNull("no power model read: no modeled total", window.modeledMah)
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

        val (window, rows) = WindowBuilder.build(WindowKind.CONSECUTIVE, null, listOf(from, to), true, 5_000.0, withDetail = true)

        val uids = rows.uids.map { it.uid }.toSet()
        assertEquals(WindowBuilder.MAX_UIDS + 1, uids.size)
        assertTrue(WindowUidEntity.OTHER_UID in uids)
        // The five smallest of 35 (1 to 5 mAh) and the five below the threshold (0.05 each).
        assertEquals(15.25, rows.uids.single { it.uid == WindowUidEntity.OTHER_UID }.mAh, 1e-9)
        assertEquals(window.modeledMah!!, rows.uids.sumOf { it.mAh }, 1e-9)
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

        val (window, rows) = WindowBuilder.build(WindowKind.CONSECUTIVE, null, listOf(from, to), true, 5_000.0, withDetail = true)

        assertEquals(7.0, window.modeledMah!!, 1e-9)
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

        val (_, rows) = WindowBuilder.build(WindowKind.CONSECUTIVE, null, listOf(from, to), true, 5_000.0, withDetail = true)

        val stats = rows.stats.associate { (it.uid to it.key) to it.value }
        assertEquals(5_000L, stats[10_100 to UidKeys.MOBILE_RX_BYTES])
        assertEquals(60_000L, stats[UidPowerEntity.DEVICE_UID to DeviceKeys.SCREEN_ON_MS])
        assertTrue(rows.stats.all { it.provenance == Provenance.MEASURED })
    }

    private fun wake(
        uid: Int,
        totalMs: Long,
    ) = WakeStatEntity(0, 0, uid, "sync", WakeKind.PARTIAL, totalMs, 1, null, null)
}

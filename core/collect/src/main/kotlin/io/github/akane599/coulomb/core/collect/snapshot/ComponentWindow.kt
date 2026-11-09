// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import io.github.akane599.coulomb.core.database.entity.PowerColumns
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.WindowComponentEntity
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.Breakdown
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.stats.ComponentPower
import io.github.akane599.coulomb.core.model.stats.DeviceKeys
import io.github.akane599.coulomb.core.model.stats.PowerColumn
import kotlin.math.abs

/** A consecutive window's S4 breakdown, or the first rule that kept it from having one. */
internal data class ComponentBreakdown(
    val breakdown: Breakdown,
    /** Empty unless [breakdown] is [Breakdown.BROKEN_DOWN]. */
    val rows: List<WindowComponentEntity> = emptyList(),
)

/**
 * ADR-0035 §5: whether a consecutive window gets S4's breakdown, and its `window_component` rows.
 * Pure. The rules run in the order of [Breakdown]; the first that fails is the reason stored.
 * Missing values never become zeros: a window that fails a rule has no rows at all.
 *
 * The rows are the window's change in S4's cumulative columns, in deci-coulombs: one per UID the
 * window keeps in `window_uid` ([kept]), one [WindowUidEntity.OTHER_UID] row for every other UID
 * and one [UidPowerEntity.DEVICE_UID] row, which is always written and marks the window as covered.
 * A UID or `OTHER_UID` row whose change is 0 in every column is left out; a reader takes it as 0.
 * There is no floor, so the rows add up exactly to S4's change.
 */
internal object ComponentWindow {
    /** A column may read this much lower at the later end: each entry is rounded by itself. */
    private const val ROUNDING_DC = 1L

    /** Battery and total realtime may differ by this much over a window wholly on battery. */
    private const val ON_BATTERY_MS = 1_000L

    /** ... or by this much when the later end is the plug-in snapshot (the plug-in tail). */
    private const val PLUG_IN_MS = 5_000L

    private const val DEVICE_MARGIN_DC = 36.0
    private const val UID_MARGIN_DC = 18.0
    private const val MARGIN_SHARE = 0.02

    /**
     * [modeledMah] is the window's S5 modeled total; [uidMah] its `window_uid` mAh per UID (the
     * kept UIDs and [WindowUidEntity.OTHER_UID], not the device rows); [kept] the UIDs the window
     * keeps, Coulomb's own included.
     */
    fun build(
        from: WindowEnd,
        to: WindowEnd,
        modeledMah: Double?,
        uidMah: Map<Int, Double>,
        kept: Set<Int>,
    ): ComponentBreakdown {
        val before = from.rows.componentPower.associate { it.uid to it.power.toPower() }
        val after = to.rows.componentPower.associate { it.uid to it.power.toPower() }
        val failed =
            when {
                isOff(from) || isOff(to) -> Breakdown.S4_OFF
                !usable(from, before) || !usable(to, after) -> Breakdown.END_NOT_OK
                modeledMah == null -> Breakdown.NO_MODELED_TOTAL
                !onBattery(from, to) -> Breakdown.NOT_ON_BATTERY
                wentDown(before, after) -> Breakdown.WENT_DOWN
                else -> null
            }
        if (failed != null || modeledMah == null) return ComponentBreakdown(failed ?: Breakdown.NO_MODELED_TOTAL)
        val changes = after.mapValues { (uid, power) -> power - (before[uid] ?: ComponentPower.ZERO) }
        val device = changes.getValue(UidPowerEntity.DEVICE_UID)
        val uids = grouped(changes, kept)
        if (!agrees(device, modeledMah, uids, uidMah)) return ComponentBreakdown(Breakdown.DISAGREES)
        val rows =
            uids
                .filterValues { it != ComponentPower.ZERO }
                .plus(UidPowerEntity.DEVICE_UID to device)
                .map { (uid, power) -> WindowComponentEntity(0, uid, PowerColumns.of(power)) }
        return ComponentBreakdown(Breakdown.BROKEN_DOWN, rows)
    }

    /** No S4 status, as on a snapshot from before S4 was read, counts as off. */
    private fun isOff(end: WindowEnd): Boolean =
        sourceStatus(end.snapshot.sourceStatusJson, HeavyReader.S4).let { it == null || it == SourceStatus.OFF }

    /** S4 `ok`, with its rows still stored: the device row is always among them (ADR-0035 §4). */
    private fun usable(
        end: WindowEnd,
        power: Map<Int, ComponentPower>,
    ): Boolean =
        sourceStatus(end.snapshot.sourceStatusJson, HeavyReader.S4) == SourceStatus.OK &&
            UidPowerEntity.DEVICE_UID in power

    /**
     * Wholly on battery, with no reset: S5's battery and total realtime changed by the same amount,
     * and its session started at the same time at both ends. The mapper leaves out a zero counter,
     * so a missing one is 0 (the caller has S5's device section at both ends).
     */
    private fun onBattery(
        from: WindowEnd,
        to: WindowEnd,
    ): Boolean {
        val start = from.snapshot.statsStartWall
        if (start == null || start != to.snapshot.statsStartWall) return false
        val total = stat(to, DeviceKeys.TOTAL_REALTIME_MS) - stat(from, DeviceKeys.TOTAL_REALTIME_MS)
        val battery = stat(to, DeviceKeys.BATTERY_REALTIME_MS) - stat(from, DeviceKeys.BATTERY_REALTIME_MS)
        val tolerance = if (to.snapshot.trigger == SnapshotTrigger.POWER_CONNECTED) PLUG_IN_MS else ON_BATTERY_MS
        return total >= 0 && battery >= 0 && abs(total - battery) <= tolerance
    }

    private fun stat(
        end: WindowEnd,
        key: String,
    ): Long =
        end.rows.deviceStats
            .firstOrNull { it.key == key }
            ?.value ?: 0

    /** A column of a consumer present at the earlier end reads lower later, or the consumer is gone. */
    private fun wentDown(
        before: Map<Int, ComponentPower>,
        after: Map<Int, ComponentPower>,
    ): Boolean =
        before.any { (uid, earlier) ->
            val later = after[uid]
            later == null || PowerColumn.entries.any { earlier[it] - later[it] > ROUNDING_DC }
        }

    /** The UIDs' changes, each kept UID by itself and every other one in [WindowUidEntity.OTHER_UID]. */
    private fun grouped(
        changes: Map<Int, ComponentPower>,
        kept: Set<Int>,
    ): Map<Int, ComponentPower> {
        val grouped = HashMap<Int, ComponentPower>()
        for ((uid, change) in changes) {
            if (uid == UidPowerEntity.DEVICE_UID) continue
            val target = if (uid in kept) uid else WindowUidEntity.OTHER_UID
            grouped.merge(target, change, ComponentPower::plus)
        }
        return grouped
    }

    /**
     * S4 agrees with S5: the device's component columns without `custom` (ids 0-18, as S5's device
     * items) against the modeled total, within 36 dC plus 2%; each UID row's components, custom
     * included as S5's UID totals include it, against its `window_uid` mAh, within 18 dC plus 2%.
     */
    private fun agrees(
        device: ComponentPower,
        modeledMah: Double,
        uids: Map<Int, ComponentPower>,
        uidMah: Map<Int, Double>,
    ): Boolean {
        val deviceDc = device.componentSum - device[PowerColumn.CUSTOM]
        if (!within(deviceDc, modeledMah, DEVICE_MARGIN_DC)) return false
        return (uids.keys + uidMah.keys).all { uid ->
            within(uids[uid]?.componentSum ?: 0, uidMah[uid] ?: 0.0, UID_MARGIN_DC)
        }
    }

    private fun within(
        dc: Long,
        mah: Double,
        marginDc: Double,
    ): Boolean {
        val expected = mah * ComponentPower.DC_PER_MAH
        return abs(dc - expected) <= marginDc + MARGIN_SHARE * abs(expected)
    }
}

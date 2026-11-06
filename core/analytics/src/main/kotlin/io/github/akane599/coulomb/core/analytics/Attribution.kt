// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import io.github.akane599.coulomb.core.model.Provenance
import kotlin.math.abs

/**
 * Where a snapshot's cumulative counters count from (S4 to S6).
 *
 * [startWallMs] alone cannot tell a reset: the platform moves it with every wall-clock change
 * (`BatteryStatsImpl.noteCurrentTimeChangedLocked` calls `adjustStartClockTime`, android-16.0.0_r4
 * L4657-4667, android-17.0.0_r1 L4196-4206). [sinceStartMs] restarts at a reset
 * (`initTimes`, android-17.0.0_r1 L10983-10990) and otherwise grows with realtime.
 */
public data class StatsEpoch(
    /** `start_clock_time_ms`. */
    val startWallMs: Long?,
    /** `total_realtime_ms`: realtime counted since the statistics started, over every boot. */
    val sinceStartMs: Long?,
)

/**
 * One snapshot's cumulative counters from one source, keyed by [K] (such as UID, component and
 * process state), with when the snapshot was taken.
 */
public data class Counters<K>(
    val bootSeq: Int,
    /** Elapsed realtime since that boot, in ms. */
    val elapsedMs: Long,
    val wallMs: Long,
    val epoch: StatsEpoch,
    val values: Map<K, Double>,
)

/** A window's counter changes (SPEC 6.5). */
public data class CounterWindow<K>(
    val values: Map<K, Double>,
    /** The statistics were reset inside the window. */
    val resetInside: Boolean,
    /**
     * Use before a reset was not seen: the last snapshot before it was taken more than
     * [Attribution.UNSEEN_TOLERANCE_MS] earlier, so the window is a lower bound.
     */
    val partial: Boolean,
    /** Keys whose counter went down or vanished without a reset, such as a removed app; counted as 0. */
    val wentDown: Int,
)

public data class Reconciliation(
    /** Measured ΔQ minus the modeled sum, in mAh; null unless both are known. */
    val unattributedMah: Double?,
    /** SPEC 6.5: shown as its own row when it exceeds 10% of measured. */
    val showRow: Boolean,
)

/** SPEC 6.5 attribution windows: `S_b − S_a` per key, split at each reset and summed. */
public object Attribution {
    public const val UNATTRIBUTED_ROW_SHARE: Double = 0.10

    /** Realtime the counters may lag elapsed time by within one boot before it means a reset. */
    public const val SAME_BOOT_TOLERANCE_MS: Long = 60_000

    /** Across a reboot, the platform starts counting after system_server starts, not at boot. */
    public const val NEW_BOOT_TOLERANCE_MS: Long = 5 * 60_000

    /** Unseen use before a reset that still counts as a complete window. */
    public const val UNSEEN_TOLERANCE_MS: Long = 2 * 60_000

    /**
     * The window over [chain], the snapshots from its start to its end in time order. Each step
     * without a reset adds `next − prev`; a step with one adds the later snapshot's own counters,
     * which start from zero at the reset, and marks the window partial unless the reset came
     * within [UNSEEN_TOLERANCE_MS] of the earlier snapshot. Pass every stored snapshot in between
     * that still has counters: each one narrows what a reset can hide.
     */
    public fun <K> window(chain: List<Counters<K>>): CounterWindow<K> {
        require(chain.size >= 2) { "a window needs two snapshots" }
        val total = HashMap<K, Double>()
        var resetInside = false
        var partial = false
        var wentDown = 0
        for ((prev, next) in chain.zipWithNext()) {
            val step =
                if (resetBetween(prev, next)) {
                    resetInside = true
                    partial = partial || unseenBeforeReset(prev, next)
                    next.values.filterValues { it > 0 }
                } else {
                    val d = diff(prev.values, next.values)
                    wentDown += d.second
                    d.first
                }
            step.forEach { (key, value) -> total.merge(key, value, Double::plus) }
        }
        return CounterWindow(total, resetInside, partial, wentDown)
    }

    /** The window from [a] to [b] with nothing stored in between. */
    public fun <K> window(
        a: Counters<K>,
        b: Counters<K>,
    ): CounterWindow<K> = window(listOf(a, b))

    /**
     * Whether the statistics were reset between [a] and [b]. Without a reset, the counted realtime
     * grows at least as fast as elapsed time in one boot, and by at least [b]'s own uptime across a
     * reboot. Without it, the start time decides: in one boot, start minus (wall − elapsed) stays
     * fixed through clock changes; across a reboot any change counts as a reset.
     */
    public fun resetBetween(
        a: Counters<*>,
        b: Counters<*>,
    ): Boolean {
        val sameBoot = a.bootSeq == b.bootSeq
        val sa = a.epoch.sinceStartMs
        val sb = b.epoch.sinceStartMs
        if (sa != null && sb != null) {
            return if (sameBoot) {
                sb - sa < b.elapsedMs - a.elapsedMs - SAME_BOOT_TOLERANCE_MS
            } else {
                sb - sa < b.elapsedMs - NEW_BOOT_TOLERANCE_MS
            }
        }
        val wa = a.epoch.startWallMs
        val wb = b.epoch.startWallMs
        return when {
            wa == null || wb == null -> false
            sameBoot -> abs((wb - (b.wallMs - b.elapsedMs)) - (wa - (a.wallMs - a.elapsedMs))) > SAME_BOOT_TOLERANCE_MS
            else -> wa != wb
        }
    }

    /** Use between [prev] and the reset before [next] was not seen. */
    private fun unseenBeforeReset(
        prev: Counters<*>,
        next: Counters<*>,
    ): Boolean {
        val since = next.epoch.sinceStartMs
        val unseenMs =
            when {
                since != null && prev.bootSeq == next.bootSeq && since <= next.elapsedMs -> next.elapsedMs - since - prev.elapsedMs
                since == null && next.epoch.startWallMs != null -> next.epoch.startWallMs - prev.wallMs
                else -> return true
            }
        return unseenMs > UNSEEN_TOLERANCE_MS
    }

    /**
     * Unattributed = measured ΔQ − Σ modeled (SPEC 6.5), both as positive mAh of drain. Null
     * inputs give no unattributed value and no row.
     */
    public fun reconcile(
        measuredMah: Double?,
        modeledMah: Double?,
    ): Reconciliation {
        if (measuredMah == null || modeledMah == null) return Reconciliation(null, showRow = false)
        val unattributed = measuredMah - modeledMah
        return Reconciliation(unattributed, showRow = measuredMah > 0 && unattributed > UNATTRIBUTED_ROW_SHARE * measuredMah)
    }

    /**
     * The "scale to measured" view (SPEC 6.5, off by default): each modeled share times
     * measured / modeled. Its values are Derived. Null when either total is not positive.
     */
    public fun <K> scaleToMeasured(
        modeled: Map<K, Double>,
        measuredMah: Double,
    ): Pair<Map<K, Double>, Provenance>? {
        val total = modeled.values.sum()
        if (total <= 0 || measuredMah <= 0) return null
        val factor = measuredMah / total
        return modeled.mapValues { it.value * factor } to Provenance.DERIVED
    }

    /** `b − a` per key with the count of keys that went down or vanished, which give nothing. */
    private fun <K> diff(
        a: Map<K, Double>,
        b: Map<K, Double>,
    ): Pair<Map<K, Double>, Int> {
        var wentDown = a.count { (key, before) -> before > 0 && key !in b }
        val values = HashMap<K, Double>()
        for ((key, after) in b) {
            val change = after - (a[key] ?: 0.0)
            if (change < 0) {
                wentDown++
            } else if (change > 0) {
                values[key] = change
            }
        }
        return values to wentDown
    }
}

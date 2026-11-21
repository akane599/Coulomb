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
    /** Steps across a system restart ([Step.RESTART]). They add nothing, so the window is partial. */
    val uncounted: Int = 0,
)

/** How the counters at the two ends of a chain step relate ([Attribution.step]). */
public enum class Step {
    /** No reset in between: the change is `next − prev`. */
    CONTINUES,

    /** One reset, right after the earlier snapshot: the later snapshot's own counters are the change. */
    RESET,

    /** A reset with use before it that no stored counter saw: the later counters are a lower bound. */
    RESET_UNSEEN,

    /** A system restart with no reset after it: the two ends cannot be compared, and the step adds nothing. */
    RESTART,
}

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

    /**
     * How far the wall time of boot (wall clock minus elapsed time) may differ between two
     * snapshots of one kernel boot. Clock drift reached 4.2 s over a 61 h boot on the S25 Ultra;
     * its 22 system restarts moved the value by 1.2 s at most, and its reboots by 122 s at least
     * (ADR-0055). A reboot moves it by more than the earlier boot had run, so the tolerance is
     * never more than that.
     */
    public const val KERNEL_BOOT_TOLERANCE_MS: Long = 60_000

    /** Unseen use before a reset that still counts as a complete window. */
    public const val UNSEEN_TOLERANCE_MS: Long = 2 * 60_000

    /**
     * The window over [chain], the snapshots from its start to its end in time order, with how each
     * step's ends relate ([steps], one per neighbouring pair; by default each pair judged alone).
     *
     * - [Step.CONTINUES] adds `next − prev`.
     * - [Step.RESET] adds the later snapshot's own counters, which start from zero at the reset.
     *   [Step.RESET_UNSEEN] does the same and marks the window partial.
     * - [Step.RESTART] adds nothing and marks the window partial: see [step].
     *
     * Pass every stored snapshot in between that still has counters and sits next to a reset or a
     * system restart: each one narrows what the step hides.
     */
    public fun <K> window(
        chain: List<Counters<K>>,
        steps: List<Step> = chain.zipWithNext { a, b -> step(listOf(a, b)) },
    ): CounterWindow<K> {
        require(chain.size >= 2) { "a window needs two snapshots" }
        require(steps.size == chain.size - 1) { "a window needs one step for each pair" }
        val total = HashMap<K, Double>()
        var resetInside = false
        var partial = false
        var wentDown = 0
        var uncounted = 0
        chain.zipWithNext().forEachIndexed { i, (prev, next) ->
            val added =
                when (steps[i]) {
                    Step.CONTINUES -> {
                        val d = diff(prev.values, next.values)
                        wentDown += d.second
                        d.first
                    }

                    Step.RESET, Step.RESET_UNSEEN -> {
                        resetInside = true
                        partial = partial || steps[i] == Step.RESET_UNSEEN
                        next.values.filterValues { it > 0 }
                    }

                    Step.RESTART -> {
                        uncounted++
                        partial = true
                        emptyMap()
                    }
                }
            added.forEach { (key, value) -> total.merge(key, value, Double::plus) }
        }
        return CounterWindow(total, resetInside, partial, wentDown, uncounted)
    }

    /**
     * How the counters at the ends of [path] relate. [path] is the two ends and every snapshot
     * stored in between, with or without counters, in time order: each neighbouring pair is judged
     * on its own ([resetBetween], [systemRestartBetween]), because the rules hold only for snapshots
     * with nothing but time between them. Two ends judged directly across a reboot and a later
     * system restart, or across a clock change, read as a reset that never happened.
     *
     * A system restart makes the ends incomparable. When the system server starts, its CPU time
     * reader knows no earlier reading and takes each UID's time since the kernel booted as new
     * (`KernelCpuUidTimeReader`, android-16.0.0_r4 and android-17.0.0_r1 L243-247: an unseen UID
     * starts from zero). `BatteryStatsImpl` drops that first reading only off battery or right
     * after a reset (android-16.0.0_r4 L12899-12903 and L13046-13052, android-17.0.0_r1
     * L12494-12497 and L12641-12647), so on battery every app is charged its CPU time since boot
     * a second time. On the S25 Ultra one such step showed 296 mAh in 65 s. So `next − prev` across
     * a restart is no lower bound, and the step is [Step.RESTART] unless a later reset cleared the
     * counters again.
     */
    public fun step(path: List<Counters<*>>): Step {
        require(path.size >= 2) { "a step needs two snapshots" }
        val pairs = path.zipWithNext()
        val lastRestart = pairs.indexOfLast { (a, b) -> systemRestartBetween(a, b) }
        val resets = pairs.indices.filter { resetBetween(pairs[it].first, pairs[it].second) }
        val lastReset = resets.lastOrNull() ?: -1
        return when {
            lastRestart >= 0 && lastReset <= lastRestart -> Step.RESTART
            lastReset < 0 -> Step.CONTINUES
            resets.size == 1 && lastReset == 0 && !unseenBeforeReset(path[0], path[1]) -> Step.RESET
            else -> Step.RESET_UNSEEN
        }
    }

    /** The window from [a] to [b] with nothing stored in between. */
    public fun <K> window(
        a: Counters<K>,
        b: Counters<K>,
    ): CounterWindow<K> = window(listOf(a, b))

    /**
     * Whether the statistics were reset between [a] and [b]. A reset restarts the counted realtime.
     *
     * - **One boot:** without a reset the counted realtime grows at least as fast as elapsed time.
     * - **A reboot:** without a reset it grows by at least [b]'s own uptime.
     * - **A system restart** ([systemRestartBetween]): the counted realtime carries on from what the
     *   platform last wrote, so it can lag elapsed time by any amount and neither rule above holds.
     *   A reset leaves at most the time since [a] counted; so it is one only when the counted
     *   realtime both went back and fits in that time. Where both readings fit, this says "no reset":
     *   a missed reset undercounts the step, a false one adds every counter's whole total to it.
     *
     * Without the counted realtime the start time decides: in one boot, start minus (wall −
     * elapsed) stays fixed through clock changes; across a reboot any change counts as a reset;
     * across a system restart the platform may move it by itself (it did in 10 of the 22 on the
     * S25 Ultra), so nothing can be said and it is taken as no reset.
     *
     * Holds for two snapshots with nothing stored between them; over a longer stretch use [step].
     */
    public fun resetBetween(
        a: Counters<*>,
        b: Counters<*>,
    ): Boolean {
        val sameBoot = a.bootSeq == b.bootSeq
        val restarted = systemRestartBetween(a, b)
        val sa = a.epoch.sinceStartMs
        val sb = b.epoch.sinceStartMs
        if (sa != null && sb != null) {
            val elapsed = b.elapsedMs - a.elapsedMs
            return when {
                sameBoot -> sb - sa < elapsed - SAME_BOOT_TOLERANCE_MS
                restarted -> sb < sa && sb <= elapsed + SAME_BOOT_TOLERANCE_MS
                else -> sb - sa < b.elapsedMs - NEW_BOOT_TOLERANCE_MS
            }
        }
        val wa = a.epoch.startWallMs
        val wb = b.epoch.startWallMs
        return when {
            wa == null || wb == null || restarted -> false
            sameBoot -> abs((wb - (b.wallMs - b.elapsedMs)) - (wa - (a.wallMs - a.elapsedMs))) > SAME_BOOT_TOLERANCE_MS
            else -> wa != wb
        }
    }

    /**
     * Whether [a] and [b] carry different boot numbers but lie in one kernel boot: the system server
     * restarted without a reboot. The platform's boot count rises at every system server start
     * (`PowerManagerService.onBootPhase` calls `incrementBootCount` at
     * `PHASE_THIRD_PARTY_APPS_CAN_START`: android-17.0.0_r1 L1412-1417 and L5790-5801,
     * android-16.0.0_r4 L1371-1376 and L5567-5578), while elapsed time keeps running and the
     * statistics carry on from their last write. Elapsed time has then not gone back, and either:
     *
     * - the wall time of boot is the same at both ends, within [KERNEL_BOOT_TOLERANCE_MS] and
     *   within how long the boot had run at [a] (a reboot moves it by more than that); or
     * - a clock change hid that, but the counted realtime carried on ([countedOn]).
     */
    public fun systemRestartBetween(
        a: Counters<*>,
        b: Counters<*>,
    ): Boolean {
        if (a.bootSeq == b.bootSeq || b.elapsedMs < a.elapsedMs) return false
        val moved = abs((b.wallMs - b.elapsedMs) - (a.wallMs - a.elapsedMs))
        return moved <= minOf(KERNEL_BOOT_TOLERANCE_MS, a.elapsedMs) || countedOn(a, b)
    }

    /**
     * The sign of a system restart that needs no wall clock: the counted realtime grew by no more
     * than the elapsed time between the two, and by less than a boot of [b]'s age would have
     * counted without a reset. A reset in a new boot can look the same when the statistics were
     * young at [a]; reading that as a restart leaves the step out, which undercounts, where the
     * other mistake would add every counter's whole total. None of the 36 reboots stored on the
     * S25 Ultra shows the sign; 15 of its 22 restarts do, and the other 7 came in a boot's first
     * five minutes.
     */
    private fun countedOn(
        a: Counters<*>,
        b: Counters<*>,
    ): Boolean {
        val counted = (b.epoch.sinceStartMs ?: return false) - (a.epoch.sinceStartMs ?: return false)
        return counted >= -SAME_BOOT_TOLERANCE_MS &&
            counted <= b.elapsedMs - a.elapsedMs + SAME_BOOT_TOLERANCE_MS &&
            counted < b.elapsedMs - NEW_BOOT_TOLERANCE_MS
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

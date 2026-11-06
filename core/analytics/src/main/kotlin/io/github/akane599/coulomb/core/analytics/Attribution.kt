// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import io.github.akane599.coulomb.core.model.Provenance

/**
 * One snapshot's cumulative counters from one source, keyed by [K] (such as UID, component and
 * process state). [statsStartWall] is the epoch the counters count from (S4 to S6): a different
 * value means the platform reset its statistics in between.
 */
public data class Counters<K>(
    val statsStartWall: Long?,
    val values: Map<K, Double>,
)

/** A window's counter changes (SPEC 6.5). */
public data class CounterWindow<K>(
    val values: Map<K, Double>,
    /** The statistics were reset inside the window. */
    val resetInside: Boolean,
    /**
     * The part before the reset is missing: no snapshot was taken just before it, so only the part
     * after it is counted and the window is a lower bound.
     */
    val partial: Boolean,
    /** Keys whose counter went down without a reset, such as a removed app; counted as 0. */
    val wentDown: Int,
)

public data class Reconciliation(
    /** Measured ΔQ minus the modeled sum, in mAh; null unless both are known. */
    val unattributedMah: Double?,
    /** SPEC 6.5: shown as its own row when it exceeds 10% of measured. */
    val showRow: Boolean,
)

/** SPEC 6.5 attribution windows: `S_b − S_a` per key, split at a reset. */
public object Attribution {
    public const val UNATTRIBUTED_ROW_SHARE: Double = 0.10

    /**
     * The window from [a] to [b]. When the statistics were reset in between, the window is split
     * there: [beforeReset], the last snapshot taken before the reset (same epoch as [a]), gives the
     * first part, and [b] itself the second, since its counters start from zero. Without
     * [beforeReset] only the second part is known.
     */
    public fun <K> window(
        a: Counters<K>,
        b: Counters<K>,
        beforeReset: Counters<K>? = null,
    ): CounterWindow<K> {
        val reset = a.statsStartWall != b.statsStartWall
        if (!reset) return diff(a.values, b.values).copy(resetInside = false)
        val usable = beforeReset?.takeIf { it.statsStartWall == a.statsStartWall }
        val before = usable?.let { diff(a.values, it.values) }
        val values =
            if (before == null) {
                b.values.filterValues { it > 0 }
            } else {
                (before.values.keys + b.values.keys).associateWith { (before.values[it] ?: 0.0) + (b.values[it] ?: 0.0) }
            }
        return CounterWindow(values, resetInside = true, partial = before == null, wentDown = before?.wentDown ?: 0)
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

    private fun <K> diff(
        a: Map<K, Double>,
        b: Map<K, Double>,
    ): CounterWindow<K> {
        var wentDown = 0
        val values =
            b
                .mapNotNull { (key, after) ->
                    val change = after - (a[key] ?: 0.0)
                    when {
                        change < 0 -> {
                            wentDown++
                            null
                        }

                        change == 0.0 -> {
                            null
                        }

                        else -> {
                            key to change
                        }
                    }
                }.toMap()
        return CounterWindow(values, resetInside = false, partial = false, wentDown = wentDown)
    }
}

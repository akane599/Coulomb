// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.database.dao.ChainStep
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage

/**
 * How much of a span each source of its wake records covered (ADR-0028 §3). A record that is missing
 * means zero only where its source's coverage is [Coverage.WHOLE]; under [Coverage.NONE] nothing can
 * be said, not even zero, and under [Coverage.PARTIAL] the counts are lower bounds.
 */
public data class WakeCoverage(
    /**
     * S5's wakelocks, kernel wakelocks, wakeup reasons and wakeup alarms: the windows that kept their
     * records and read S5 at both ends.
     */
    val batteryStats: Coverage,
    /** S9, root only: wakeup sources. */
    val wakeupSources: Coverage,
    /** S27, root only: resume causes and suspend aborts. */
    val kernelLog: Coverage,
) {
    internal companion object {
        /** The coverage of a span read from one stored window's own records. */
        fun ofWindow(window: WindowResultEntity): WakeCoverage =
            of(listOf(Kept.of(window.detailPruned, window.modeledMah, window.kernelCoverage)))

        /** The coverage of a span summed over its chain of windows. */
        fun ofChain(steps: List<ChainStep>): WakeCoverage = of(steps.map { Kept.of(it.detailPruned, it.modeledMah, it.kernelCoverage) })

        private fun of(windows: List<Kept>): WakeCoverage =
            WakeCoverage(
                batteryStats = combine(windows.map { it.batteryStats }),
                wakeupSources = combine(windows.map { it.kernel.wakeupSources }),
                kernelLog = combine(windows.map { it.kernel.kernelLog }),
            )

        /** Whole only when every window is; none when no window collected anything. */
        private fun combine(levels: List<Coverage>): Coverage =
            when {
                levels.isEmpty() || levels.all { it == Coverage.NONE } -> Coverage.NONE
                levels.all { it == Coverage.WHOLE } -> Coverage.WHOLE
                else -> Coverage.PARTIAL
            }

        private val NOTHING = KernelCoverage(Coverage.NONE, Coverage.NONE, Coverage.NONE)
    }

    /** One window's records: pruned records count as never collected. */
    private class Kept(
        val batteryStats: Coverage,
        val kernel: KernelCoverage,
    ) {
        companion object {
            fun of(
                detailPruned: Boolean,
                modeledMah: Double?,
                kernelCoverage: KernelCoverage?,
            ): Kept =
                if (detailPruned) {
                    Kept(Coverage.NONE, NOTHING)
                } else {
                    Kept(if (modeledMah != null) Coverage.WHOLE else Coverage.NONE, kernelCoverage ?: NOTHING)
                }
        }
    }
}

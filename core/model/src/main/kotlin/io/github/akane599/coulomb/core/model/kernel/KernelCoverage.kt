// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.kernel

/**
 * How much of a window's root kernel data was collected, per source (ADR-0029 §3, §4). A window
 * row that is missing means zero only for a source whose coverage is [Coverage.WHOLE].
 */
public data class KernelCoverage(
    /** S25: suspend counter deltas, stored as `window_stat` rows. */
    val suspendStats: Coverage,
    /** S9: wakeup-source deltas, stored as [io.github.akane599.coulomb.core.model.WakeKind.WAKEUP_SOURCE] rows. */
    val wakeupSources: Coverage,
    /** S27: resume causes and suspend aborts, stored as window wake rows of their kinds. */
    val kernelLog: Coverage,
) {
    public enum class Coverage(
        public val code: Int,
    ) {
        /** Not collected: nothing can be said, not even zero. */
        NONE(0),

        /** Collected for part of the window: the values are lower bounds ("at least"). */
        PARTIAL(1),

        /** Collected for the whole window: a missing row means zero. */
        WHOLE(2),
    }

    /** Two bits per source, S25 lowest, then S9, then S27. */
    public val code: Int
        get() = suspendStats.code or (wakeupSources.code shl BITS) or (kernelLog.code shl (2 * BITS))

    public companion object {
        private const val BITS = 2
        private const val MASK = 0b11
        private const val MAX_CODE = (1 shl (3 * BITS)) - 1

        public fun ofCode(code: Int): KernelCoverage {
            require(code in 0..MAX_CODE) { "Kernel coverage code out of range: $code" }
            return KernelCoverage(level(code), level(code shr BITS), level(code shr (2 * BITS)))
        }

        private fun level(bits: Int): Coverage =
            Coverage.entries.firstOrNull { it.code == bits and MASK } ?: error("Unknown coverage level ${bits and MASK}")
    }
}

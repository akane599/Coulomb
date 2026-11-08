// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.database.dao.ChainStep
import io.github.akane599.coulomb.core.model.ChargeSource
import kotlin.math.abs

/**
 * How far an Estimated measured total can be off (ADR-0036 §17). Levels are whole points, so a level
 * change is good to one point either way. Consecutive level-based windows share their ends, so a
 * stretch of them adds up to one level change with one point of error; each stretch adds one step.
 */
public data class LevelEstimate(
    /** Half the range, in mAh: one level step for each stretch. */
    val spreadMah: Double,
    /** The stretches of level-based windows in the total. */
    val stretches: Int,
    /** The rest of the total came from the charge counter. */
    val withCounter: Boolean,
) {
    internal companion object {
        /**
         * The range of a chain's discharge total ([ChainStep]s in time order): windows that started
         * on battery and have a measured value, as the total sums them. A window read by the counter,
         * one on the charger, or one without a value ends a stretch. The step is the mAh per point of
         * the level-based windows, from their own capacity. Null without a level-based window, or
         * when none of them saw the level change.
         */
        fun ofChain(steps: List<ChainStep>): LevelEstimate? {
            var stretches = 0
            var inStretch = false
            var withCounter = false
            var levelMah = 0.0
            var points = 0
            for (step in steps) {
                val summed = step.plugType == 0 && step.measuredMah != null
                val byLevel = summed && step.chargeSource == ChargeSource.LEVEL_DELTA
                if (byLevel) {
                    if (!inStretch) stretches++
                    val from = step.fromLevel
                    val to = step.toLevel
                    if (from != null && to != null && from != to) {
                        levelMah += abs(checkNotNull(step.measuredMah))
                        points += abs(from - to)
                    }
                } else if (summed) {
                    withCounter = true
                }
                inStretch = byLevel
            }
            if (stretches == 0 || points == 0) return null
            return LevelEstimate(stretches * levelMah / points, stretches, withCounter)
        }

        /** The range of one level-based window: one step. Null when its level did not change. */
        fun ofWindow(
            measuredMah: Double,
            fromLevel: Int?,
            toLevel: Int?,
        ): LevelEstimate? {
            if (fromLevel == null || toLevel == null || fromLevel == toLevel) return null
            return LevelEstimate(abs(measuredMah) / abs(fromLevel - toLevel), stretches = 1, withCounter = false)
        }
    }
}

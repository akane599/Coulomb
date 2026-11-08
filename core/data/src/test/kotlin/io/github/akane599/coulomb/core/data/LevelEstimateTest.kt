// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.dao.ChainStep
import io.github.akane599.coulomb.core.model.ChargeSource
import org.junit.Test

class LevelEstimateTest {
    private fun level(
        mah: Double?,
        from: Int?,
        to: Int?,
        plug: Int? = 0,
    ) = ChainStep(ChargeSource.LEVEL_DELTA, mah, plug, from, to)

    private fun counter(mah: Double?) = ChainStep(ChargeSource.CHARGE_COUNTER, mah, 0, 50, 49)

    @Test
    fun consecutiveLevelWindowsAreOneStretchOfOneStep() {
        // 100 mAh over 2 points and 150 over 3: 50 mAh a point, one stretch.
        val estimate = LevelEstimate.ofChain(listOf(level(-100.0, 80, 78), level(-150.0, 78, 75)))

        assertThat(estimate).isEqualTo(LevelEstimate(50.0, stretches = 1, withCounter = false))
    }

    @Test
    fun aCounterWindowEndsAStretch() {
        val estimate = LevelEstimate.ofChain(listOf(level(-100.0, 80, 78), counter(-40.0), level(-50.0, 77, 76)))

        // 150 mAh over 3 points is 50 a point; two stretches make 100.
        assertThat(estimate).isEqualTo(LevelEstimate(100.0, stretches = 2, withCounter = true))
    }

    @Test
    fun aWindowOnTheChargerOrWithoutAValueEndsAStretchAndAddsNothing() {
        val estimate =
            LevelEstimate.ofChain(
                listOf(
                    level(-100.0, 80, 78),
                    level(200.0, 78, 82, plug = 1),
                    level(-100.0, 82, 80),
                    level(null, 80, 79),
                    level(-50.0, 79, 78),
                ),
            )

        // Three stretches, 250 mAh over 5 points.
        assertThat(estimate).isEqualTo(LevelEstimate(150.0, stretches = 3, withCounter = false))
    }

    @Test
    fun aLevelWindowWithoutAChangeExtendsTheStretchButNotTheStep() {
        val estimate = LevelEstimate.ofChain(listOf(level(-100.0, 80, 78), level(-5.0, 78, 78)))

        assertThat(estimate).isEqualTo(LevelEstimate(50.0, stretches = 1, withCounter = false))
    }

    @Test
    fun noLevelChangeOrNoLevelWindowHasNoRange() {
        assertThat(LevelEstimate.ofChain(listOf(counter(-40.0)))).isNull()
        assertThat(LevelEstimate.ofChain(listOf(level(-5.0, 78, 78)))).isNull()
        assertThat(LevelEstimate.ofChain(emptyList())).isNull()
    }

    @Test
    fun oneWindowIsOneStep() {
        assertThat(LevelEstimate.ofWindow(-120.0, 60, 57)).isEqualTo(LevelEstimate(40.0, stretches = 1, withCounter = false))
        assertThat(LevelEstimate.ofWindow(-5.0, 57, 57)).isNull()
        assertThat(LevelEstimate.ofWindow(-5.0, null, 57)).isNull()
    }
}

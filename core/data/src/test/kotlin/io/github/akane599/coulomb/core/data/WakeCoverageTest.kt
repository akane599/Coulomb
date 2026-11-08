// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.dao.ChainStep
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import org.junit.Test

class WakeCoverageTest {
    private val root = KernelCoverage(Coverage.WHOLE, Coverage.WHOLE, Coverage.WHOLE)

    private fun step(
        modeled: Double? = 10.0,
        kernel: KernelCoverage? = null,
        pruned: Boolean = false,
    ) = ChainStep(ChargeSource.CHARGE_COUNTER, 10.0, 0, 80, 79, pruned, modeled, kernel)

    @Test
    fun aShellChainHasS5WholeAndNoRootSource() {
        assertThat(WakeCoverage.ofChain(listOf(step(), step())))
            .isEqualTo(WakeCoverage(Coverage.WHOLE, Coverage.NONE, Coverage.NONE))
    }

    @Test
    fun aChainThatSwitchedModesIsPartlyCovered() {
        // Root, then shell: S9 and S27 for one window of two; S5 lost at one end of the second.
        val coverage = WakeCoverage.ofChain(listOf(step(kernel = root), step(modeled = null)))

        assertThat(coverage).isEqualTo(WakeCoverage(Coverage.PARTIAL, Coverage.PARTIAL, Coverage.PARTIAL))
    }

    @Test
    fun aWindowCoveredInPartStaysPartialEvenAlone() {
        val half = KernelCoverage(Coverage.WHOLE, Coverage.PARTIAL, Coverage.NONE)

        assertThat(WakeCoverage.ofChain(listOf(step(kernel = half))))
            .isEqualTo(WakeCoverage(Coverage.WHOLE, Coverage.PARTIAL, Coverage.NONE))
    }

    @Test
    fun prunedRecordsCountAsNeverCollected() {
        assertThat(WakeCoverage.ofChain(listOf(step(kernel = root, pruned = true))))
            .isEqualTo(WakeCoverage(Coverage.NONE, Coverage.NONE, Coverage.NONE))
        assertThat(WakeCoverage.ofChain(listOf(step(kernel = root), step(kernel = root, pruned = true))))
            .isEqualTo(WakeCoverage(Coverage.PARTIAL, Coverage.PARTIAL, Coverage.PARTIAL))
    }

    @Test
    fun anEmptyChainCollectedNothing() {
        assertThat(WakeCoverage.ofChain(emptyList())).isEqualTo(WakeCoverage(Coverage.NONE, Coverage.NONE, Coverage.NONE))
    }
}

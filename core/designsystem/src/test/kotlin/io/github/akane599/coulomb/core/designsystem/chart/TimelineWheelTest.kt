// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.chart

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Audit BUG-010: a Ctrl-wheel zoom keeps the time under the pointer in place. In a right-to-left
 * layout the plot is mirrored, margins included, so the pointer's share of the plot is the scale's
 * own, not a left-to-right share turned round.
 */
class TimelineWheelTest {
    private fun focus(
        x: Float,
        rtl: Boolean,
    ) = wheelFocus(x, plotStart = 80f, plotEnd = 350f, rtl = rtl, width = 350f)

    @Test
    fun theWheelZoomsAboutTheTimeUnderThePointer() {
        // Left to right: the plot runs from 80 to 350.
        assertThat(focus(80f, rtl = false)).isWithin(TOLERANCE).of(0f)
        assertThat(focus(215f, rtl = false)).isWithin(TOLERANCE).of(0.5f)
        assertThat(focus(350f, rtl = false)).isWithin(TOLERANCE).of(1f)
        // Right to left: mirrored, it runs from 270 back to 0, and its middle is at 135.
        assertThat(focus(270f, rtl = true)).isWithin(TOLERANCE).of(0f)
        assertThat(focus(135f, rtl = true)).isWithin(TOLERANCE).of(0.5f)
        assertThat(focus(0f, rtl = true)).isWithin(TOLERANCE).of(1f)
        // Outside the plot the nearest edge counts.
        assertThat(focus(340f, rtl = true)).isWithin(TOLERANCE).of(0f)
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}

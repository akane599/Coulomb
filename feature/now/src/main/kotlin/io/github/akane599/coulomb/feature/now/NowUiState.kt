// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.now

import androidx.compose.runtime.Immutable
import io.github.akane599.coulomb.core.data.AppDrain
import io.github.akane599.coulomb.core.data.BatteryNow
import io.github.akane599.coulomb.core.data.CurrentPoint
import io.github.akane599.coulomb.core.data.DischargeSummary
import io.github.akane599.coulomb.core.data.LevelPoint
import io.github.akane599.coulomb.core.data.WindowDrains
import io.github.akane599.coulomb.core.model.PrivilegeIssue

/** What Now shows (SPEC 10.2), in the states of SPEC 10.11. */
internal sealed interface NowUiState {
    /** No battery reading yet: the static skeleton. */
    data object Loading : NowUiState

    /** The records could not be read. */
    data object Error : NowUiState

    @Immutable
    data class Ready(
        val battery: BatteryNow,
        /** The open discharge; null while plugged in. */
        val discharge: DischargeSummary?,
        val levels: List<LevelPoint>,
        /** Since unplugged; plugged in, the last discharge. Null until a window is stored. */
        val drains: WindowDrains?,
        val live: List<CurrentPoint>,
        /** A missing capability to explain inline (SPEC 10.11 partial), or null. */
        val issue: PrivilegeIssue?,
    ) : NowUiState {
        /** The three largest drains (SPEC 10.2). */
        val topDrains: List<AppDrain> get() = drains?.apps.orEmpty().take(TOP_DRAINS)

        /**
         * What the three rows leave of the window's total: the other apps, the folded UIDs, the
         * device itself and the unattributed drain. SPEC 11.8's remainder row; Derived.
         */
        val remainderMah: Double
            get() = drains?.let { (it.total - topDrains.sumOf(AppDrain::mah)).coerceAtLeast(0.0) } ?: 0.0
    }

    companion object {
        const val TOP_DRAINS = 3
    }
}

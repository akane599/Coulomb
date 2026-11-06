// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.HistoryCodes
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import org.junit.Test

class HistoryCodesTest {
    @Test
    fun everyKindAndChangeHasOneCode() {
        assertThat(HistoryCodes.KINDS).containsExactlyElementsIn(HistoryKind.entries)
        assertThat(HistoryCodes.CHANGES).containsExactlyElementsIn(HistoryChange.entries)
        for (kind in HistoryKind.entries) assertThat(HistoryCodes.kind(HistoryCodes.kindCode(kind))).isEqualTo(kind)
    }

    @Test
    fun storedCodesNeverMove() {
        // Stored rows keep these codes: a new kind goes at the end of the list.
        assertThat(HistoryCodes.KINDS.take(6).map { it.name })
            .containsExactly("BOOT", "RESET", "WALL_CLOCK", "SHUTDOWN", "OVERFLOW", "BATTERY_LEVEL")
            .inOrder()
        assertThat(HistoryCodes.KINDS.size).isAtLeast(70)
        assertThat(HistoryCodes.KINDS[69]).isEqualTo(HistoryKind.DISPLAY_STATE)
        assertThat(HistoryCodes.CHANGES).containsExactly(HistoryChange.START, HistoryChange.STOP, HistoryChange.SET).inOrder()
    }
}

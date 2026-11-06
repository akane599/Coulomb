// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The batterystats allowlist (SPEC 19.1): exact arguments per mode, and no `--checkin` ever. */
class WorkerProtocolTest {
    @Test
    fun everyModeSendsExactlyItsAllowlistedFlags() {
        val args = BatteryStatsMode.entries.associateWith { it.args(HISTORY_START).toList() }
        assertThat(args)
            .containsExactly(
                BatteryStatsMode.HELP,
                listOf("-h"),
                BatteryStatsMode.PROTO,
                listOf("--proto"),
                BatteryStatsMode.CHECKIN,
                listOf("-c"),
                BatteryStatsMode.HISTORY,
                listOf("--history"),
                BatteryStatsMode.HISTORY_START,
                listOf("--history-start", "1234"),
                BatteryStatsMode.CHECKIN_HISTORY,
                listOf("-c", "--history-start", "1234"),
            )
        // `--checkin` deletes the saved checkin file (ADR-0005).
        assertThat(args.values.flatten()).doesNotContain("--checkin")
    }

    @Test
    fun aNegativeCursorStartsAtZero() {
        assertThat(BatteryStatsMode.CHECKIN_HISTORY.args(-5).toList()).containsExactly("-c", "--history-start", "0").inOrder()
    }

    @Test
    fun idsAreUniqueAndRoundTrip() {
        assertThat(BatteryStatsMode.entries.map { it.id }).containsNoDuplicates()
        for (mode in BatteryStatsMode.entries) assertThat(BatteryStatsMode.fromId(mode.id)).isEqualTo(mode)
        assertThat(BatteryStatsMode.fromId(0)).isNull()
    }

    @Test
    fun theCheckinBranchesNeedTheOtherUsersApps() {
        assertThat(BatteryStatsMode.entries.filter { it.listsAllUsers })
            .containsExactly(BatteryStatsMode.PROTO, BatteryStatsMode.CHECKIN, BatteryStatsMode.CHECKIN_HISTORY)
    }

    private companion object {
        const val HISTORY_START = 1234L
    }
}

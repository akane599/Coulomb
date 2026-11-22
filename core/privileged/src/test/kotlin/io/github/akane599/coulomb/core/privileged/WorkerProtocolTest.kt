// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeState
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
                BatteryStatsMode.USAGE_PROTO,
                listOf("--usage", "--proto"),
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

    /** Audit BUG-025: a target is T1 only where the in-app route can take it under the grants. */
    @Test
    fun theInAppRouteTakesOnlyTheTargetsItCan() {
        val durable = GrantStatus(batteryStats = true, dump = true, usageStats = true, usageStatsPermission = true)
        assertThat(BatteryStatsMode.HELP.dumpableInApp(durable)).isTrue()
        assertThat(BatteryStatsMode.USAGE_PROTO.dumpableInApp(durable)).isTrue()
        // These list every user's apps, which needs INTERACT_ACROSS_USERS in Coulomb's process.
        assertThat(BatteryStatsMode.PROTO.dumpableInApp(durable)).isFalse()
        assertThat(BatteryStatsMode.CHECKIN_HISTORY.dumpableInApp(durable)).isFalse()
        assertThat(BatteryStatsMode.PROTO.dumpableInApp(durable.copy(interactAcrossUsers = true))).isTrue()
        // An app cannot look the battery service up at all.
        assertThat(SystemService.ALARM.dumpableInApp(durable)).isTrue()
        assertThat(SystemService.BATTERY.dumpableInApp(durable.copy(interactAcrossUsers = true))).isFalse()
    }

    /** Audit BUG-045: without the worker, a missing BATTERY_STATS alone does not stop heavy snapshots. */
    @Test
    fun heavySnapshotsNeedTheDumpGrantsNotBatteryStats() {
        val dumps = GrantStatus(dump = true, usageStats = true, usageStatsPermission = true, interactAcrossUsers = true)
        val stopped = PrivilegeState(manager = ManagerStatus.NOT_RUNNING, grants = dumps)
        assertThat(stopped.tier).isEqualTo(AccessTier.T0_UNPRIVILEGED)
        assertThat(stopped.statsReadable).isTrue()
        assertThat(stopped.copy(grants = dumps.copy(interactAcrossUsers = false)).statsReadable).isFalse()
        assertThat(PrivilegeState(manager = ManagerStatus.NOT_RUNNING).statsReadable).isFalse()
    }

    private companion object {
        const val HISTORY_START = 1234L
    }
}

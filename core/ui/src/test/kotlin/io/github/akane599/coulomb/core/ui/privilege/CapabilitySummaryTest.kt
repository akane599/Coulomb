// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.privilege

import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.Capability
import io.github.akane599.coulomb.core.model.CapabilityId
import io.github.akane599.coulomb.core.model.CapabilityState
import io.github.akane599.coulomb.core.model.GrantStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitySummaryTest {
    @Test
    fun deepSourcesNameWhatCoulombSeesAndRailsDecideTheSource() {
        val summary =
            capabilitySummary(
                listOf(
                    cap(CapabilityId.BATTERY_CHANGED),
                    cap(CapabilityId.BATTERYSTATS_PROTO),
                    cap(CapabilityId.BATTERYSTATS_HISTORY, CapabilityState.DEGRADED),
                    cap(CapabilityId.WAKEUP_SOURCES),
                    cap(CapabilityId.POWER_MONITORS, CapabilityState.UNAVAILABLE),
                ),
            )
        assertEquals(listOf(Sight.PER_APP_DRAIN, Sight.WAKELOCKS, Sight.KERNEL_WAKEUPS), summary.sights)
        assertTrue(summary.deep)
        assertFalse(summary.railsExposed)
    }

    @Test
    fun withoutDeepSourcesTheBasicOnesAreNamed() {
        val summary =
            capabilitySummary(
                listOf(
                    cap(CapabilityId.BATTERY_CHANGED),
                    cap(CapabilityId.CURRENT_NOW),
                    cap(CapabilityId.USAGE_STATS),
                    cap(CapabilityId.BATTERYSTATS_USAGE, CapabilityState.UNAVAILABLE),
                ),
            )
        assertEquals(listOf(Sight.LEVEL, Sight.CURRENT, Sight.SCREEN_TIME), summary.sights)
        assertFalse(summary.deep)
    }

    @Test
    fun theChecklistAsksForWriteSecureSettingsOnlyLaterAndSplitsTheUsageGrants() {
        // The usage op alone (the Settings route) is app usage; reports also need DUMP and the permission.
        val rows = grantRows(GrantStatus(batteryStats = true, dump = true, usageStats = true))
        assertEquals(GrantRowState.GRANTED, rows[0].state)
        assertEquals(GrantRowState.MISSING, rows[1].state)
        assertEquals(GrantRowState.GRANTED, rows[2].state)
        assertEquals(GrantRowState.GRANTED, grantRows(GrantStatus(dump = true, usageStatsPermission = true))[1].state)
        assertEquals(GrantRowState.LATER, rows[4].state)
        assertEquals(GrantRowState.GRANTED, grantRows(GrantStatus(writeSecureSettings = true))[4].state)
    }

    private fun cap(
        id: CapabilityId,
        state: CapabilityState = CapabilityState.AVAILABLE,
    ) = Capability(id, state, AccessTier.T0_UNPRIVILEGED, null, null, 0L, "f")
}

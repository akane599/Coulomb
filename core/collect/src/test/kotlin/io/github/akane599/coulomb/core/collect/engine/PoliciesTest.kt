// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.retention.Cut
import io.github.akane599.coulomb.core.collect.retention.Retention
import io.github.akane599.coulomb.core.database.entity.BootEntity
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import org.junit.Test

class PoliciesTest {
    private val minuteNs = 60_000_000_000L

    private fun decide(
        trigger: SnapshotTrigger,
        lastSnapshotMinutesAgo: Long? = null,
        available: Boolean = true,
        first: Boolean = false,
        owed: Boolean = false,
    ) = TriggerPolicy.decide(
        PolicyInput(trigger, 100 * minuteNs, lastSnapshotMinutesAgo?.let { (100 - it) * minuteNs }, available, first, owed),
    )

    @Test
    fun spec72Kinds() {
        assertThat(decide(SnapshotTrigger.SCREEN_OFF).action).isEqualTo(Action.HEAVY)
        assertThat(decide(SnapshotTrigger.POWER_CONNECTED, lastSnapshotMinutesAgo = 0).action).isEqualTo(Action.HEAVY)
        assertThat(decide(SnapshotTrigger.LEVEL_CHANGED).action).isEqualTo(Action.SAMPLE)
        assertThat(decide(SnapshotTrigger.PACKAGE_CHANGED).action).isEqualTo(Action.LIGHT)
    }

    @Test
    fun appOpenRefreshAndUnlockAreDebouncedToOneAMinute() {
        assertThat(decide(SnapshotTrigger.APP_OPENED, lastSnapshotMinutesAgo = 0).action).isEqualTo(Action.NONE)
        assertThat(decide(SnapshotTrigger.USER_PRESENT, lastSnapshotMinutesAgo = 0).action).isEqualTo(Action.NONE)
        assertThat(decide(SnapshotTrigger.PULL_TO_REFRESH, lastSnapshotMinutesAgo = 1).action).isEqualTo(Action.HEAVY)
        assertThat(decide(SnapshotTrigger.APP_OPENED).action).isEqualTo(Action.HEAVY)
    }

    @Test
    fun aHeavySnapshotWithoutAccessIsLightAndOwed() {
        val owed = decide(SnapshotTrigger.SCREEN_OFF, available = false)
        assertThat(owed).isEqualTo(Decision(Action.LIGHT, heavyOwed = true))
        // A level change pays the debt once dumps work; a debounced trigger does not swallow it.
        assertThat(decide(SnapshotTrigger.LEVEL_CHANGED, owed = true)).isEqualTo(Decision(Action.HEAVY, heavyOwed = false))
        assertThat(decide(SnapshotTrigger.APP_OPENED, lastSnapshotMinutesAgo = 0, owed = true).action).isEqualTo(Action.HEAVY)
    }

    @Test
    fun aDebtThatCannotBePaidYetChangesNothing() {
        // Still no access: a level change stays a sample, a package change a light snapshot, and
        // the debounce holds, so the debt does not turn every broadcast into a snapshot.
        assertThat(
            decide(SnapshotTrigger.LEVEL_CHANGED, available = false, owed = true),
        ).isEqualTo(Decision(Action.SAMPLE, heavyOwed = true))
        assertThat(
            decide(SnapshotTrigger.PACKAGE_CHANGED, available = false, owed = true),
        ).isEqualTo(Decision(Action.LIGHT, heavyOwed = true))
        assertThat(decide(SnapshotTrigger.APP_OPENED, lastSnapshotMinutesAgo = 0, available = false, owed = true).action)
            .isEqualTo(Action.NONE)
    }

    @Test
    fun theFirstTriggerOfAProcessIsHeavy() {
        assertThat(decide(SnapshotTrigger.LEVEL_CHANGED, first = true).action).isEqualTo(Action.HEAVY)
        assertThat(decide(SnapshotTrigger.PACKAGE_CHANGED, first = true).action).isEqualTo(Action.HEAVY)
    }

    @Test
    fun spec73SamplingRates() {
        assertThat(SamplerPolicy.intervalMs(plugged = false, screenOn = false, VisibleScreen.COULOMB)).isNull()
        assertThat(SamplerPolicy.intervalMs(plugged = false, screenOn = true, VisibleScreen.NONE)).isEqualTo(30_000)
        assertThat(SamplerPolicy.intervalMs(plugged = false, screenOn = true, VisibleScreen.COULOMB)).isEqualTo(5_000)
        assertThat(SamplerPolicy.intervalMs(plugged = true, screenOn = false, VisibleScreen.NONE)).isEqualTo(30_000)
        assertThat(SamplerPolicy.intervalMs(plugged = true, screenOn = true, VisibleScreen.NONE)).isEqualTo(5_000)
        assertThat(SamplerPolicy.intervalMs(plugged = true, screenOn = true, VisibleScreen.CHARGING)).isEqualTo(1_000)
    }

    @Test
    fun aRetentionCutCountsBackInElapsedTimeOverTheBoots() {
        val hourNs = 3_600_000_000_000L
        val hourMs = 3_600_000L
        // The wall clock is deliberately useless: a cut never reads it.
        val older = BootEntity(5, 5, "fp", firstWallMs = 0, firstElapsedNs = 1 * hourNs, null, imported = false)
        val old = BootEntity(7, 7, "fp", firstWallMs = 0, firstElapsedNs = 2 * hourNs, null, imported = false)
        val current = BootEntity(8, 8, "fp", firstWallMs = 0, firstElapsedNs = hourNs / 2, null, imported = false)
        val boots = listOf(older, old, current)
        val lastSeen = mapOf(7 to 30 * hourNs, 8 to 3 * hourNs)
        val now = 3 * hourNs

        fun cut(hours: Long) = Retention.cutKey(boots, lastSeen, current, now, hours * hourMs)

        assertThat(cut(1)).isEqualTo(Cut(8, 2 * hourNs))
        assertThat(cut(3)).isEqualTo(Cut(8, 0))
        // Ten hours ago: three in this boot, then seven back from boot 7's last sighting.
        assertThat(cut(10)).isEqualTo(Cut(7, 23 * hourNs))
        // Boot 6 was never stored and boot 5 has no rows: each counts only what it is known to have run.
        assertThat(cut(34)).isEqualTo(Cut(5, 0))
        assertThat(cut(35)).isNull()
    }
}

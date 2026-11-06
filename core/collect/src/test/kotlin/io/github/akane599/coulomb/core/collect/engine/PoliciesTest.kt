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
        lastHeavyMinutesAgo: Long? = null,
        available: Boolean = true,
        first: Boolean = false,
        owed: Boolean = false,
    ) = TriggerPolicy.decide(
        PolicyInput(trigger, 100 * minuteNs, lastHeavyMinutesAgo?.let { (100 - it) * minuteNs }, available, first, owed),
    )

    @Test
    fun spec72Kinds() {
        assertThat(decide(SnapshotTrigger.SCREEN_OFF).action).isEqualTo(Action.HEAVY)
        assertThat(decide(SnapshotTrigger.POWER_CONNECTED, lastHeavyMinutesAgo = 0).action).isEqualTo(Action.HEAVY)
        assertThat(decide(SnapshotTrigger.LEVEL_CHANGED).action).isEqualTo(Action.SAMPLE)
        assertThat(decide(SnapshotTrigger.PACKAGE_CHANGED).action).isEqualTo(Action.LIGHT)
    }

    @Test
    fun appOpenRefreshAndUnlockAreDebouncedToOneAMinute() {
        assertThat(decide(SnapshotTrigger.APP_OPENED, lastHeavyMinutesAgo = 0).action).isEqualTo(Action.NONE)
        assertThat(decide(SnapshotTrigger.USER_PRESENT, lastHeavyMinutesAgo = 0).action).isEqualTo(Action.NONE)
        assertThat(decide(SnapshotTrigger.PULL_TO_REFRESH, lastHeavyMinutesAgo = 1).action).isEqualTo(Action.HEAVY)
        assertThat(decide(SnapshotTrigger.APP_OPENED).action).isEqualTo(Action.HEAVY)
    }

    @Test
    fun aHeavySnapshotWithoutAccessIsLightAndOwed() {
        val owed = decide(SnapshotTrigger.SCREEN_OFF, available = false)
        assertThat(owed).isEqualTo(Decision(Action.LIGHT, heavyOwed = true))
        // A level change pays the debt once dumps work; a debounced trigger does not swallow it.
        assertThat(decide(SnapshotTrigger.LEVEL_CHANGED, owed = true)).isEqualTo(Decision(Action.HEAVY, heavyOwed = false))
        assertThat(decide(SnapshotTrigger.APP_OPENED, lastHeavyMinutesAgo = 0, owed = true).action).isEqualTo(Action.HEAVY)
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
    fun aRetentionCutUsesElapsedTimeInTheCurrentBootAndTheBootTableBeforeIt() {
        val hourMs = 3_600_000L
        val old = BootEntity(7, 7, "fp", firstWallMs = 1_000_000_000_000, firstElapsedNs = 5_000_000_000, null, imported = false)
        val current =
            BootEntity(8, 8, "fp", firstWallMs = 1_000_000_000_000 + 48 * hourMs, firstElapsedNs = 30_000_000_000, null, imported = false)
        val now = 1_000_000_000_000 + 50 * hourMs
        val nowElapsedNs = 2 * hourMs * 1_000_000 + 30_000_000_000

        assertThat(Retention.cutKey(listOf(old, current), current, nowElapsedNs, now, hourMs)).isEqualTo(
            Cut(
                8,
                nowElapsedNs - hourMs * 1_000_000,
            ),
        )
        // Ten hours ago is before this boot: the earlier boot's first sighting plus the wall-clock offset.
        assertThat(Retention.cutKey(listOf(old, current), current, nowElapsedNs, now, 10 * hourMs))
            .isEqualTo(Cut(7, old.firstElapsedNs + 40 * hourMs * 1_000_000))
        assertThat(Retention.cutKey(listOf(old, current), current, nowElapsedNs, now, 100 * hourMs)).isNull()
    }
}

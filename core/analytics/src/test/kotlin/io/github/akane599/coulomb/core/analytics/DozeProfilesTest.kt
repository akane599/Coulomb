// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DozeProfilesTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    /** The duration keys of the Java initialisers: a phone that waits an hour before deep idle. */
    private val slow =
        mapOf(
            "light_after_inactive_to" to 4 * minute,
            "inactive_to" to 30 * minute,
            "idle_after_inactive_to" to 30 * minute,
            "motion_inactive_to" to 10 * minute,
            "idle_to" to hour,
            "max_idle_to" to 6 * hour,
        )

    /** A phone that ships with its own string and idles after five minutes. */
    private val quickBaseline = "light_after_inactive_to=30000,inactive_to=180000,idle_after_inactive_to=120000,skip_sensing=true"
    private val quick =
        mapOf(
            "light_after_inactive_to" to 30_000L,
            "inactive_to" to 3 * minute,
            "idle_after_inactive_to" to 2 * minute,
            "motion_inactive_to" to 3 * minute,
            "idle_to" to hour,
            "max_idle_to" to 6 * hour,
        )

    @Test
    fun balancedBringsDeepIdleToHalfAnHourOnASlowPhone() {
        val plan = checkNotNull(DozeProfiles.plan(DozeProfile.BALANCED, null, slow))
        assertThat(plan.value).isEqualTo("inactive_to=900000,idle_after_inactive_to=900000")
        assertThat(plan.changes)
            .containsExactly(
                DozeChange("inactive_to", 30 * minute, 15 * minute),
                DozeChange(
                    "idle_after_inactive_to",
                    30 * minute,
                    15 * minute,
                ),
            )
    }

    @Test
    fun aProfileNeverMakesAPhoneDozeLater() {
        val balanced = checkNotNull(DozeProfiles.plan(DozeProfile.BALANCED, quickBaseline, quick))
        assertThat(balanced.changes).isEmpty()
        // Nothing to change: the value is the baseline as it was.
        assertThat(balanced.value).isEqualTo(quickBaseline)
        val aggressive = checkNotNull(DozeProfiles.plan(DozeProfile.AGGRESSIVE, quickBaseline, quick))
        assertThat(aggressive.changes).containsExactly(DozeChange("idle_to", hour, 2 * hour))
    }

    @Test
    fun theBaselinesPairsAreKeptInTheirOrder() {
        val plan = checkNotNull(DozeProfiles.plan(DozeProfile.AGGRESSIVE, quickBaseline, quick))
        assertThat(plan.value).isEqualTo("$quickBaseline,idle_to=7200000")
        val rewritten = checkNotNull(DozeProfiles.plan(DozeProfile.BALANCED, "skip_sensing=true,inactive_to=1800000,x_oem=7", slow))
        assertThat(rewritten.value).isEqualTo("skip_sensing=true,inactive_to=900000,x_oem=7,idle_after_inactive_to=900000")
    }

    @Test
    fun aKeyThePhoneDoesNotListIsLeftOut() {
        val plan = checkNotNull(DozeProfiles.plan(DozeProfile.AGGRESSIVE, null, mapOf("inactive_to" to 30 * minute)))
        assertThat(plan.value).isEqualTo("inactive_to=300000")
    }

    @Test
    fun anIdlePeriodStaysWithinThePhonesLongest() {
        val short = slow + ("max_idle_to" to 90 * minute)
        val plan = checkNotNull(DozeProfiles.plan(DozeProfile.AGGRESSIVE, null, short))
        assertThat(plan.changes.single { it.key == "idle_to" }.toMs).isEqualTo(90 * minute)
        // Already longer than the target: left alone.
        val long = slow + ("idle_to" to 3 * hour)
        assertThat(checkNotNull(DozeProfiles.plan(DozeProfile.AGGRESSIVE, null, long)).changes.map { it.key }).doesNotContain("idle_to")
    }

    @Test
    fun aValueThatIsNotPairsGivesNoPlan() {
        assertThat(DozeProfiles.plan(DozeProfile.BALANCED, "inactive_to", slow)).isNull()
        assertThat(DozeProfiles.plan(DozeProfile.BALANCED, "inactive_to=1,,idle_to=2", slow)).isNull()
        assertThat(DozeProfiles.pairs("a=1,b=two,a=3")).containsExactly("a", "3", "b", "two")
        assertThat(DozeProfiles.pairs(null)).isEmpty()
        assertThat(DozeProfiles.pairs("")).isEmpty()
    }

    @Test
    fun deepIdleComesAfterTheTwoWaits() {
        assertThat(DozeProfiles.idleAfterMs(quick)).isEqualTo(5 * minute)
        assertThat(DozeProfiles.idleAfterMs(mapOf("inactive_to" to minute))).isNull()
    }

    @Test
    fun aWriteIsInForceWhenEveryChangedKeyReadsItsValue() {
        val plan = checkNotNull(DozeProfiles.plan(DozeProfile.BALANCED, null, slow))
        assertThat(DozeProfiles.inForce(plan, slow + mapOf("inactive_to" to 15 * minute, "idle_after_inactive_to" to 15 * minute))).isTrue()
        assertThat(DozeProfiles.inForce(plan, slow + ("inactive_to" to 15 * minute))).isFalse()
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.Provenance
import org.junit.Test
import kotlin.random.Random

class AttributionTest {
    private val minute = 60_000L

    /**
     * A snapshot [atMin] minutes into boot [boot]. The statistics started [resetAtMin] minutes into
     * the same boot, so they have counted `atMin − resetAtMin` minutes; the wall clock is
     * [clockShiftMs] off the elapsed one, and the start time moves with it, as on the platform.
     */
    private fun snapshot(
        atMin: Long,
        values: Map<String, Double>,
        resetAtMin: Long = 0,
        boot: Int = 1,
        countedBeforeBootMin: Long = 0,
        clockShiftMs: Long = 0,
        knowsSince: Boolean = true,
    ): Counters<String> {
        val elapsed = atMin * minute
        val wallBase = 1_700_000_000_000L + clockShiftMs
        return Counters(
            bootSeq = boot,
            elapsedMs = elapsed,
            wallMs = wallBase + elapsed,
            epoch =
                StatsEpoch(
                    startWallMs = wallBase + resetAtMin * minute,
                    sinceStartMs = if (knowsSince) (atMin - resetAtMin + countedBeforeBootMin) * minute else null,
                ),
            values = values,
        )
    }

    @Test
    fun aWindowIsTheChangeOfEachKey() {
        val window =
            Attribution.window(
                snapshot(10, mapOf("cpu" to 10.0, "gone" to 5.0, "same" to 2.0)),
                snapshot(40, mapOf("cpu" to 14.0, "new" to 3.0, "same" to 2.0)),
            )
        assertThat(window.values).containsExactly("cpu", 4.0, "new", 3.0)
        assertThat(window.resetInside).isFalse()
        assertThat(window.partial).isFalse()
        // The removed key counts as gone down: it gives nothing, never negative use.
        assertThat(window.wentDown).isEqualTo(1)
    }

    @Test
    fun aCounterThatGoesDownCountsAsNothing() {
        val window = Attribution.window(snapshot(10, mapOf("app" to 10.0)), snapshot(20, mapOf("app" to 4.0)))
        assertThat(window.values).isEmpty()
        assertThat(window.wentDown).isEqualTo(1)
    }

    @Test
    fun aClockChangeMovesTheStartTimeButIsNoReset() {
        val a = snapshot(10, mapOf("cpu" to 10.0))
        val b = snapshot(40, mapOf("cpu" to 25.0), clockShiftMs = 3 * 3_600_000L)
        assertThat(a.epoch.startWallMs).isNotEqualTo(b.epoch.startWallMs)
        assertThat(Attribution.resetBetween(a, b)).isFalse()
        // Without the counted time, the start time measured against the clock offset decides.
        val a2 = snapshot(10, mapOf("cpu" to 10.0), knowsSince = false)
        val b2 = snapshot(40, mapOf("cpu" to 25.0), clockShiftMs = -7 * minute, knowsSince = false)
        assertThat(Attribution.resetBetween(a2, b2)).isFalse()
        assertThat(Attribution.window(a2, b2).values).containsExactly("cpu", 15.0)
    }

    @Test
    fun aResetLongAfterTheLastSnapshotMakesTheWindowALowerBound() {
        val a = snapshot(10, mapOf("cpu" to 10.0))
        val b = snapshot(90, mapOf("cpu" to 3.0), resetAtMin = 60)
        val window = Attribution.window(a, b)
        assertThat(window.values).containsExactly("cpu", 3.0)
        assertThat(window.resetInside).isTrue()
        assertThat(window.partial).isTrue()
        // The same reset found by its start time alone.
        val byStart =
            Attribution.window(
                snapshot(10, mapOf("cpu" to 10.0), knowsSince = false),
                snapshot(90, mapOf("cpu" to 3.0), resetAtMin = 60, knowsSince = false),
            )
        assertThat(byStart.resetInside).isTrue()
        assertThat(byStart.partial).isTrue()
    }

    @Test
    fun aResetAcrossARebootIsFoundFromTheCountedTime() {
        val a = snapshot(300, mapOf("cpu" to 50.0), resetAtMin = 0)
        // No reset: the new boot's counting continues from the old total.
        val kept = snapshot(30, mapOf("cpu" to 60.0), boot = 2, countedBeforeBootMin = 320)
        assertThat(Attribution.resetBetween(a, kept)).isFalse()
        // A reset in the new boot: the counted time is less than the boot's own uptime.
        val reset = snapshot(30, mapOf("cpu" to 4.0), boot = 2, resetAtMin = 20)
        assertThat(Attribution.resetBetween(a, reset)).isTrue()
        assertThat(Attribution.window(a, reset).partial).isTrue()
    }

    @Test
    fun splittingAtEachResetAddsUpToWhatReallyHappened() {
        // Property: random use across up to three resets, each seen by a snapshot within a minute
        // before it, sums to the true total use.
        val random = Random(11)
        repeat(300) {
            val keys = List(random.nextInt(1, 6)) { "k$it" }
            var counters = keys.associateWith { random.nextDouble(0.0, 100.0) }
            var resetAt = 0L
            var now = 10L
            val chain = mutableListOf(snapshot(now, counters, resetAtMin = resetAt))
            val truth = keys.associateWith { 0.0 }.toMutableMap()
            repeat(random.nextInt(1, 4)) {
                // Use, a snapshot one minute before the reset, the reset, then more use.
                val before = keys.associateWith { random.nextDouble(0.0, 20.0) }
                now += random.nextLong(5, 60)
                counters = keys.associateWith { counters.getValue(it) + before.getValue(it) }
                chain += snapshot(now, counters, resetAtMin = resetAt)
                keys.forEach { truth[it] = truth.getValue(it) + before.getValue(it) }
                resetAt = now + 1
                val after = keys.associateWith { if (random.nextBoolean()) random.nextDouble(0.0, 20.0) else 0.0 }
                now = resetAt + random.nextLong(5, 60)
                counters = after
                chain += snapshot(now, counters, resetAtMin = resetAt)
                keys.forEach { truth[it] = truth.getValue(it) + after.getValue(it) }
            }
            val window = Attribution.window(chain)
            assertThat(window.resetInside).isTrue()
            assertThat(window.partial).isFalse()
            for (key in keys) {
                assertThat(window.values[key] ?: 0.0).isWithin(1e-9).of(truth.getValue(key))
            }
        }
    }

    @Test
    fun unattributedDrainGetsItsRowAboveTenPercent() {
        assertThat(Attribution.reconcile(100.0, 91.0)).isEqualTo(Reconciliation(9.0, showRow = false))
        assertThat(Attribution.reconcile(100.0, 89.0).showRow).isTrue()
        assertThat(Attribution.reconcile(null, 89.0)).isEqualTo(Reconciliation(null, showRow = false))
        assertThat(Attribution.reconcile(100.0, null).unattributedMah).isNull()
    }

    @Test
    fun scalingToMeasuredIsDerived() {
        val (scaled, provenance) = checkNotNull(Attribution.scaleToMeasured(mapOf("a" to 30.0, "b" to 10.0), measuredMah = 60.0))
        assertThat(scaled).containsExactly("a", 45.0, "b", 15.0)
        assertThat(provenance).isEqualTo(Provenance.DERIVED)
        assertThat(Attribution.scaleToMeasured(mapOf("a" to 0.0), 60.0)).isNull()
    }
}

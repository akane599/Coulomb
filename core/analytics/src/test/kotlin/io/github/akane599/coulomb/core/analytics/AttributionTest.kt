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

    /** A snapshot [elapsedMin] into the kernel boot under boot number [boot], with [countedMin] of statistics. */
    private fun during(
        boot: Int,
        elapsedMin: Double,
        countedMin: Double?,
        cpu: Double,
        clockShiftMs: Long = 0,
    ): Counters<String> {
        val elapsed = (elapsedMin * minute).toLong()
        val wall = 1_700_000_000_000L + clockShiftMs + elapsed
        // The platform sets a new start time when the system server starts.
        return Counters(boot, elapsed, wall, StatsEpoch(wall - minute, countedMin?.let { (it * minute).toLong() }), mapOf("cpu" to cpu))
    }

    @Test
    fun aSystemRestartIsNoResetAndItsStepAddsNothing() {
        // As stored on the S25 Ultra: 78 h into the kernel boot the system server restarts, the boot
        // count rises, and the statistics carry on at 100 h with 5 of the 18 minutes counted. On
        // battery the platform then charges every app its CPU time since boot again, so the later
        // counters are no lower bound either.
        val before = during(310, 4707.0, 6003.0, cpu = 4700.0)
        val after = during(311, 4725.0, 6008.4, cpu = 5400.0)
        assertThat(Attribution.systemRestartBetween(before, after)).isTrue()
        assertThat(Attribution.resetBetween(before, after)).isFalse()
        assertThat(Attribution.step(listOf(before, after))).isEqualTo(Step.RESTART)
        val window = Attribution.window(before, after)
        assertThat(window.values).isEmpty()
        assertThat(window.resetInside).isFalse()
        assertThat(window.partial).isTrue()
        assertThat(window.uncounted).isEqualTo(1)
    }

    @Test
    fun theStepsAroundASystemRestartStillCount() {
        val start = during(310, 4600.0, 5896.0, cpu = 4650.0)
        val before = during(310, 4707.0, 6003.0, cpu = 4700.0)
        val after = during(311, 4725.0, 6008.4, cpu = 5400.0)
        val end = during(311, 4800.0, 6083.4, cpu = 5430.0)
        val window = Attribution.window(listOf(start, before, after, end))
        // 50 before the restart and 30 after it; the 700 across it are the platform's second count.
        assertThat(window.values).containsExactly("cpu", 80.0)
        assertThat(window.partial).isTrue()
        assertThat(window.uncounted).isEqualTo(1)
        // Without the two snapshots around it, the whole stretch is one step that cannot be counted.
        val merged = Attribution.window(listOf(start, end), listOf(Attribution.step(listOf(start, before, after, end))))
        assertThat(merged.values).isEmpty()
        assertThat(merged.uncounted).isEqualTo(1)
    }

    @Test
    fun aResetAtASystemRestartIsFoundButItsStepStaysOut() {
        val before = during(310, 4707.0, 6003.0, cpu = 4700.0)
        // Counted time went back and fits in the 18 minutes since: the statistics restarted.
        val reset = during(311, 4725.0, 10.0, cpu = 2.0)
        assertThat(Attribution.resetBetween(before, reset)).isTrue()
        // Whether the reset came before or after the restart is not known, so neither is the second count.
        assertThat(Attribution.step(listOf(before, reset))).isEqualTo(Step.RESTART)
        // Counted time that went back but does not fit is the platform losing what it had not written.
        assertThat(Attribution.resetBetween(before, during(311, 4725.0, 5990.0, cpu = 4703.0))).isFalse()
        // Young statistics: 12 minutes counted after 5 fits both readings, and reads as no reset.
        val young = during(310, 4707.0, 5.0, cpu = 1.0)
        assertThat(Attribution.resetBetween(young, during(311, 4725.0, 12.0, cpu = 3.0))).isFalse()
        assertThat(Attribution.resetBetween(young, during(311, 4725.0, 3.0, cpu = 0.5))).isTrue()
        // Exactly as much as before is not going back.
        assertThat(Attribution.resetBetween(young, during(311, 4725.0, 5.0, cpu = 1.0))).isFalse()
    }

    @Test
    fun aResetAfterASystemRestartClearsTheSecondCount() {
        val before = during(310, 4707.0, 6003.0, cpu = 4700.0)
        val after = during(311, 4725.0, 6008.4, cpu = 5400.0)
        val reset = during(311, 4800.0, 30.0, cpu = 5.0)
        val step = Attribution.step(listOf(before, after, reset))
        assertThat(step).isEqualTo(Step.RESET_UNSEEN)
        val window = Attribution.window(listOf(before, reset), listOf(step))
        assertThat(window.values).containsExactly("cpu", 5.0)
        assertThat(window.resetInside).isTrue()
        assertThat(window.partial).isTrue()
        assertThat(window.uncounted).isEqualTo(0)
    }

    @Test
    fun aSystemRestartWithoutCountedTimeIsTakenAsNoReset() {
        val before = during(310, 4707.0, null, cpu = 4700.0)
        val after = during(311, 4725.0, null, cpu = 4703.0)
        // The start time moved, as it can at a system server start; that alone proves nothing.
        assertThat(before.epoch.startWallMs).isNotEqualTo(after.epoch.startWallMs)
        assertThat(Attribution.resetBetween(before, after)).isFalse()
        assertThat(Attribution.step(listOf(before, after))).isEqualTo(Step.RESTART)
    }

    @Test
    fun aRebootIsNotASystemRestart() {
        val before = during(310, 4707.0, 6003.0, cpu = 4700.0)
        // Elapsed time started again.
        assertThat(Attribution.systemRestartBetween(before, during(311, 3.0, 6006.0, cpu = 4701.0))).isFalse()
        // Elapsed time is later, but the boot began two minutes later by the wall clock: a reboot.
        val rebooted = during(311, 4725.0, 6003.0 + 4725.0, cpu = 4800.0, clockShiftMs = 2 * minute)
        assertThat(Attribution.systemRestartBetween(before, rebooted)).isFalse()
        assertThat(Attribution.resetBetween(before, rebooted)).isFalse()
        assertThat(Attribution.step(listOf(before, rebooted))).isEqualTo(Step.CONTINUES)
        // One boot number is one boot, whatever the clocks say.
        assertThat(Attribution.systemRestartBetween(before, during(310, 4725.0, 6021.0, cpu = 4703.0))).isFalse()
        // Just inside the tolerance the wall clock may wobble.
        val unknown = during(310, 4707.0, null, cpu = 4700.0)
        val wobble = during(311, 4725.0, null, cpu = 4703.0, clockShiftMs = Attribution.KERNEL_BOOT_TOLERANCE_MS)
        assertThat(Attribution.systemRestartBetween(unknown, wobble)).isTrue()
        assertThat(Attribution.systemRestartBetween(unknown, wobble.copy(wallMs = wobble.wallMs + 1))).isFalse()
        // A boot that had run 30 s cannot have restarted 45 s of wall time away from itself.
        val early = during(310, 0.5, null, cpu = 1.0)
        assertThat(Attribution.systemRestartBetween(early, during(311, 2.0, null, cpu = 2.0, clockShiftMs = 45_000))).isFalse()
        assertThat(Attribution.systemRestartBetween(early, during(311, 2.0, null, cpu = 2.0, clockShiftMs = 20_000))).isTrue()
    }

    @Test
    fun aClockChangeDoesNotHideASystemRestart() {
        val before = during(310, 4707.0, 6003.0, cpu = 4700.0)
        // The clock was corrected by 41 days between the two; the counted time still carried on.
        val after = during(311, 4725.0, 6008.4, cpu = 5400.0, clockShiftMs = 41 * 24 * 60 * minute)
        assertThat(Attribution.systemRestartBetween(before, after)).isTrue()
        assertThat(Attribution.resetBetween(before, after)).isFalse()
        assertThat(Attribution.window(before, after).values).isEmpty()
    }

    @Test
    fun aStretchIsJudgedPairByPair() {
        // A reboot six hours later by the clock, then 78 h on a system restart that lost 12 minutes.
        val shift = 6 * 60 * minute
        val a = during(309, 300.0, 1000.0, cpu = 100.0)
        val b = during(310, 10.0, 1009.0, cpu = 110.0, clockShiftMs = shift)
        val c = during(310, 4707.0, 5706.0, cpu = 4700.0, clockShiftMs = shift)
        val d = during(311, 4725.0, 5711.6, cpu = 5400.0, clockShiftMs = shift)
        // The two ends alone read as a reset: the counted time grew by less than the last boot's age.
        assertThat(Attribution.resetBetween(a, d)).isTrue()
        assertThat(Attribution.step(listOf(a, b, c))).isEqualTo(Step.CONTINUES)
        assertThat(Attribution.step(listOf(a, b, c, d))).isEqualTo(Step.RESTART)
        // A reset inside a longer stretch has use before it that no counter kept.
        val reset = during(310, 2000.0, 20.0, cpu = 9.0, clockShiftMs = shift)
        assertThat(Attribution.step(listOf(a, b, reset))).isEqualTo(Step.RESET_UNSEEN)
        assertThat(
            Attribution.step(listOf(b.copy(elapsedMs = 1979 * minute, wallMs = b.wallMs + 1969 * minute), reset)),
        ).isEqualTo(Step.RESET)
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

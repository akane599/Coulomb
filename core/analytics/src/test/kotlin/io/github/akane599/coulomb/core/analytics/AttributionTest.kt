// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.Provenance
import org.junit.Test
import kotlin.random.Random

class AttributionTest {
    private fun counters(
        epoch: Long,
        vararg values: Pair<String, Double>,
    ) = Counters(epoch, values.toMap())

    @Test
    fun aWindowIsTheChangeOfEachKey() {
        val window =
            Attribution.window(
                counters(1, "cpu" to 10.0, "gone" to 5.0, "same" to 2.0),
                counters(
                    1,
                    "cpu" to 14.0,
                    "new" to 3.0,
                    "same" to 2.0,
                ),
            )
        assertThat(window.values).containsExactly("cpu", 4.0, "new", 3.0)
        assertThat(window.resetInside).isFalse()
        assertThat(window.partial).isFalse()
        assertThat(window.wentDown).isEqualTo(0)
    }

    @Test
    fun aCounterThatGoesDownCountsAsNothing() {
        val window = Attribution.window(counters(1, "app" to 10.0), counters(1, "app" to 4.0))
        assertThat(window.values).isEmpty()
        assertThat(window.wentDown).isEqualTo(1)
    }

    @Test
    fun withoutASnapshotBeforeTheResetOnlyThePartAfterItIsKnown() {
        val window = Attribution.window(counters(1, "cpu" to 10.0), counters(2, "cpu" to 3.0))
        assertThat(window.values).containsExactly("cpu", 3.0)
        assertThat(window.resetInside).isTrue()
        assertThat(window.partial).isTrue()
        // A snapshot from another epoch cannot stand in for the one before the reset.
        assertThat(Attribution.window(counters(1, "cpu" to 10.0), counters(3, "cpu" to 3.0), counters(2, "cpu" to 1.0)).partial).isTrue()
    }

    @Test
    fun splittingAtAResetAddsUpToWhatReallyHappened() {
        // Property: random use before and after a reset, measured from a, the last snapshot
        // before the reset, and b, equals the true total use.
        val random = Random(11)
        repeat(500) {
            val keys = List(random.nextInt(1, 8)) { "k$it" }
            val start = keys.associateWith { random.nextDouble(0.0, 100.0) }
            val before = keys.associateWith { random.nextDouble(0.0, 20.0) }
            val after = keys.associateWith { if (random.nextBoolean()) random.nextDouble(0.0, 20.0) else 0.0 }
            val a = Counters(1, start)
            val atReset = Counters(1, keys.associateWith { start.getValue(it) + before.getValue(it) })
            val b = Counters(2, after)
            val window = Attribution.window(a, b, atReset)
            assertThat(window.partial).isFalse()
            for (key in keys) {
                val truth = before.getValue(key) + after.getValue(key)
                assertThat(window.values[key] ?: 0.0).isWithin(1e-9).of(truth)
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

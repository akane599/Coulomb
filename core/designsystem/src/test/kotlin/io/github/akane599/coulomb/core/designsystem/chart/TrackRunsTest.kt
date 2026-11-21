// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.chart

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** SPEC 10.6: events closer than 3 dp merge into a density span; zooming in splits it into ticks. */
class TrackRunsTest {
    private fun runs(vararg marks: Pair<Float, Float>) =
        TrackRuns.of(marks.map { Triple(it.first, it.second, false) }, width = 300f, minSpan = 3f, merge = 3f)

    @Test
    fun aLongStateIsASpanAndAShortOneATick() {
        assertThat(
            runs(10f to 40f, 100f to 100.5f),
        ).containsExactly(TrackRun.Span(10f, 40f, light = false), TrackRun.Tick(100.25f)).inOrder()
    }

    @Test
    fun eventsCloserThanTheMergeDistanceBecomeOneDensitySpan() {
        val found = runs(10f to 10f, 12f to 12f, 14f to 14f, 30f to 30f)

        assertThat(found).containsExactly(TrackRun.Dense(10f, 14f, count = 3), TrackRun.Tick(30f)).inOrder()
    }

    @Test
    fun zoomingInSplitsADensitySpanBackIntoTicks() {
        // The same three events at four times the scale are 8 px apart.
        assertThat(
            runs(40f to 40f, 48f to 48f, 56f to 56f),
        ).containsExactly(TrackRun.Tick(40f), TrackRun.Tick(48f), TrackRun.Tick(56f)).inOrder()
    }

    @Test
    fun aSpanBetweenEventsKeepsThemApart() {
        val found = runs(10f to 10f, 11f to 20f, 21f to 21f)

        assertThat(found).containsExactly(TrackRun.Tick(10f), TrackRun.Span(11f, 20f, light = false), TrackRun.Tick(21f)).inOrder()
    }

    @Test
    fun aDensitySpanIsNeverThinnerThanAVisibleSpan() {
        assertThat(runs(10f to 10f, 11f to 11f)).containsExactly(TrackRun.Dense(10f, 13f, count = 2))
    }

    @Test
    fun marksOutsideThePlotAreDroppedAndOnesAcrossItsEdgeClipped() {
        val found = runs(-50f to -10f, -5f to 20f, 290f to 400f, 500f to 600f)

        assertThat(found).containsExactly(TrackRun.Span(0f, 20f, light = false), TrackRun.Span(290f, 300f, light = false)).inOrder()
    }
}

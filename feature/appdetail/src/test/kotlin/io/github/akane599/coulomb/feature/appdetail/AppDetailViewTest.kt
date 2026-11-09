// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import io.github.akane599.coulomb.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppDetailViewTest {
    @Test
    fun jobNameDropsThePrefixTypeAndOwnPackage() {
        // Android 17 always adds the type letter; Android 16 only behind a flag (JobStatus.getWakelockTag).
        assertEquals(".sync.FeedPrefetchJob", AppDetailView.jobName("*job*r/$PACKAGE/.sync.FeedPrefetchJob", PACKAGE))
        assertEquals(".sync.FeedPrefetchJob", AppDetailView.jobName("*job*/$PACKAGE/.sync.FeedPrefetchJob", PACKAGE))
        assertEquals(".Fetch", AppDetailView.jobName("*job*e/$PACKAGE/$PACKAGE.Fetch", PACKAGE))
        assertEquals(".Fetch", AppDetailView.jobName("*job*u/$PACKAGE/.Fetch", PACKAGE))
    }

    @Test
    fun jobNameDropsTheTraceTagAndNamespace() {
        assertEquals(".Sync", AppDetailView.jobName("*job*r/#feed#@media@$PACKAGE/.Sync", PACKAGE))
        assertEquals(".Sync", AppDetailView.jobName("*job*r/@media@$PACKAGE/.Sync", PACKAGE))
    }

    @Test
    fun jobNameKeepsAnotherPackageAndASourceTag() {
        assertEquals("other.lib.Worker", AppDetailView.jobName("*job*r/$PACKAGE/other.lib.Worker", PACKAGE))
        // A job scheduled for another app with a source tag: the tag is its name.
        assertEquals("SyncAdapter:feed", AppDetailView.jobName("*job*r/SyncAdapter:feed", PACKAGE))
        assertEquals(".Fetch", AppDetailView.jobName("*job*r/$PACKAGE/.Fetch", null))
    }

    @Test
    fun jobsAreSplitFromWakelocks() {
        val view = AppDetailView.of(labDetail(), scaled = false)

        assertEquals(listOf(".sync.FeedPrefetchJob"), view.jobs.map { it.name })
        assertEquals(31L, view.jobs.single().runs)
        assertEquals(listOf("HalcyonPush:NotificationWakeLock", "ExoPlayer:WakeLockManager"), view.wakelocks.map { it.tag })
    }

    @Test
    fun unscaledIsModeledOverTheMeasuredTotal() {
        val view = AppDetailView.of(labDetail(), scaled = false)

        assertEquals(402.0, view.mah!!, 1e-9)
        assertEquals(402.0 / 1_204.0, view.share!!, 1e-9)
        assertEquals(Provenance.MODELED, view.provenance)
    }

    @Test
    fun scaledIsDerivedAsOnDrain() {
        val view = AppDetailView.of(labDetail(), scaled = true)

        assertEquals(402.0 * 1_204.0 / 1_062.0, view.mah!!, 1e-9)
        assertEquals(402.0 / 1_062.0, view.share!!, 1e-9)
        assertEquals(Provenance.DERIVED, view.provenance)
    }

    @Test
    fun scalingNeedsAMeasuredTotal() {
        val drains = labDrains().copy(measuredMah = null, measuredProvenance = null)
        val view = AppDetailView.of(labDetail(drains = drains), scaled = true)

        assertEquals(1.0, view.factor, 0.0)
        assertEquals(Provenance.MODELED, view.provenance)
    }

    @Test
    fun noUseHasNoFigure() {
        val view = AppDetailView.of(labDetail(used = false), scaled = false)

        assertNull(view.mah)
        assertNull(view.share)
    }

    @Test
    fun trendTopIsRoundAndCoversTheLargestDay() {
        assertEquals(4.0, TrendScale.niceTop(0.0), 0.0)
        assertEquals(4.0, TrendScale.niceTop(3.2), 0.0)
        assertEquals(6.0, TrendScale.niceTop(5.0), 0.0)
        assertEquals(400.0, TrendScale.niceTop(400.0), 0.0)
        assertEquals(600.0, TrendScale.niceTop(402.0), 0.0)
        assertEquals(1_000.0, TrendScale.niceTop(1_000.0), 0.0)
        assertEquals(1_600.0, TrendScale.niceTop(1_204.0), 0.0)
    }

    @Test
    fun trendKeepsTheUpdateOnlyInsideItsDays() {
        val inside = TrendScale.of(labTrend(gapDays = setOf(2)), UPDATED)
        val outside = TrendScale.of(labTrend(), UPDATED.minusDays(30))

        assertEquals(UPDATED, inside.updated)
        assertEquals(1, inside.gaps)
        assertNull(outside.updated)
    }
}

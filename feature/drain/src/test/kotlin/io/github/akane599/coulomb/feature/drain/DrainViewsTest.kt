// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.ui.graphics.Color
import io.github.akane599.coulomb.core.data.AppDrain
import io.github.akane599.coulomb.core.data.ComponentDrain
import io.github.akane599.coulomb.core.data.PowerComponent
import io.github.akane599.coulomb.core.model.WakeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrainViewsTest {
    @Test
    fun appsAtThreeOrMorePercentGetRowsAndTheRestFoldIntoTheRemainder() {
        val view = appsView(report(), includeSystem = true, scaled = false)
        val names = view.rows.map { (it as? AppsEntry.App)?.app?.label ?: (if (it is AppsEntry.Device) "device" else "uid") }
        // 1,204 mAh is the denominator: 36 mAh is 2.99%, so Camera folds; the device row ranks by size.
        assertEquals(listOf("Halcyon", "Wayline", "uid", "device", "Tally", "Driftly", "Northwind Mail"), names)
        assertEquals(listOf("Camera", "Lumen", "Quire", "Parcel", "Nfc Service"), view.rest.map { it.label })
        assertEquals(36.0 + 22 + 14 + 9 + 4 + 18, view.remainderMah, 1e-9)
    }

    @Test
    fun theRowsAndTheRemainderAddUpToTheModeledTotal() {
        val view = appsView(report(), includeSystem = false, scaled = false)
        val rows = view.rows.sumOf { it.mah }
        assertEquals(1_062.0, rows + view.remainderMah, 1e-9)
    }

    @Test
    fun hidingSystemAppsMovesThemIntoTheRemainder() {
        val view = appsView(report(), includeSystem = false, scaled = false)
        assertTrue(view.rows.none { it is AppsEntry.App && it.app.isSystem })
        assertEquals(listOf(1_000, 10_077, 1_027), view.hidden.map { it.uid })
    }

    @Test
    fun atLeastThreeAppsGetRowsHoweverSmall() {
        val tiny = labDrains().copy(apps = (1..5).map { AppDrain(10_000 + it, "App $it", false, null, 1.0 * it) })
        val view = appsView(report(drains = tiny), includeSystem = true, scaled = false)
        assertEquals(listOf(5, 4, 3), view.rows.filterIsInstance<AppsEntry.App>().map { it.app.uid - 10_000 })
    }

    @Test
    fun unattributedGetsItsOwnRowOnlyAboveTenPercentOfMeasured() {
        // 142 of 1,204 is 11.8%.
        assertEquals(142.0, appsView(report(), true, scaled = false).unattributedMah ?: 0.0, 1e-9)
        val close = labDrains().copy(modeledMah = 1_100.0)
        assertNull(appsView(report(drains = close), true, scaled = false).unattributedMah)
    }

    @Test
    fun scalingSpreadsTheMeasuredTotalOverTheModeledRowsAndDropsTheGap() {
        val view = appsView(report(), includeSystem = true, scaled = true)
        assertTrue(view.scaled)
        assertNull(view.unattributedMah)
        assertEquals(1_204.0, view.denominator, 1e-9)
        val shares = view.rows.sumOf { view.share(it.mah) } + view.share(view.remainderMah)
        assertEquals(1.0, shares, 1e-9)
    }

    @Test
    fun withoutAMeasuredTotalNothingScales() {
        val unmeasured = labDrains().copy(measuredMah = null, measuredProvenance = null)
        val view = appsView(report(drains = unmeasured), includeSystem = true, scaled = true)
        assertEquals(1.0, view.factor, 0.0)
        assertEquals(1_062.0, view.denominator, 1e-9)
    }

    @Test
    fun kernelAndReasonGroupsPreferTheirFirstSource() {
        val groups = wakeGroups(report()).toMap()
        assertEquals(setOf(WakeKind.KERNEL), groups.getValue(WakeGroup.KERNEL).map { it.kind }.toSet())
        assertEquals(setOf(WakeKind.WAKEUP_REASON), groups.getValue(WakeGroup.REASONS).map { it.kind }.toSet())
        // Longest held first.
        assertEquals("PowerManagerService.WakeLocks", groups.getValue(WakeGroup.KERNEL).first().tag)
    }

    @Test
    fun withoutTheFirstSourceAGroupFallsBackToTheOther() {
        val report =
            report().let { r ->
                r.copy(wakes = r.wakes.filter { it.kind != WakeKind.KERNEL && it.kind != WakeKind.WAKEUP_REASON })
            }
        val groups = wakeGroups(report).toMap()
        assertEquals(setOf(WakeKind.WAKEUP_SOURCE), groups.getValue(WakeGroup.KERNEL).map { it.kind }.toSet())
        assertEquals(setOf(WakeKind.RESUME_CAUSE), groups.getValue(WakeGroup.REASONS).map { it.kind }.toSet())
    }

    @Test
    fun emptyGroupsAreLeftOut() {
        val partialOnly = report().let { r -> r.copy(wakes = r.wakes.filter { it.kind == WakeKind.PARTIAL }) }
        assertEquals(listOf(WakeGroup.PARTIAL), wakeGroups(partialOnly).map { it.first })
    }

    @Test
    fun neutralComponentsShareOneSegmentAtTheBarsEnd() {
        val parts =
            listOf(
                ComponentDrain(PowerComponent.SCREEN, 10.0),
                ComponentDrain(PowerComponent.OTHER, 8.0),
                ComponentDrain(PowerComponent.CPU, 6.0),
                ComponentDrain(PowerComponent.IDLE, 4.0),
            )
        val hues = mapOf(PowerComponent.SCREEN to Color.Blue, PowerComponent.CPU to Color.Red)
        val segments = barSegments(parts, { hues[it] ?: Color.Gray }, Color.Gray)
        assertEquals(listOf(Color.Blue, Color.Red, Color.Gray), segments.map { it.first })
        assertEquals(listOf(10.0, 6.0, 12.0), segments.map { it.second })
    }
}

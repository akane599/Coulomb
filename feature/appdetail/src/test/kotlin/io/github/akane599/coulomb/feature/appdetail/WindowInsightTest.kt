// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import io.github.akane599.coulomb.core.insights.ShownInsight
import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.model.InsightState
import io.github.akane599.coulomb.core.model.insight.Evidence
import io.github.akane599.coulomb.core.model.insight.EvidenceUnit
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.model.insight.RuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Audit BUG-033: App detail's insight is the window's, chosen among all of the app's open ones. */
class WindowInsightTest {
    private val detail = labDetail()

    private fun shown(
        id: Long,
        severity: InsightSeverity,
        from: Long,
        to: Long,
    ) = ShownInsight(
        Insight(
            id,
            RuleId.EXCESSIVE_WAKELOCK,
            severity,
            InsightState.SEEN,
            null,
            detail.uid,
            null,
            Evidence(3.0, 2.0, EvidenceUnit.HOURS, from, to),
            to,
        ),
        "Halcyon",
    )

    @Test
    fun aMoreSevereInsightOfAnotherDateDoesNotHideTheWindowsOwn() {
        val drains = detail.report.drains
        val days = 3 * 86_400_000L
        val older = shown(1, InsightSeverity.ACT, drains.fromWallMs - days, drains.fromWallMs - days + HOUR)
        val inWindow = shown(2, InsightSeverity.WATCH, drains.fromWallMs + HOUR, drains.toWallMs)
        // Most severe first, as the store gives them.
        assertEquals(inWindow, listOf(older, inWindow).inWindow(detail))
        assertNull(listOf(older).inWindow(detail))
    }
}

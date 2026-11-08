// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import io.github.akane599.coulomb.core.data.AlarmEntry
import io.github.akane599.coulomb.core.data.AppDrain
import io.github.akane599.coulomb.core.data.ComponentDrain
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.data.PowerComponent
import io.github.akane599.coulomb.core.data.WakeBasis
import io.github.akane599.coulomb.core.data.WakeCoverage
import io.github.akane599.coulomb.core.data.WakeEntry
import io.github.akane599.coulomb.core.data.WindowDrains
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ComparisonTest {
    private val yesterday = DrainSpan.Days(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 7))
    private val today = DrainSpan.Days(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 8))

    @Test
    fun everyFigureIsPerHourOfTheTimeItsCounterRan() {
        // Yesterday: 4 h on battery, 3 of them measured, 2 with the screen off.
        val first =
            period(
                yesterday,
                onBatteryMs = 4 * HOUR,
                measuredMs = 3 * HOUR,
                screenOffOnBatteryMs = 2 * HOUR,
                measuredMah = 300.0,
                apps = listOf(app(HALCYON, 200.0)),
                deviceMah = 40.0,
                otherMah = 8.0,
                components = listOf(ComponentDrain(PowerComponent.SCREEN, 120.0)),
                wakes = listOf(partial(HALCYON, 30 * MINUTE, 10), reason("425 ipa", 6 * MINUTE, 30)),
                alarms = listOf(AlarmEntry(HALCYON, "Halcyon", null, 12)),
                durationMs = 10 * HOUR,
            )
        // Today: 2 h on battery, all measured, 1 with the screen off.
        val second =
            period(
                today,
                onBatteryMs = 2 * HOUR,
                measuredMs = 2 * HOUR,
                screenOffOnBatteryMs = HOUR,
                measuredMah = 300.0,
                apps = listOf(app(HALCYON, 200.0)),
                deviceMah = 10.0,
                components = listOf(ComponentDrain(PowerComponent.SCREEN, 120.0)),
                wakes = listOf(partial(HALCYON, 30 * MINUTE, 10), reason("425 ipa", 6 * MINUTE, 30)),
                alarms = listOf(AlarmEntry(HALCYON, "Halcyon", null, 12)),
                durationMs = 2 * HOUR,
            )

        val comparison = compare(first, second, includeSystem = false)

        // The measured drain over the time its windows cover: 100 then 150 mAh/h, half as much again.
        assertEquals(100.0, first.drainPerHour!!, 1e-9)
        assertEquals(150.0, second.drainPerHour!!, 1e-9)
        assertEquals(0.5, comparison.drainChange!!, 1e-9)
        // Apps, the phone's own row, the small apps and components per hour on battery.
        assertRow(comparison.apps.single { it.key == "app-$HALCYON" }, 50.0, 100.0)
        assertRow(comparison.apps.single { it.key == "device" }, 10.0, 5.0)
        assertRow(comparison.apps.single { it.key == "small" }, 2.0, 0.0)
        assertRow(comparison.components.single(), 30.0, 60.0)
        // Partial wakelocks and alarms per hour with the screen off on battery; reasons per hour on battery.
        val partial = comparison.wakes.single { it.key == WakeGroup.PARTIAL.name }
        assertEquals(WakeBasis.SCREEN_OFF_ON_BATTERY, partial.basis)
        assertEquals(RateUnit.MINUTES, partial.unit)
        assertRow(partial.rows.single(), 15.0, 30.0)
        val reasons = comparison.wakes.single { it.key == WakeGroup.REASONS.name }
        assertEquals(WakeBasis.ON_BATTERY, reasons.basis)
        assertRow(reasons.rows.single(), 1.5, 3.0)
        val alarms = comparison.wakes.single { it.key == "ALARMS" }
        assertEquals(RateUnit.COUNT, alarms.unit)
        assertRow(alarms.rows.single(), 6.0, 12.0)
    }

    @Test
    fun rowsRankByTheSizeOfTheirChangeAndAMissingAppIsZero() {
        val first = period(yesterday, apps = listOf(app(HALCYON, 100.0), app(WAYLINE, 60.0)))
        val second = period(today, apps = listOf(app(HALCYON, 110.0), app(TALLY, 30.0)))

        val apps = compare(first, second, includeSystem = false).apps.filterIsSubject<CompareSubject.App>()

        // Per hour of 2 h: Wayline fell 30, Tally rose 15, Halcyon rose 5.
        assertEquals(listOf(WAYLINE, TALLY, HALCYON), apps.map { (it.subject as CompareSubject.App).app.uid })
        assertRow(apps[0], 30.0, 0.0)
        assertRow(apps[1], 0.0, 15.0)
        assertEquals(-30.0, apps[0].change!!, 1e-9)
    }

    @Test
    fun systemAppsFollowTheSwitchAndAreCounted() {
        val system = AppDrain(1_000, null, isSystem = true, iconPackage = null, mah = 50.0)
        val first = period(yesterday, apps = listOf(app(HALCYON, 100.0), system))
        val second = period(today, apps = listOf(app(HALCYON, 100.0), system.copy(mah = 80.0)))

        val hidden = compare(first, second, includeSystem = false)
        assertTrue(hidden.apps.none { it.key == "app-1000" })
        assertEquals(1, hidden.hiddenSystem)
        // Left out, they share one row, as on Drain.
        val together = hidden.apps.single { it.subject == CompareSubject.HiddenSystem }
        assertRow(together, 25.0, 40.0)
        val shown = compare(first, second, includeSystem = true)
        assertRow(shown.apps.single { it.key == "app-1000" }, 25.0, 40.0)
        assertEquals(0, shown.hiddenSystem)
    }

    @Test
    fun aPeriodBelowTheRateFloorGivesNoRates() {
        // Ten minutes on battery: 25 mAh still makes a drain rate (SPEC 6.2), the apps get none.
        val short = period(today, onBatteryMs = 10 * MINUTE, measuredMs = 10 * MINUTE, measuredMah = 25.0, apps = listOf(app(HALCYON, 5.0)))
        assertEquals(150.0, short.drainPerHour!!, 1e-9)
        assertFalse(short.tooShort)
        val comparison = compare(period(yesterday, apps = listOf(app(HALCYON, 100.0))), short, includeSystem = false)
        val halcyon = comparison.apps.single { it.key == "app-$HALCYON" }
        assertEquals(50.0, halcyon.first!!, 1e-9)
        assertNull(halcyon.second)
        assertNull(halcyon.change)

        // Ten minutes and 5 mAh: too little for any rate.
        val tiny = period(today, onBatteryMs = 10 * MINUTE, measuredMs = 10 * MINUTE, measuredMah = 5.0)
        assertNull(tiny.drainPerHour)
        assertTrue(tiny.tooShort)
        assertNull(compare(period(yesterday), tiny, includeSystem = false).drainChange)
    }

    @Test
    fun aPeriodWithoutRecordsOrWithoutItsSourceHasNoFiguresNotZeros() {
        val first = period(yesterday, apps = listOf(app(HALCYON, 100.0)), wakes = listOf(partial(HALCYON, 30 * MINUTE, 10)))
        val unread = Period(today, report = null)

        val comparison = compare(first, unread, includeSystem = false)
        assertNull(comparison.apps.single { it.key == "app-$HALCYON" }.second)
        assertNull(comparison.wakes.single { it.key == WakeGroup.PARTIAL.name }.secondCoverage)

        // Batterystats not collected today: its groups have no figures for the day.
        val blind = period(today, wakes = emptyList(), coverage = WakeCoverage(Coverage.NONE, Coverage.NONE, Coverage.NONE))
        val partial = compare(first, blind, includeSystem = false).wakes.single { it.key == WakeGroup.PARTIAL.name }
        assertTrue(partial.comparable)
        assertEquals(Coverage.NONE, partial.secondCoverage)
        assertNull(partial.rows.single().second)
    }

    @Test
    fun aDrainWithNothingToShowGivesNoRate() {
        assertNull(period(today, measuredMah = 0.0).drainPerHour)
        assertNull(period(today, measuredMah = -40.0).drainPerHour)
        assertFalse(period(today, measuredMah = -40.0).tooShort)
        // Exactly 15 minutes is enough (SPEC 6.2).
        assertEquals(20.0, period(today, onBatteryMs = 15 * MINUTE, measuredMah = 5.0).drainPerHour!!, 1e-9)
    }

    @Test
    fun rootGroupsArePerHourOfTheWholePeriodAndNeedItWhole() {
        val root = WakeCoverage(Coverage.WHOLE, Coverage.WHOLE, Coverage.WHOLE)
        val wakes = listOf(kernel(WakeKind.WAKEUP_SOURCE, "IPA_WS"), resume("Abort:Pending Wakeup Sources", 12))
        // 20 minutes held and 12 resume causes, over 2 hours, then over 4 with 2 of them on the charger.
        val first = period(yesterday, wakes = wakes, coverage = root)
        val second = period(today, wakes = wakes, coverage = root, durationMs = 4 * HOUR)

        val comparison = compare(first, second, includeSystem = false)

        val kernel = comparison.wakes.single { it.key == WakeGroup.KERNEL.name }
        assertEquals(WakeBasis.WHOLE_SPAN, kernel.basis)
        assertEquals(RateUnit.MINUTES, kernel.unit)
        assertRow(kernel.rows.single(), 10.0, 5.0)
        val resumes = comparison.wakes.single { it.key == WakeGroup.REASONS.name }
        assertEquals(WakeBasis.WHOLE_SPAN, resumes.basis)
        assertEquals(RateUnit.COUNT, resumes.unit)
        assertRow(resumes.rows.single(), 6.0, 3.0)

        // Root for part of today only: its base would be the whole day, so the day gives no rates.
        val partly = period(today, wakes = wakes, coverage = WakeCoverage(Coverage.WHOLE, Coverage.PARTIAL, Coverage.PARTIAL))
        val partial = compare(first, partly, includeSystem = false).wakes.single { it.key == WakeGroup.KERNEL.name }
        assertTrue(partial.firstCounted)
        assertFalse(partial.secondCounted)
        assertNull(partial.rows.single().second)
    }

    @Test
    fun aGroupCountedTooBrieflyHasNoRatesWhileTheOthersDo() {
        val wakes = listOf(partial(HALCYON, 30 * MINUTE, 10), reason("425 ipa", 6 * MINUTE, 30))
        val first = period(yesterday, wakes = wakes)
        // Three hours on battery, ten minutes of them with the screen off.
        val second = period(today, onBatteryMs = 3 * HOUR, screenOffOnBatteryMs = 10 * MINUTE, wakes = wakes)

        val comparison = compare(first, second, includeSystem = false)

        val partial = comparison.wakes.single { it.key == WakeGroup.PARTIAL.name }
        assertFalse(partial.secondCounted)
        assertNull(partial.rows.single().second)
        val reasons = comparison.wakes.single { it.key == WakeGroup.REASONS.name }
        assertTrue(reasons.secondCounted)
        assertRow(reasons.rows.single(), 3.0, 2.0)
    }

    @Test
    fun rowsWithoutAChangeComeAfterThoseWithOneByTheirLargerRate() {
        val first = period(yesterday, apps = listOf(app(HALCYON, 100.0), app(WAYLINE, 2.0)))
        val unread = Period(today, report = null)

        val rows = compare(first, unread, includeSystem = false).apps

        assertEquals(listOf("app-$HALCYON", "app-$WAYLINE"), rows.map { it.key })
        assertTrue(rows.all { it.change == null })
    }

    @Test
    fun changesTooSmallToShowSortByTheLargerRate() {
        // Per hour over 2 h: Wayline 5.00 -> 5.02, Halcyon 50.00 -> 50.01. Both show as no change.
        val first = period(yesterday, apps = listOf(app(HALCYON, 100.0), app(WAYLINE, 10.0), app(TALLY, 20.0)))
        val second = period(today, apps = listOf(app(HALCYON, 100.02), app(WAYLINE, 10.04), app(TALLY, 30.0)))

        val rows = compare(first, second, includeSystem = false).apps

        assertEquals(listOf("app-$TALLY", "app-$HALCYON", "app-$WAYLINE"), rows.map { it.key })
    }

    @Test
    fun aGroupReadFromDifferentSourcesDoesNotCompare() {
        // Yesterday in shell mode (S5's kernel wakelocks), today with root and no S5 rows (S9 sources).
        val shell = period(yesterday, wakes = listOf(kernel(WakeKind.KERNEL, "IPA_WS")))
        val root =
            period(
                today,
                wakes = listOf(kernel(WakeKind.WAKEUP_SOURCE, "IPA_WS")),
                coverage = WakeCoverage(Coverage.WHOLE, Coverage.WHOLE, Coverage.WHOLE),
            )

        val kernel = compare(shell, root, includeSystem = false).wakes.single { it.key == WakeGroup.KERNEL.name }

        assertFalse(kernel.comparable)
        assertTrue(kernel.rows.isEmpty())
    }

    private fun assertRow(
        row: ComparedRow,
        first: Double,
        second: Double,
    ) {
        assertEquals(first, row.first!!, 1e-9)
        assertEquals(second, row.second!!, 1e-9)
    }

    private inline fun <reified T : CompareSubject> List<ComparedRow>.filterIsSubject() = filter { it.subject is T }

    @Suppress("LongParameterList") // One report's figures.
    private fun period(
        days: DrainSpan.Days,
        onBatteryMs: Long = 2 * HOUR,
        measuredMs: Long = onBatteryMs,
        screenOffOnBatteryMs: Long = onBatteryMs / 2,
        measuredMah: Double = 200.0,
        apps: List<AppDrain> = emptyList(),
        deviceMah: Double = 0.0,
        otherMah: Double = 0.0,
        components: List<ComponentDrain> = emptyList(),
        wakes: List<WakeEntry> = emptyList(),
        alarms: List<AlarmEntry> = emptyList(),
        durationMs: Long = onBatteryMs,
        coverage: WakeCoverage = SHELL_COVERAGE,
    ) = Period(
        days,
        DrainReport(
            span = days,
            drains =
                WindowDrains(
                    fromWallMs = 0,
                    toWallMs = durationMs,
                    measuredMah = measuredMah,
                    measuredProvenance = Provenance.MEASURED,
                    modeledMah = apps.sumOf { it.mah } + deviceMah + otherMah,
                    apps = apps,
                    otherMah = otherMah,
                    deviceMah = deviceMah,
                ),
            startLevel = 90,
            endLevel = 60,
            durationMs = durationMs,
            screenOffMs = durationMs - (onBatteryMs - screenOffOnBatteryMs),
            onBatteryMs = onBatteryMs,
            screenOffOnBatteryMs = screenOffOnBatteryMs,
            measuredMs = measuredMs,
            lowerBound = true,
            chargedInSpan = durationMs > onBatteryMs,
            wakes = wakes,
            wakeCoverage = coverage,
            alarms = alarms,
            components = components,
        ),
    )

    private fun app(
        uid: Int,
        mah: Double,
    ) = AppDrain(uid, "App $uid", isSystem = false, iconPackage = null, mah = mah)

    private fun partial(
        uid: Int,
        ms: Long,
        count: Long,
    ) = WakeEntry(WakeKind.PARTIAL, uid, "App $uid", null, "*job*/app/.Sync", ms, count, null, null)

    private fun reason(
        tag: String,
        ms: Long,
        count: Long,
    ) = WakeEntry(WakeKind.WAKEUP_REASON, null, null, null, tag, ms, count, null, null)

    private fun kernel(
        kind: WakeKind,
        tag: String,
    ) = WakeEntry(kind, null, null, null, tag, 20 * MINUTE, 40, null, null)

    private fun resume(
        tag: String,
        count: Long,
    ) = WakeEntry(WakeKind.RESUME_CAUSE, null, null, null, tag, 0, count, null, null)

    private companion object {
        const val HALCYON = 10_123
        const val WAYLINE = 10_241
        const val TALLY = 10_310
    }
}

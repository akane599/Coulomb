// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import android.os.BatteryManager
import io.github.akane599.coulomb.core.analytics.CapacityEstimate
import io.github.akane599.coulomb.core.analytics.StressLevel
import io.github.akane599.coulomb.core.data.CapacityChange
import io.github.akane599.coulomb.core.data.CapacityTrend
import io.github.akane599.coulomb.core.data.ChargeCurvePoint
import io.github.akane599.coulomb.core.data.ChargeHabits
import io.github.akane599.coulomb.core.data.ChargeSessionDetail
import io.github.akane599.coulomb.core.data.ChargeSessionRow
import io.github.akane599.coulomb.core.data.Charger
import io.github.akane599.coulomb.core.data.CrossCheck
import io.github.akane599.coulomb.core.data.Cycles
import io.github.akane599.coulomb.core.data.EstimateDay
import io.github.akane599.coulomb.core.data.EstimatePoint
import io.github.akane599.coulomb.core.data.HealthReport
import io.github.akane599.coulomb.core.model.BatteryDate
import io.github.akane599.coulomb.core.model.BatteryDateSource
import io.github.akane599.coulomb.core.model.BatteryDates
import io.github.akane599.coulomb.core.model.DesignCapacity
import io.github.akane599.coulomb.core.model.DesignCapacitySource
import io.github.akane599.coulomb.core.model.dumpsys.OemHealthValue
import java.time.LocalDate
import java.time.ZoneOffset

// The lab's Health (design-lab/health.html), as of noon UTC on 9 Nov 2026.

internal val TODAY: LocalDate = LocalDate.of(2026, 11, 9)
internal val NOW_MS: Long = TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

private fun at(
    date: LocalDate,
    hour: Int,
    minute: Int,
): Long = date.atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

internal val LAB_DESIGN = DesignCapacity(5_000.0, DesignCapacitySource.POWER_PROFILE)

internal fun labCapacity(
    estimate: Double = 4_610.0,
    low: Double = 4_540.0,
    high: Double = 4_680.0,
    health: Double? = 92.2,
    windows: Int = 14,
) = CapacityEstimate(estimate, low, high, health, windows, quality = 0.8)

/** Fourteen windows from 12 August, the estimate settling from 4,720 to 4,610 mAh and narrowing. */
internal fun labTrend(): CapacityTrend {
    val first = LocalDate.of(2026, 8, 12)
    val estimates = listOf(4_720, 4_665, 4_680, 4_645, 4_650, 4_665, 4_650, 4_640, 4_650, 4_640, 4_625, 4_635, 4_625, 4_610)
    val halves = listOf(150, 135, 125, 115, 100, 100, 92, 88, 85, 80, 78, 75, 72, 70)
    val readings = listOf(4_750, 4_640, 4_710, 4_600, 4_680, 4_730, 4_620, 4_660, 4_690, 4_610, 4_650, 4_670, 4_630, 4_645)
    val days = estimates.indices.map { first.plusDays(it * 7L) }
    return CapacityTrend(
        points = days.mapIndexed { i, day -> EstimatePoint(at(day, 9, 0), readings[i].toDouble()) },
        days =
            days.mapIndexed { i, day ->
                EstimateDay(day, estimates[i].toDouble(), (estimates[i] - halves[i]).toDouble(), (estimates[i] + halves[i]).toDouble())
            },
    )
}

internal val LAB_HABITS =
    ChargeHabits(
        sessions = 30,
        depthPct = 46.0,
        highMinutesPerDay = 370.0,
        chargeTempDeciC = 334.0,
        stress = mapOf(StressLevel.LOW to 18, StressLevel.MODERATE to 9, StressLevel.HIGH to 3),
    )

internal val LAB_SESSIONS =
    listOf(
        ChargeSessionRow(3, at(TODAY, 0, 30), at(TODAY, 9, 8), 38, 100, 0.62, StressLevel.HIGH, 0.9),
        ChargeSessionRow(2, at(TODAY.minusDays(1), 12, 40), at(TODAY.minusDays(1), 13, 20), 55, 81, 0.26, StressLevel.LOW, 0.9),
        ChargeSessionRow(1, at(TODAY.minusDays(5), 23, 10), at(TODAY.minusDays(4), 7, 0), 31, 100, 0.69, StressLevel.MODERATE, 0.9),
    )

internal val LAB_CHARGERS =
    listOf(
        Charger(1, "Desk charger", BatteryManager.BATTERY_PLUGGED_AC, 19.2, NOW_MS - 90 * DAY, NOW_MS - 3 * HOUR),
        Charger(2, "Car charger", BatteryManager.BATTERY_PLUGGED_USB, 14.8, NOW_MS - 60 * DAY, NOW_MS - 3 * DAY),
        Charger(3, "", BatteryManager.BATTERY_PLUGGED_USB, 9.7, NOW_MS - 40 * DAY, at(LocalDate.of(2026, 10, 21), 18, 0)),
    )

internal fun labReport() =
    HealthReport(
        capacity = labCapacity(),
        design = LAB_DESIGN,
        change = CapacityChange(LocalDate.of(2026, 8, 12), deltaMah = -110.0, firstHalfWidthMah = 150.0),
        crossCheck = CrossCheck(systemSohPct = 93, oemHealth = listOf(OemHealthValue("mSavedBatteryAsoc", 91))),
        cycles = Cycles(platformCount = 312, coulombCount = 61.4, since = LocalDate.of(2026, 8, 12), platformCounted = 63),
        trend = labTrend(),
        habits = LAB_HABITS,
        sessions = LAB_SESSIONS,
        moreSessions = true,
        chargers = LAB_CHARGERS,
    )

internal val LAB_DATES =
    BatteryDates(
        manufactured = BatteryDate(LocalDate.of(2025, 3, 14), BatteryDateSource.SYSTEM),
        firstUsed = BatteryDate(LocalDate.of(2025, 4, 2), BatteryDateSource.SYSTEM),
    )

/** A first week: no estimate, no habits yet, one charge, no charger profile, no dates. */
internal fun newReport() =
    HealthReport(
        capacity = null,
        design = LAB_DESIGN,
        change = null,
        crossCheck = CrossCheck(systemSohPct = null, oemHealth = emptyList()),
        cycles = Cycles(platformCount = null, coulombCount = 0.0, since = null, platformCounted = null),
        trend = CapacityTrend(emptyList(), emptyList()),
        habits = null,
        sessions = emptyList(),
        moreSessions = false,
        chargers = emptyList(),
    )

/**
 * SPEC 11.12 harden: no design capacity, a wide interval, six-digit-ish figures, sources that
 * disagree, a long charger name, a session without cycles or stress.
 */
internal fun hardenedReport() =
    labReport().copy(
        capacity = labCapacity(estimate = 12_340.0, low = 11_200.0, high = 13_480.0, health = null, windows = 1),
        design = null,
        change = CapacityChange(LocalDate.of(2026, 8, 12), deltaMah = 1_820.0, firstHalfWidthMah = 400.0),
        crossCheck =
            CrossCheck(
                systemSohPct = 84,
                oemHealth = listOf(OemHealthValue("mSavedBatteryAsoc", 97), OemHealthValue("mSavedBatteryBsoh", 76)),
            ),
        cycles = Cycles(platformCount = 1_204, coulombCount = 1_187.3, since = null, platformCounted = null),
        habits = LAB_HABITS.copy(highMinutesPerDay = null, chargeTempDeciC = null),
        sessions =
            listOf(
                ChargeSessionRow(9, at(TODAY.minusDays(40), 23, 59), at(TODAY.minusDays(39), 11, 1), 2, 100, null, null, 0.2),
            ),
        moreSessions = false,
        chargers =
            listOf(
                Charger(
                    5,
                    "The very long name of the charger in the upstairs study by the window",
                    0,
                    120.4,
                    NOW_MS - DAY,
                    NOW_MS - 40 * DAY,
                ),
                Charger(6, "", BatteryManager.BATTERY_PLUGGED_WIRELESS, 7.5, NOW_MS - DAY, NOW_MS - DAY),
            ),
    )

/** The first lab session with its curve: full speed near 19 W to 80%, then slowing to full. */
internal fun labSession(): ChargeSessionDetail {
    val start = LAB_SESSIONS.first().startWallMs
    val curve =
        (0..103).map { i ->
            val t = start + i * 5 * MINUTE
            val level = if (i < 30) 38 + i * 42 / 30 else minOf(100, 80 + (i - 30) * 20 / 60)
            val watts = if (i < 30) 19.0 - i * 0.03 else maxOf(1.2, 18.0 - (i - 30) * 0.28)
            ChargeCurvePoint(t, level, watts, tempDeciC = 300 + minOf(i, 40))
        }
    return ChargeSessionDetail(
        row = LAB_SESSIONS.first(),
        plugType = BatteryManager.BATTERY_PLUGGED_AC,
        charger = LAB_CHARGERS.first(),
        peakW = 19.0,
        fullSpeedW = 18.4,
        ccToCvLevel = 80,
        minutesAtOrAbove80 = 368,
        minutesAbove38C = 0,
        meanTempDeciC = 331,
        maxTempDeciC = 342,
        curve = curve,
    )
}

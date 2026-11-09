// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.charging

import android.os.BatteryManager
import io.github.akane599.coulomb.core.analytics.ChargePhase
import io.github.akane599.coulomb.core.analytics.ChargeTime
import io.github.akane599.coulomb.core.analytics.ChargeTimeBasis
import io.github.akane599.coulomb.core.data.ChargeCurvePoint
import io.github.akane599.coulomb.core.data.Charger
import io.github.akane599.coulomb.core.data.ChargingNow
import io.github.akane599.coulomb.core.data.LiveCharger
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt
import kotlin.math.sin

// The lab's Charging (design-lab/charging.html): a desk charger since 18:31 UTC, 52% at 18:50.

private val TODAY: LocalDate = LocalDate.of(2026, 11, 9)
private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private val START_MS: Long = TODAY.atTime(18, 31).toInstant(ZoneOffset.UTC).toEpochMilli()
internal val NOW_MS: Long = START_MS + 19 * MINUTE
private const val START_LEVEL = 21
private const val LAB_LEVEL = 52
private const val STEP_MS = 5 * SECOND
private const val FULL_SPEED_W = 18.6
private const val RAMP_MS = 40 * SECOND
private const val START_TEMP = 297
private const val LAB_TEMP = 338

internal val DESK =
    Charger(
        id = 1,
        name = "Desk charger",
        plugType = BatteryManager.BATTERY_PLUGGED_AC,
        baselineW = 19.2,
        firstSeenWallMs = START_MS - 60 * 24 * 60 * MINUTE,
        lastSeenWallMs = START_MS,
    )

/**
 * A sample every five seconds from 18:31 to 18:50, the level going [from] to [to] and the power as
 * [watts] gives it at each share of the way (null before calibration), warming as it goes.
 */
private fun curve(
    from: Int,
    to: Int,
    watts: (share: Double, i: Int) -> Double?,
): List<ChargeCurvePoint> {
    val span = NOW_MS - START_MS
    return (0..span / STEP_MS).map { i ->
        val t = i * STEP_MS
        val share = t.toDouble() / span
        ChargeCurvePoint(
            wallMs = START_MS + t,
            levelPct = (from + (to - from) * share).roundToInt(),
            watts = watts(share, i.toInt())?.let { (it * 10).roundToInt() / 10.0 },
            tempDeciC = (START_TEMP + (LAB_TEMP - START_TEMP) * share).roundToInt(),
        )
    }
}

/** A short ramp, then full speed with a little ripple, ending at [last]. */
private fun fullSpeed(
    speed: Double,
    last: Double,
): (Double, Int) -> Double =
    { share, i ->
        val ramp = (share * (NOW_MS - START_MS) / RAMP_MS).coerceAtMost(1.0)
        if (share >= 1.0) last else speed * (0.3 + 0.7 * ramp) + 0.35 * sin(i / 7.0)
    }

/** From [high] down to [low] along the charge, as the current tapers. */
private fun tapering(
    high: Double,
    low: Double,
): (Double, Int) -> Double = { share, _ -> high + (low - high) * share }

private fun labCurve(): List<ChargeCurvePoint> = curve(START_LEVEL, LAB_LEVEL, fullSpeed(FULL_SPEED_W, 18.4))

internal fun labNow(): ChargingNow =
    ChargingNow(
        levelPct = LAB_LEVEL,
        plugType = BatteryManager.BATTERY_PLUGGED_AC,
        watts = 18.4,
        currentMa = 4_580.0,
        voltageMv = 4_020,
        tempDeciC = LAB_TEMP,
        tempRiseDeciC = LAB_TEMP - START_TEMP,
        phase = ChargePhase.FULL_SPEED,
        charger = LiveCharger.Known(DESK, shareOfBaseline = 0.96),
        targetPct = 80,
        belowHighest = false,
        toTarget = ChargeTime(16.3, 19.6, ChargeTimeBasis.SAME_CHARGER, charges = 7),
        toFull = ChargeTime(44.2, 52.6, ChargeTimeBasis.SAME_CHARGER, charges = 7),
        startWallMs = START_MS,
        startLevelPct = START_LEVEL,
        curve = labCurve(),
        wallMs = NOW_MS,
    )

/** Before calibration: no current, so no power, phase, charger or times. */
internal fun uncalibratedNow(): ChargingNow =
    labNow().copy(
        watts = null,
        currentMa = null,
        phase = null,
        charger = LiveCharger.Learning(BatteryManager.BATTERY_PLUGGED_AC),
        toTarget = null,
        toFull = null,
        curve = curve(START_LEVEL, LAB_LEVEL) { _, _ -> null },
    )

/** A first charge on a new charger: no past charges, so no times yet. */
internal fun newChargerNow(): ChargingNow =
    labNow().copy(
        charger = LiveCharger.New(BatteryManager.BATTERY_PLUGGED_USB, fullSpeedW = 9.8),
        plugType = BatteryManager.BATTERY_PLUGGED_USB,
        watts = 9.6,
        currentMa = 2_390.0,
        toTarget = null,
        toFull = null,
        curve = curve(START_LEVEL, LAB_LEVEL, fullSpeed(9.8, 9.6)),
    )

/** Two minutes in: still learning the charger, one sample of curve too few for a line. */
internal fun learningNow(): ChargingNow =
    labNow().copy(
        levelPct = START_LEVEL,
        charger = LiveCharger.Learning(BatteryManager.BATTERY_PLUGGED_AC),
        tempRiseDeciC = null,
        curve = labCurve().take(1),
        toTarget = ChargeTime(41.0, 47.5, ChargeTimeBasis.ANY_CHARGER, charges = 4),
        toFull = null,
    )

/** Plugged in at 74%: the charger cannot be told, slowing by the usual taper, 90% as the target. */
internal fun startedHighNow(): ChargingNow =
    labNow().copy(
        levelPct = 81,
        watts = 9.1,
        currentMa = 2_150.0,
        voltageMv = 4_240,
        phase = ChargePhase.SLOWING,
        charger = LiveCharger.StartedHigh(BatteryManager.BATTERY_PLUGGED_AC),
        targetPct = 90,
        toTarget = ChargeTime(13.6, 17.2, ChargeTimeBasis.SAME_CHARGER, charges = 5),
        toFull = ChargeTime(38.0, 46.4, ChargeTimeBasis.SAME_CHARGER, charges = 5),
        startLevelPct = 74,
        curve = curve(74, 81, tapering(12.4, 9.1)),
    )

/** Held at the 80% target by the phone's charge limit. */
internal fun holdingNow(): ChargingNow =
    labNow().copy(
        levelPct = 80,
        watts = 0.3,
        currentMa = 70.0,
        voltageMv = 4_180,
        phase = ChargePhase.HOLDING,
        toTarget = null,
        toFull = null,
        startLevelPct = 66,
        // Up to the limit by 18:39, then held there on a trickle.
        curve =
            curve(66, 80) { share, _ -> if (share < 0.45) 14.0 - 10 * share else 0.3 }
                .map { it.copy(levelPct = minOf(80, 66 + ((it.wallMs - START_MS) * 14 / (8 * MINUTE)).toInt())) },
    )

/** A phone drawing more than a weak charger gives: the battery is going down while plugged in. */
internal fun drawingNow(): ChargingNow =
    labNow().copy(
        watts = -1.3,
        currentMa = -320.0,
        phase = ChargePhase.DRAWING,
        charger = LiveCharger.New(BatteryManager.BATTERY_PLUGGED_USB, fullSpeedW = 2.4),
        belowHighest = true,
        toTarget = null,
        toFull = null,
        startLevelPct = 55,
        curve = curve(55, LAB_LEVEL, tapering(2.4, -1.3)),
    )

internal fun fullNow(): ChargingNow =
    labNow().copy(
        levelPct = 100,
        watts = 0.2,
        currentMa = 40.0,
        voltageMv = 4_380,
        phase = ChargePhase.FULL,
        targetPct = 100,
        toTarget = null,
        toFull = null,
        startLevelPct = 86,
        curve = curve(86, 100, tapering(8.6, 0.2)),
    )

/** SPEC 11.12 harden: a long charger name, a 100% target, past charges on any charger, a fall in temperature. */
internal fun hardenedNow(): ChargingNow =
    labNow().copy(
        charger =
            LiveCharger.Known(
                DESK.copy(name = "Travel adapter with the long braided cable from the hotel"),
                shareOfBaseline = 0.78,
            ),
        targetPct = 100,
        toTarget = ChargeTime(118.0, 161.0, ChargeTimeBasis.ANY_CHARGER, charges = 3),
        tempRiseDeciC = -6,
    )

/** Held by a charge limit below the 90% target: no time, and the reason is the hold. */
internal fun holdingBelowTargetNow(): ChargingNow = holdingNow().copy(targetPct = 90)

/** SPEC 11.12 harden: a right-to-left charger name in a left-to-right page keeps the line's order. */
internal fun rtlNameNow(): ChargingNow =
    labNow().copy(
        charger =
            LiveCharger.Known(
                DESK.copy(name = "\u0634\u0627\u062D\u0646 \u0627\u0644\u0645\u0643\u062A\u0628"),
                shareOfBaseline = 0.96,
            ),
    )

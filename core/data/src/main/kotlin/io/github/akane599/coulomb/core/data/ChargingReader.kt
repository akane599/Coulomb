// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.analytics.ChargeLevel
import io.github.akane599.coulomb.core.analytics.ChargePhase
import io.github.akane599.coulomb.core.analytics.ChargePhases
import io.github.akane599.coulomb.core.analytics.ChargeSample
import io.github.akane599.coulomb.core.analytics.ChargeTimes
import io.github.akane599.coulomb.core.analytics.ChargerBaseline
import io.github.akane599.coulomb.core.analytics.Charging
import io.github.akane599.coulomb.core.analytics.PastCharge
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.ChargerProfileEntity

/** A closed charge as the time estimate needs it: its charger, where it slowed and its level line. */
internal data class PastChargeRow(
    val sessionId: Long,
    val chargerProfileId: Long?,
    /** `BatteryManager.BATTERY_PLUGGED_*`; 0 without a charge detail. */
    val plugType: Int,
    val ccToCvLevel: Int?,
    val points: List<ChargeLevel>,
)

/** ADR-0046: the charge in progress from its samples, the past charges and the charger profiles. Pure. */
internal object ChargingReader {
    /** [samples] are the open session's in time order, at least one; [history] is newest first. */
    fun now(
        samples: List<BatterySampleEntity>,
        startWallMs: Long,
        history: List<PastChargeRow>,
        profiles: List<ChargerProfileEntity>,
        targetPct: Int,
    ): ChargingNow {
        val last = samples.last()
        val points = samples.map { ChargeSample(it.elapsedNs, it.levelPct, it.currentUa, it.voltageMv, it.tempDeciC, it.plugType) }
        val charger = charger(points, profiles)
        val knownId = (charger as? LiveCharger.Known)?.charger?.id
        // The charger's own taper level; without one, that of past charges on the same plug.
        val usualTaper =
            knownId?.let { id -> median(history.filter { it.chargerProfileId == id }.mapNotNull { it.ccToCvLevel }) }
                ?: median(history.filter { it.plugType == charger.plugType }.mapNotNull { it.ccToCvLevel })
        val levels = samples.map { ChargeLevel(it.elapsedNs, it.levelPct) }
        val past = history.map { PastCharge(sameCharger = knownId != null && it.chargerProfileId == knownId, points = it.points) }
        val level = last.levelPct
        val phase = ChargePhases.of(points, usualTaper)
        // A held or falling level has no time to a higher one (estimator review).
        val timed = phase != ChargePhase.HOLDING && phase != ChargePhase.DRAWING
        return ChargingNow(
            levelPct = level,
            plugType = last.plugType,
            watts = last.currentUa?.let { (it * last.voltageMv.toDouble()) / NW_PER_W },
            currentMa = last.currentUa?.let { it / UA_PER_MA },
            voltageMv = last.voltageMv,
            tempDeciC = last.tempDeciC,
            tempRiseDeciC = (last.tempDeciC - samples.first().tempDeciC).takeIf { samples.size > 1 },
            phase = phase,
            charger = charger,
            targetPct = targetPct,
            belowHighest = level < samples.maxOf { it.levelPct },
            toTarget = if (timed && level < targetPct) ChargeTimes.toLevel(levels, past, targetPct) else null,
            toFull = if (timed && level < FULL) ChargeTimes.toLevel(levels, past, FULL) else null,
            startWallMs = startWallMs,
            startLevelPct = samples.first().levelPct,
            // Signed, unlike a closed charge's curve: power out of the battery is not zero (gate harden).
            curve =
                samples.map {
                    ChargeCurvePoint(
                        it.wallMs,
                        it.levelPct,
                        it.currentUa?.let { ua ->
                            ua * it.voltageMv.toDouble() / NW_PER_W
                        },
                        it.tempDeciC,
                    )
                },
            wallMs = last.wallMs,
        )
    }

    /**
     * ADR-0046 §5: this charge's full speed and plug type, as [Charging.figures] takes them for a
     * closed charge, matched by [Charging.match], the same rule. Only after [MATCH_SPAN_NS] of
     * readings below 50%, so a charger still ramping up is not taken for a slower one.
     */
    private fun charger(
        points: List<ChargeSample>,
        profiles: List<ChargerProfileEntity>,
    ): LiveCharger {
        val figures = Charging.figures(points)
        val plugType = figures.plugType.takeIf { it != 0 } ?: points.last().plugType
        val early = points.filter { it.levelPct < Charging.TAPER_SEARCH_FROM && (it.currentUa ?: 0) > 0 }
        val span = if (early.isEmpty()) 0L else early.last().elapsedNs - early.first().elapsedNs
        val fullSpeed = figures.fullSpeedW?.takeIf { span >= MATCH_SPAN_NS }
        if (fullSpeed == null) {
            // Past 50% the readings that tell full speed can no longer come.
            val past = points.last().levelPct >= Charging.TAPER_SEARCH_FROM
            return if (past) LiveCharger.StartedHigh(plugType) else LiveCharger.Learning(plugType)
        }
        val id = Charging.match(plugType, fullSpeed, profiles.map { ChargerBaseline(it.id, it.plugType, it.baselineW) })
        val profile = profiles.firstOrNull { it.id == id } ?: return LiveCharger.New(plugType, fullSpeed)
        return LiveCharger.Known(HealthReader.charger(profile), fullSpeed / profile.baselineW)
    }

    private fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private const val FULL = 100

    /** Two minutes of readings below 50% before a charger is named (estimator review). */
    private const val MATCH_SPAN_NS = 120_000_000_000L
    private const val NW_PER_W = 1e9
    private const val UA_PER_MA = 1_000.0
}

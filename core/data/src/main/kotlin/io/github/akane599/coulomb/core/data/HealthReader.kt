// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.analytics.Capacity
import io.github.akane599.coulomb.core.analytics.CapacityEstimate
import io.github.akane599.coulomb.core.analytics.CapacityReading
import io.github.akane599.coulomb.core.analytics.StressLevel
import io.github.akane599.coulomb.core.database.dao.LevelTime
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.CapacityEstimateEntity
import io.github.akane599.coulomb.core.database.entity.ChargerProfileEntity
import io.github.akane599.coulomb.core.database.entity.HealthSummaryEntity
import io.github.akane599.coulomb.core.database.entity.SampleAggregateEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.DesignCapacity
import io.github.akane599.coulomb.core.model.dumpsys.OemHealthCodec
import java.time.LocalDate
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Turns stored health rows into the Health screen's read models (ADR-0044). Pure. */
internal object HealthReader {
    /**
     * SPEC 6.3's estimate as of [nowWallMs] from [estimates] (oldest first, as stored), the way
     * the collector makes it at each close, with today's design capacity.
     */
    fun capacity(
        estimates: List<CapacityEstimateEntity>,
        design: DesignCapacity?,
        nowWallMs: Long,
    ): CapacityEstimate? {
        val readings =
            estimates.asReversed().take(Capacity.MAX_WINDOWS).map {
                CapacityReading(it.capacityMah, it.weight, (nowWallMs - it.createdWall).toDouble() / MS_PER_DAY)
            }
        return Capacity.estimate(readings, design?.mah)
    }

    /**
     * Now against the first settled day's estimate, once that day has passed (ADR-0044 §1). A day
     * before [settledDay], when fewer than [Capacity.SETTLED_WINDOWS] windows backed the estimate,
     * has only the SPEC 6.3 floor for an interval, which is a display rule and not its uncertainty.
     */
    fun change(
        summaries: List<HealthSummaryEntity>,
        current: CapacityEstimate?,
        today: LocalDate,
        settledDay: LocalDate?,
    ): CapacityChange? {
        current ?: return null
        settledDay ?: return null
        val first = summaries.firstOrNull { it.estimateMah != null && it.epochDay >= settledDay.toEpochDay() } ?: return null
        val firstDay = LocalDate.ofEpochDay(first.epochDay.toLong())
        if (!firstDay.isBefore(today)) return null
        val mah = first.estimateMah ?: return null
        val then = ((first.ciHighMah ?: mah) - (first.ciLowMah ?: mah)) / 2
        val now = (current.ciHighMah - current.ciLowMah) / 2
        return CapacityChange(firstDay, current.estimateMah - mah, hypot(then, now))
    }

    /** Each value from the latest day that has it. */
    fun crossCheck(summaries: List<HealthSummaryEntity>): CrossCheck =
        CrossCheck(
            systemSohPct = summaries.lastOrNull { it.systemSohPct != null }?.systemSohPct,
            oemHealth = summaries.asReversed().firstNotNullOfOrNull { s -> OemHealthCodec.decode(s.oemHealth).ifEmpty { null } }.orEmpty(),
        )

    /**
     * ADR-0044 §3. Both counts over the same days when two days carry a platform count; else the
     * whole running sum from the first counted charge, [firstCountedDay].
     *
     * A platform count still at 0 while Coulomb has counted a cycle or more is a field the phone
     * does not fill, not a count, so it is left out rather than shown as Measured (gate review M2).
     * A count that went down (a battery swap, a reset) gives no comparison.
     */
    fun cycles(
        summaries: List<HealthSummaryEntity>,
        firstCountedDay: LocalDate?,
    ): Cycles {
        val latest = summaries.lastOrNull() ?: return Cycles(null, 0.0, null, null)
        val whole = Cycles(null, latest.cycleEquivalentsSum, firstCountedDay, null)
        return compared(summaries.filter { (it.cycleCount ?: -1) >= 0 }, whole) ?: whole
    }

    /** [whole] with the platform's count, or both counts over the same days; null when the count is not usable. */
    private fun compared(
        counted: List<HealthSummaryEntity>,
        whole: Cycles,
    ): Cycles? {
        val first = counted.firstOrNull() ?: return null
        val last = counted.last()
        val lastCount = checkNotNull(last.cycleCount)
        val sameDay = last.epochDay <= first.epochDay
        val ours = if (sameDay) whole.coulombCount else last.cycleEquivalentsSum - first.cycleEquivalentsSum
        return when {
            unfilled(lastCount, ours) -> {
                null
            }

            sameDay -> {
                whole.copy(platformCount = lastCount)
            }

            else -> {
                Cycles(
                    platformCount = lastCount,
                    coulombCount = ours,
                    since = LocalDate.ofEpochDay(first.epochDay.toLong()),
                    platformCounted = (lastCount - checkNotNull(first.cycleCount)).takeIf { it >= 0 },
                )
            }
        }
    }

    private fun unfilled(
        platformCount: Int,
        coulombCycles: Double,
    ): Boolean = platformCount == 0 && coulombCycles >= 1.0

    /** Every window as a point; the daily line and band from [settledDay] on, as [change] counts. */
    fun trend(
        estimates: List<CapacityEstimateEntity>,
        summaries: List<HealthSummaryEntity>,
        settledDay: LocalDate?,
    ): CapacityTrend =
        CapacityTrend(
            points = estimates.map { EstimatePoint(it.createdWall, it.capacityMah) },
            days =
                summaries.mapNotNull { s ->
                    if (settledDay == null || s.epochDay < settledDay.toEpochDay()) return@mapNotNull null
                    val mah = s.estimateMah ?: return@mapNotNull null
                    EstimateDay(LocalDate.ofEpochDay(s.epochDay.toLong()), mah, s.ciLowMah ?: mah, s.ciHighMah ?: mah)
                },
        )

    /** ADR-0044 §2 over [charges], the closed charge sessions of the window. */
    fun habits(
        charges: List<SessionEntity>,
        highTime: LevelTime,
    ): ChargeHabits? {
        if (charges.isEmpty()) return null
        val timed = charges.mapNotNull { s -> s.meanTempDeciC?.let { t -> t to durationMs(s) } }.filter { it.second > 0 }
        val weight = timed.sumOf { it.second }.toDouble()
        return ChargeHabits(
            sessions = charges.size,
            depthPct = charges.map { FULL_PCT - it.startLevel }.average(),
            highMinutesPerDay =
                highTime.takeIf { it.watchedMs >= MS_PER_DAY }?.let {
                    it.atOrAboveMs.toDouble() / it.watchedMs * MINUTES_PER_DAY
                },
            chargeTempDeciC = if (weight > 0) timed.sumOf { (t, ms) -> t.toDouble() * ms } / weight else null,
            stress =
                charges
                    .mapNotNull { stress(it) }
                    .groupingBy { it }
                    .eachCount(),
        )
    }

    fun row(session: SessionEntity): ChargeSessionRow =
        ChargeSessionRow(
            id = session.id,
            startWallMs = session.startWall,
            endWallMs = session.endWall ?: session.startWall,
            fromPct = session.startLevel,
            toPct = session.endLevel ?: session.startLevel,
            cycles = session.cycleEquivalents,
            stress = stress(session),
            quality = session.quality,
        )

    fun charger(profile: ChargerProfileEntity): Charger =
        Charger(profile.id, profile.name, profile.plugType, profile.baselineW, profile.firstSeenWall, profile.lastSeenWall)

    /** The curve from raw samples; power is null for a sample stored before calibration. */
    fun curve(samples: List<BatterySampleEntity>): List<ChargeCurvePoint> =
        samples.map { s ->
            ChargeCurvePoint(s.wallMs, s.levelPct, s.currentUa?.let { watts(it, s.voltageMv) }, s.tempDeciC)
        }

    /** The curve from minute aggregates, once the samples are compacted (SPEC 7.7). */
    fun aggregateCurve(rows: List<SampleAggregateEntity>): List<ChargeCurvePoint> =
        rows.map { a ->
            ChargeCurvePoint(a.startWallMs, a.levelLastPct, a.currentMeanUa?.let { watts(it, a.voltageMeanMv) }, a.tempMeanDeciC)
        }

    /** The stored stress index (ADR-0037 §3: the level's ordinal). */
    private fun stress(session: SessionEntity): StressLevel? = session.stressIndex?.roundToInt()?.let { StressLevel.entries.getOrNull(it) }

    /** By the elapsed clock, which a wall-clock change cannot move. */
    private fun durationMs(session: SessionEntity): Long = session.endElapsedNs?.let { (it - session.startElapsedNs) / NS_PER_MS } ?: 0L

    /** Into the battery only: a discharging moment on the charger is no charge power. */
    private fun watts(
        currentUa: Long,
        voltageMv: Int,
    ): Double = (currentUa.coerceAtLeast(0) * voltageMv.toDouble()) / NW_PER_W

    private const val MS_PER_DAY = 86_400_000L
    private const val MINUTES_PER_DAY = 1_440.0
    private const val FULL_PCT = 100
    private const val NW_PER_W = 1e9
    private const val NS_PER_MS = 1_000_000L
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.SnapshotKind
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where a session ends. */
internal data class SessionEnd(
    val elapsedNs: Long,
    val wallMs: Long,
    val snapshot: SnapshotEntity?,
    val levelPct: Int?,
    val chargeUah: Long?,
    val tempDeciC: Int?,
    val reason: SessionCloseReason,
)

/**
 * The values a session keeps once its samples are compacted (SPEC 6.3, ADR-0015), from the
 * samples between its ends, and its awake ratio from its end snapshots' clocks. Pure. The wear
 * columns (stress index, cycle equivalents) and the capacity estimate come from [SessionHealth];
 * deep Doze with P5's Night Report, and stays null until then, which means "not known".
 */
internal object SessionSummary {
    private const val NS_PER_MIN = 60_000_000_000L
    private const val NS_PER_MS = 1_000_000L
    private const val MIN_AWAKE_SPAN_MS = 60_000L
    private const val UA_PER_MA = 1_000.0
    private const val HIGH_LEVEL = 80
    private const val FULL_LEVEL = 100

    /** A sample stands for at most this long; a longer gap is not counted as time at its value. */
    private const val MAX_SAMPLE_SPAN_NS = 120_000_000_000L

    /**
     * A level holds until the next sample for up to this long: every level change is sampled
     * (ADR-0037 §1), so a sparse gap while the phone sleeps on the charger is time at that level.
     * The same bound as the habits' `timeAtOrAbove` (ADR-0044 §2, gate review m2).
     */
    private const val LEVEL_HOLD_NS = 6 * 3_600_000_000_000L
    private const val COUNTER_STEP_UAH = 1_000.0
    private const val LEVEL_QUALITY = 0.5
    private const val GAP_PENALTY = 0.5

    /** A session opened at [at], whose reading had [levelPct] and [tempDeciC]. */
    fun open(
        kind: SessionKind,
        at: SnapshotEntity,
        levelPct: Int,
        tempDeciC: Int,
    ) = SessionEntity(
        kind = kind,
        bootSeq = at.bootSeq,
        startElapsedNs = at.elapsedNs,
        endElapsedNs = null,
        startWall = at.wallMs,
        endWall = null,
        startSnapshotId = at.id.takeIf { at.kind == SnapshotKind.HEAVY },
        endSnapshotId = null,
        startLevel = levelPct,
        endLevel = null,
        startChargeUah = at.chargeUah?.takeIf { it > 0 },
        endChargeUah = null,
        chargeSource = null,
        avgCurrentMa = null,
        peakCurrentMa = null,
        minTempDeciC = tempDeciC,
        meanTempDeciC = tempDeciC,
        maxTempDeciC = tempDeciC,
        awakeRatio = null,
        deepIdleReached = null,
        deepIdleReachedMs = null,
        minutesAtOrAbove80 = null,
        minutesAt100 = null,
        stressIndex = null,
        cycleEquivalents = null,
        closeReason = null,
        quality = 0.0,
    )

    /**
     * Closes [session] at [end]. [samples] are the session's own, in time order and from its boot.
     * [currentUa] turns a sample's raw current into calibrated µA when the sample was stored
     * before calibration finished; null while calibration is still running. [awakeRatio] comes
     * from [awakeRatio] over the session's first and last snapshots.
     */
    fun close(
        session: SessionEntity,
        end: SessionEnd,
        samples: List<BatterySampleEntity>,
        counterReliable: Boolean,
        currentUa: ((Long) -> Long)?,
        awakeRatio: Double? = null,
    ): SessionEntity {
        val currents =
            samples.mapNotNull { s ->
                (s.currentUa ?: s.currentRaw?.let { raw -> currentUa?.invoke(raw) })?.let {
                    s.elapsedNs to
                        it
                }
            }
        val temps = samples.map { it.tempDeciC } + listOfNotNull(session.maxTempDeciC, end.tempDeciC)
        val endCharge = end.chargeUah?.takeIf { it > 0 }
        val source =
            when {
                counterReliable && session.startChargeUah != null && endCharge != null -> ChargeSource.CHARGE_COUNTER
                end.levelPct != null -> ChargeSource.LEVEL_DELTA
                else -> null
            }
        val charging = session.kind == SessionKind.CHARGE
        return session.copy(
            endElapsedNs = end.elapsedNs,
            endWall = end.wallMs,
            endSnapshotId = end.snapshot?.id,
            endLevel = end.levelPct,
            endChargeUah = endCharge,
            chargeSource = source,
            avgCurrentMa = timeWeightedMean(currents)?.div(UA_PER_MA),
            peakCurrentMa = currents.maxOfOrNull { (_, ua) -> if (charging) ua.toDouble() else -ua.toDouble() }?.div(UA_PER_MA),
            minTempDeciC = listOfNotNull(session.minTempDeciC, end.tempDeciC).plus(samples.map { it.tempDeciC }).min(),
            meanTempDeciC = timeWeightedMean(samples.map { it.elapsedNs to it.tempDeciC.toLong() })?.roundToInt() ?: session.meanTempDeciC,
            maxTempDeciC = temps.max(),
            minutesAtOrAbove80 = if (charging) minutesWhere(samples) { it.levelPct >= HIGH_LEVEL } else null,
            minutesAt100 = if (charging) minutesWhere(samples) { it.levelPct >= FULL_LEVEL } else null,
            awakeRatio = awakeRatio,
            closeReason = end.reason,
            quality = quality(session, end, source),
        )
    }

    /**
     * The share of the time from [start] to [end] that the CPU was awake: uptime, which stops in
     * suspend, over elapsed time (ADR-0029 §3, the source SPEC 2 asks for per screen-off session).
     * Null unless both snapshots are of one boot, carry uptime and lie at least [MIN_AWAKE_SPAN_MS]
     * apart: the two clocks are read a few milliseconds apart, which a minute makes negligible.
     */
    fun awakeRatio(
        start: SnapshotEntity?,
        end: SnapshotEntity?,
    ): Double? {
        if (start == null || end == null || start.bootSeq != end.bootSeq) return null
        val from = start.uptimeMs ?: return null
        val to = end.uptimeMs ?: return null
        val elapsedMs = (end.elapsedNs - start.elapsedNs) / NS_PER_MS
        if (elapsedMs < MIN_AWAKE_SPAN_MS) return null
        return ((to - from).toDouble() / elapsedMs).coerceIn(0.0, 1.0)
    }

    /** Charge source quality times resolution (ADR-0017), halved when the session ended in a gap. */
    private fun quality(
        session: SessionEntity,
        end: SessionEnd,
        source: ChargeSource?,
    ): Double {
        val base =
            when (source) {
                ChargeSource.CHARGE_COUNTER -> {
                    val change = abs((end.chargeUah ?: 0) - (session.startChargeUah ?: 0)).toDouble()
                    resolution(change, COUNTER_STEP_UAH)
                }

                ChargeSource.LEVEL_DELTA, ChargeSource.INTEGRATED_CURRENT -> {
                    LEVEL_QUALITY * resolution(abs((end.levelPct ?: 0) - session.startLevel).toDouble(), 1.0)
                }

                null -> {
                    0.0
                }
            }
        return if (end.reason == SessionCloseReason.STATE_CHANGED) base else base * GAP_PENALTY
    }

    private fun resolution(
        change: Double,
        step: Double,
    ): Double = if (change <= 0) 0.0 else (1 - step / change).coerceIn(0.0, 1.0)

    /** The mean of [points] (time, value) as a step function: each holds until the next, for at most two minutes. */
    fun timeWeightedMean(points: List<Pair<Long, Long>>): Double? {
        if (points.isEmpty()) return null
        if (points.size == 1) return points.single().second.toDouble()
        var weighted = 0.0
        var total = 0L
        for ((a, b) in points.zipWithNext()) {
            val span = (b.first - a.first).coerceIn(0, MAX_SAMPLE_SPAN_NS)
            weighted += a.second * span.toDouble()
            total += span
        }
        return if (total == 0L) points.map { it.second }.average() else weighted / total
    }

    /** Minutes the samples spent where the level [test] holds; a sample counts until the next, up to [LEVEL_HOLD_NS]. */
    private fun minutesWhere(
        samples: List<BatterySampleEntity>,
        test: (BatterySampleEntity) -> Boolean,
    ): Int {
        val ns =
            samples
                .zipWithNext()
                .filter { (a, _) ->
                    test(a)
                }.sumOf { (a, b) -> (b.elapsedNs - a.elapsedNs).let { if (it in 0..LEVEL_HOLD_NS) it else 0L } }
        return (ns / NS_PER_MIN).toInt()
    }
}

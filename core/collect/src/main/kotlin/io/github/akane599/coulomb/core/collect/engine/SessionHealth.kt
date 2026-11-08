// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import io.github.akane599.coulomb.core.analytics.CapacityWindow
import io.github.akane599.coulomb.core.analytics.StressThresholds
import io.github.akane599.coulomb.core.analytics.WearInput
import io.github.akane599.coulomb.core.analytics.WearModel
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.SessionKind
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * SPEC 6.3 and 6.4 at the close of a charge or discharge session (ADR-0037): the window a capacity
 * estimate may come from, and the session's wear. Pure.
 */
internal object SessionHealth {
    /** The session kinds that carry capacity windows and wear. */
    val KINDS: Set<SessionKind> = setOf(SessionKind.CHARGE, SessionKind.DISCHARGE)

    private const val UAH_PER_MAH = 1_000.0
    private const val PERCENT = 100.0

    /**
     * The window from the session's first level change to its last, at the samples that saw them
     * (ADR-0037 §1). A level change is a sample whose level differs from the one before it, or from
     * [startLevel] for the first; only samples with a charge reading count. At both ends the true
     * level has just crossed a step, so ΔSoC is exact; the session's own ends could be up to a point
     * off each. Null with fewer than two such changes. [samples] are the session's, in time order.
     */
    fun capacityWindow(
        kind: SessionKind,
        startLevel: Int,
        samples: List<BatterySampleEntity>,
        counterReliable: Boolean,
    ): CapacityWindow? {
        if (kind !in KINDS) return null
        val changes =
            samples.indices.filter {
                samples[it].levelPct != (if (it == 0) startLevel else samples[it - 1].levelPct) && samples[it].chargeUah != null
            }
        if (changes.size < 2) return null
        val span = samples.subList(changes.first(), changes.last() + 1)
        val first = span.first()
        val last = span.last()
        return CapacityWindow(
            charging = kind == SessionKind.CHARGE,
            deltaSocPct = (last.levelPct - first.levelPct).toDouble(),
            deltaChargeUah = checkNotNull(last.chargeUah) - checkNotNull(first.chargeUah),
            minTempDeciC = span.minOf { it.tempDeciC },
            maxTempDeciC = span.maxOf { it.tempDeciC },
            meanTempDeciC =
                checkNotNull(SessionSummary.timeWeightedMean(span.map { it.elapsedNs to it.tempDeciC.toLong() })).roundToInt(),
            sameBoot = span.all { it.bootSeq == first.bootSeq },
            counterReliable = counterReliable,
        )
    }

    /**
     * [session] (closed) with its cycle equivalents and stress index (ADR-0037 §2, §3).
     * - Cycle equivalents are |ΔQ| / C_est with the charge counter's ΔQ and [capacityMah], the
     *   current estimate. Without either, |ΔSoC| / 100: a level change of the whole capacity is one
     *   cycle whatever the capacity is.
     * - The stress index is [WearModel]'s level as its ordinal (0 low, 1 moderate, 2 high). It needs
     *   the C-rate, so it stays null without an estimate or a calibrated peak current.
     */
    fun withWear(
        session: SessionEntity,
        capacityMah: Double?,
        thresholds: StressThresholds,
    ): SessionEntity {
        if (session.kind !in KINDS) return session
        val endLevel = session.endLevel ?: return session
        val counted =
            session.startChargeUah
                ?.let { start -> session.endChargeUah?.minus(start) }
                ?.takeIf { session.chargeSource == ChargeSource.CHARGE_COUNTER }
        val cycles =
            if (counted != null && capacityMah != null) {
                abs(counted / UAH_PER_MAH) / capacityMah
            } else {
                abs(endLevel - session.startLevel) / PERCENT
            }
        val stress =
            capacityMah?.let { capacity ->
                session.peakCurrentMa?.let { peak ->
                    val deltaUah = counted ?: ((endLevel - session.startLevel) / PERCENT * capacity * UAH_PER_MAH).toLong()
                    WearModel
                        .wear(WearInput(deltaUah, session.minutesAtOrAbove80 ?: 0, session.maxTempDeciC, abs(peak)), capacity, thresholds)
                        .stress.ordinal
                        .toDouble()
                }
            }
        return session.copy(cycleEquivalents = cycles, stressIndex = stress)
    }
}

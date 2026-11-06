// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.dao

import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.SampleAggregateEntity
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Builds [SampleAggregateEntity] buckets (SPEC 7.7). A bucket is `resolutionS` long, starts at a
 * multiple of it counted from the boot, and never spans a boot. Inputs are in key order.
 */
internal object SampleBuckets {
    private const val NS_PER_S = 1_000_000_000L

    fun lengthNs(resolutionS: Int): Long = resolutionS * NS_PER_S

    fun startOf(
        elapsedNs: Long,
        resolutionS: Int,
    ): Long = Math.floorDiv(elapsedNs, lengthNs(resolutionS)) * lengthNs(resolutionS)

    fun ofSamples(
        samples: List<BatterySampleEntity>,
        resolutionS: Int,
    ): List<SampleAggregateEntity> =
        samples
            .groupBy { it.bootSeq to startOf(it.elapsedNs, resolutionS) }
            .map { (key, bucket) -> sampleBucket(resolutionS, key.first, key.second, bucket) }

    /**
     * Merges finer aggregates into [resolutionS] buckets. Means are weighted by sample count; for
     * current that weight also counts samples taken before calibration, which had no current.
     */
    fun ofAggregates(
        rows: List<SampleAggregateEntity>,
        resolutionS: Int,
    ): List<SampleAggregateEntity> =
        rows
            .groupBy { it.bootSeq to startOf(it.startElapsedNs, resolutionS) }
            .map { (key, bucket) -> aggregateBucket(resolutionS, key.first, key.second, bucket) }

    private fun sampleBucket(
        resolutionS: Int,
        bootSeq: Int,
        startElapsedNs: Long,
        bucket: List<BatterySampleEntity>,
    ): SampleAggregateEntity {
        val currents = bucket.mapNotNull { it.currentUa }
        val charges = bucket.mapNotNull { it.chargeUah }
        return SampleAggregateEntity(
            resolutionS = resolutionS,
            bootSeq = bootSeq,
            startElapsedNs = startElapsedNs,
            startWallMs = bucket.first().wallMs,
            sampleCount = bucket.size,
            levelFirstPct = bucket.first().levelPct,
            levelLastPct = bucket.last().levelPct,
            chargeFirstUah = charges.firstOrNull(),
            chargeLastUah = charges.lastOrNull(),
            currentMinUa = currents.minOrNull(),
            currentMeanUa = currents.takeIf { it.isNotEmpty() }?.average()?.roundToLong(),
            currentMaxUa = currents.maxOrNull(),
            voltageMeanMv = bucket.map { it.voltageMv }.average().roundToInt(),
            tempMinDeciC = bucket.minOf { it.tempDeciC },
            tempMeanDeciC = bucket.map { it.tempDeciC }.average().roundToInt(),
            tempMaxDeciC = bucket.maxOf { it.tempDeciC },
            screenOnShare = bucket.count { it.screenOn }.toDouble() / bucket.size,
            plugType = bucket.last().plugType,
        )
    }

    private fun aggregateBucket(
        resolutionS: Int,
        bootSeq: Int,
        startElapsedNs: Long,
        bucket: List<SampleAggregateEntity>,
    ): SampleAggregateEntity {
        val count = bucket.sumOf { it.sampleCount }
        val currents = bucket.mapNotNull { row -> row.currentMeanUa?.let { it.toDouble() to row.sampleCount } }
        return SampleAggregateEntity(
            resolutionS = resolutionS,
            bootSeq = bootSeq,
            startElapsedNs = startElapsedNs,
            startWallMs = bucket.first().startWallMs,
            sampleCount = count,
            levelFirstPct = bucket.first().levelFirstPct,
            levelLastPct = bucket.last().levelLastPct,
            chargeFirstUah = bucket.firstNotNullOfOrNull { it.chargeFirstUah },
            chargeLastUah = bucket.lastOrNull { it.chargeLastUah != null }?.chargeLastUah,
            currentMinUa = bucket.mapNotNull { it.currentMinUa }.minOrNull(),
            currentMeanUa = currents.takeIf { it.isNotEmpty() }?.weightedMean()?.roundToLong(),
            currentMaxUa = bucket.mapNotNull { it.currentMaxUa }.maxOrNull(),
            voltageMeanMv = bucket.map { it.voltageMeanMv.toDouble() to it.sampleCount }.weightedMean().roundToInt(),
            tempMinDeciC = bucket.minOf { it.tempMinDeciC },
            tempMeanDeciC = bucket.map { it.tempMeanDeciC.toDouble() to it.sampleCount }.weightedMean().roundToInt(),
            tempMaxDeciC = bucket.maxOf { it.tempMaxDeciC },
            screenOnShare = bucket.map { it.screenOnShare to it.sampleCount }.weightedMean(),
            plugType = bucket.last().plugType,
        )
    }

    /** Mean of (value, weight) pairs; every stored aggregate has a weight of at least 1. */
    private fun List<Pair<Double, Int>>.weightedMean(): Double =
        sumOf { (value, weight) -> value * weight } / sumOf { it.second }.coerceAtLeast(1)
}

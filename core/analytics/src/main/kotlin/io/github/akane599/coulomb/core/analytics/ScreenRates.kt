// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A stretch in one screen state with the level at its ends: a screen session or part of one. */
public data class LevelSpan(
    val screenOn: Boolean,
    val startWallMs: Long,
    val endWallMs: Long,
    val startLevel: Int,
    val endLevel: Int,
    /** The session's quality, 0 to 1 (SPEC 6.7). */
    val quality: Double,
    /** The platform's first and last level drop inside the span, when it recorded two or more. */
    val drops: LevelDrops? = null,
)

/**
 * The first and last time the platform's history saw the level fall inside a span (ADR-0049).
 * Between them the level fell by exactly [firstLevel] − [lastLevel] whole points, with none of the
 * rounding at a span's ends, and without the hour or more a gauge can hold 100% after a full charge.
 */
public data class LevelDrops(
    val firstWallMs: Long,
    val firstLevel: Int,
    val lastWallMs: Long,
    val lastLevel: Int,
)

/** A level the platform recorded at [wallMs]. */
public data class LevelChange(
    val wallMs: Long,
    val level: Int,
)

/** A discharge session's ends: the battery ran on its own from start to end. */
public data class Discharge(
    val startWallMs: Long,
    val endWallMs: Long,
    val startLevel: Int,
    val endLevel: Int,
    val quality: Double,
)

/**
 * The %/h rates of a discharge per screen state (SPEC 10.2's session strip) and the personal
 * history that SPEC 6.8 blends with them. A rate is Derived: level change over time.
 *
 * Levels are whole percents, so a short span says little. A rate's quality is the spans' mean
 * quality, weighted by time, times its resolution `1 - 1 / drop`. A 1% drop then earns nothing,
 * and a 10% drop 0.9 of the spans' quality.
 */
public object ScreenRates {
    /** History reaches this far back. */
    public const val HISTORY_DAYS: Int = 14

    /** Same context: spans that start within this many hours of now's hour of day. */
    public const val HOUR_WINDOW: Int = 2

    private const val HOURS_PER_DAY = 24
    private const val MS_PER_HOUR = 3_600_000.0
    private const val MS_PER_DAY = 86_400_000L

    /**
     * One rate over [spans], all in one screen state: the level dropped, over the hours. A span
     * with [LevelSpan.drops] counts from its first drop to its last (ADR-0049); any other counts
     * from end to end. Null when they cover no time. A rise, such as a gauge correction, gives a
     * rate below zero; the time estimate then ignores it.
     *
     * [ContextRate.hours] stays the spans' whole length: it says how long the state was watched,
     * which readiness (SPEC 6.2's 15 minutes), the caption and the time estimate's trust weigh.
     * The quality is the spans' quality weighted by that time, times the resolution over the points
     * counted: conservative for timed spans, whose points carry no rounding.
     */
    public fun combine(spans: List<LevelSpan>): ContextRate? {
        val hours = spans.sumOf { (it.endWallMs - it.startWallMs).coerceAtLeast(0) / MS_PER_HOUR }
        val counted = spans.sumOf { countedMs(it) / MS_PER_HOUR }
        if (hours <= 0 || counted <= 0) return null
        val drop = spans.sumOf(::countedDrop)
        val quality = spans.sumOf { it.quality.coerceIn(0.0, 1.0) * (it.endWallMs - it.startWallMs).coerceAtLeast(0) / MS_PER_HOUR } / hours
        return ContextRate(pctPerHour = drop / counted, hours = hours, quality = quality * resolution(drop))
    }

    /**
     * The first and last drop in [changes], in time order, from a span that started at
     * [startLevel]. A drop is a change below the level before it. Null with fewer than two drops:
     * one drop times nothing.
     */
    public fun drops(
        startLevel: Int,
        changes: List<LevelChange>,
    ): LevelDrops? {
        var level = startLevel
        var first: LevelChange? = null
        var last: LevelChange? = null
        for (change in changes) {
            if (change.level < level) {
                if (first == null) first = change else last = change
            }
            level = change.level
        }
        return if (first != null && last != null && last.wallMs > first.wallMs) {
            LevelDrops(first.wallMs, first.level, last.wallMs, last.level)
        } else {
            null
        }
    }

    private fun timed(span: LevelSpan): LevelDrops? = span.drops?.takeIf { it.lastWallMs > it.firstWallMs }

    private fun countedMs(span: LevelSpan): Long =
        timed(span)?.let { it.lastWallMs - it.firstWallMs } ?: (span.endWallMs - span.startWallMs).coerceAtLeast(0)

    private fun countedDrop(span: LevelSpan): Int = timed(span)?.let { it.firstLevel - it.lastLevel } ?: (span.startLevel - span.endLevel)

    /**
     * SPEC 6.8's personal history for [screenOn]: per past day, the [spans] in that screen state
     * that started within [HOUR_WINDOW] hours of [nowWallMs]'s hour of day in [zone], combined.
     * Spans from the last [HISTORY_DAYS] days count; today's are the current session's, not history.
     */
    public fun history(
        spans: List<LevelSpan>,
        screenOn: Boolean,
        nowWallMs: Long,
        zone: ZoneId,
    ): List<ContextRate> {
        val now = Instant.ofEpochMilli(nowWallMs).atZone(zone)
        val today = now.toLocalDate()
        val oldest = nowWallMs - HISTORY_DAYS * MS_PER_DAY
        return spans
            .asSequence()
            .filter { it.screenOn == screenOn && it.startWallMs >= oldest && it.endWallMs > it.startWallMs }
            .map { it to Instant.ofEpochMilli(it.startWallMs).atZone(zone) }
            .filter { (_, start) -> start.toLocalDate() != today && hourDistance(start.hour, now.hour) <= HOUR_WINDOW }
            .groupBy({ (_, start) -> start.toLocalDate() }, { (span, _) -> span })
            .values
            .mapNotNull(::combine)
    }

    /**
     * The parts of a screen [span] that fall inside [discharges], so time on the charger never
     * counts. Where a part ends at a discharge's end instead of the span's, the level there is the
     * discharge's; its quality is the lower of the two. A part keeps the span's drops only when
     * both fall inside it.
     */
    public fun discharging(
        span: LevelSpan,
        discharges: List<Discharge>,
    ): List<LevelSpan> =
        discharges
            .filter { it.startWallMs < span.endWallMs && it.endWallMs > span.startWallMs }
            .map { d ->
                val startsInside = span.startWallMs >= d.startWallMs
                val endsInside = span.endWallMs <= d.endWallMs
                span.copy(
                    startWallMs = if (startsInside) span.startWallMs else d.startWallMs,
                    endWallMs = if (endsInside) span.endWallMs else d.endWallMs,
                    startLevel = if (startsInside) span.startLevel else d.startLevel,
                    endLevel = if (endsInside) span.endLevel else d.endLevel,
                    quality = min(span.quality, d.quality),
                    drops =
                        span.drops?.takeIf {
                            it.firstWallMs >= max(span.startWallMs, d.startWallMs) &&
                                it.lastWallMs <= min(span.endWallMs, d.endWallMs)
                        },
                )
            }

    private fun resolution(drop: Int): Double = if (drop <= 1) 0.0 else 1.0 - 1.0 / drop

    private fun hourDistance(
        a: Int,
        b: Int,
    ): Int = abs(a - b).let { min(it, HOURS_PER_DAY - it) }
}

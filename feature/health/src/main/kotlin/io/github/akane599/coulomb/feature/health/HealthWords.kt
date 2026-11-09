// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.akane599.coulomb.core.analytics.StressLevel
import io.github.akane599.coulomb.core.data.ChargeSessionRow
import io.github.akane599.coulomb.core.data.Charger
import io.github.akane599.coulomb.core.ui.chargerName
import io.github.akane599.coulomb.core.ui.format.Formats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Within this many days a day is named by its weekday or as days ago; further back, by its date. */
private const val WEEK = 6L

/** A charger's name, or one from its plug type while it has none (ADR-0044 §6). */
@Composable
internal fun chargerName(charger: Charger): String = chargerName(charger.name, charger.plugType)

/** When a session started: "Today, 00:30", "Yesterday, 12:40", "Wednesday, 23:10", or "Oct 21, 23:10". */
@Composable
internal fun startedAt(
    wallMs: Long,
    nowMs: Long,
    zone: ZoneId,
    formats: Formats,
): String {
    val day = dayOf(wallMs, zone)
    val ago = ChronoUnit.DAYS.between(day, dayOf(nowMs, zone))
    val clock = formats.clock(wallMs, zone)
    return when {
        // A start after now (a clock set back, a trip west) reads as today, as lastSeen does.
        ago <= 0L -> stringResource(R.string.health_today_at, clock)

        ago == 1L -> stringResource(R.string.health_yesterday_at, clock)

        ago in 2..WEEK -> stringResource(R.string.health_day_at, formats.weekday(day), clock)

        else -> stringResource(R.string.health_day_at, formats.day(day, dayOf(nowMs, zone)), clock)
    }
}

/** When a charger was last seen: "today", "yesterday", "3 days ago", or "Oct 21". */
@Composable
internal fun lastSeen(
    wallMs: Long,
    nowMs: Long,
    zone: ZoneId,
    formats: Formats,
): String {
    val day = dayOf(wallMs, zone)
    val ago = ChronoUnit.DAYS.between(day, dayOf(nowMs, zone))
    return when {
        ago <= 0L -> stringResource(R.string.health_today)
        ago == 1L -> stringResource(R.string.health_yesterday)
        ago <= WEEK -> pluralStringResource(R.plurals.health_days_ago, ago.toInt(), formats.integer(ago))
        else -> formats.day(day, dayOf(nowMs, zone))
    }
}

/** "38% to 100% in 8 h 38 min · 0.62 cycles · high stress" (lab `.detail`). */
@Composable
internal fun sessionLine(
    row: ChargeSessionRow,
    formats: Formats,
): String {
    val minutes = ((row.endWallMs - row.startWallMs).coerceAtLeast(0) / MS_PER_MINUTE.toDouble()).roundToInt().toLong()
    val parts =
        listOfNotNull(
            stringResource(
                R.string.health_session_line,
                formats.percent(row.fromPct),
                formats.percent(row.toPct),
                formats.duration(minutes),
            ),
            row.cycles?.let { cycles(it, formats) },
            row.stress?.let { stringResource(inline(it)) },
            stringResource(R.string.health_low_confidence_inline).takeIf { row.quality < LOW_QUALITY },
        )
    return parts.joinToString(SEPARATOR)
}

/** Cycle equivalents to two decimals, as SPEC 6.7 gives a session's: "0.62 cycles". */
@Composable
internal fun cycles(
    value: Double,
    formats: Formats,
): String = pluralStringResource(R.plurals.health_cycles, if (value == 1.0) 1 else 2, formats.fineAmount(value))

internal fun inline(level: StressLevel): Int =
    when (level) {
        StressLevel.LOW -> R.string.health_stress_low_inline
        StressLevel.MODERATE -> R.string.health_stress_moderate_inline
        StressLevel.HIGH -> R.string.health_stress_high_inline
    }

internal fun name(level: StressLevel): Int =
    when (level) {
        StressLevel.LOW -> R.string.health_stress_low
        StressLevel.MODERATE -> R.string.health_stress_moderate
        StressLevel.HIGH -> R.string.health_stress_high
    }

internal fun dayOf(
    wallMs: Long,
    zone: ZoneId,
): LocalDate = Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate()

private const val MS_PER_MINUTE = 60_000L
private const val SEPARATOR = "\u00A0· "

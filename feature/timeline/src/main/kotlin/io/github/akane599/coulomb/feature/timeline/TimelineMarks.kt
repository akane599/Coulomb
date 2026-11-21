// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import androidx.annotation.StringRes
import io.github.akane599.coulomb.core.data.Timeline
import io.github.akane599.coulomb.core.data.TimelineTrack
import io.github.akane599.coulomb.core.data.TrackSpan
import io.github.akane599.coulomb.core.designsystem.chart.CurrentMark
import io.github.akane599.coulomb.core.designsystem.chart.LevelMark
import io.github.akane599.coulomb.core.designsystem.chart.TimeTick
import io.github.akane599.coulomb.core.designsystem.chart.TimelineBand
import io.github.akane599.coulomb.core.designsystem.chart.TimelineSeries
import io.github.akane599.coulomb.core.designsystem.chart.TrackInk
import io.github.akane599.coulomb.core.designsystem.chart.TrackMark
import io.github.akane599.coulomb.core.designsystem.chart.TrackValue
import io.github.akane599.coulomb.core.ui.format.Formats
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A track's name beside its band (SPEC 10.6). */
@get:StringRes
internal val TimelineTrack.label: Int
    get() =
        when (this) {
            TimelineTrack.SCREEN -> R.string.timeline_track_screen
            TimelineTrack.CHARGING -> R.string.timeline_track_charging
            TimelineTrack.DOZE -> R.string.timeline_track_doze
            TimelineTrack.AWAKE -> R.string.timeline_track_awake
            TimelineTrack.TOP_APP -> R.string.timeline_track_top_app
            TimelineTrack.WAKELOCKS -> R.string.timeline_track_wakelocks
            TimelineTrack.MOBILE_RADIO -> R.string.timeline_track_mobile_radio
            TimelineTrack.WIFI -> R.string.timeline_track_wifi
            TimelineTrack.GPS -> R.string.timeline_track_gps
            TimelineTrack.SIGNAL -> R.string.timeline_track_signal
            TimelineTrack.TEMPERATURE -> R.string.timeline_track_temperature
        }

private val TimelineTrack.ink: TrackInk
    get() =
        when (this) {
            TimelineTrack.SCREEN -> TrackInk.SCREEN
            TimelineTrack.CHARGING -> TrackInk.CHARGE
            TimelineTrack.AWAKE, TimelineTrack.WAKELOCKS -> TrackInk.CPU
            TimelineTrack.MOBILE_RADIO -> TrackInk.RADIO
            TimelineTrack.WIFI -> TrackInk.WIFI
            TimelineTrack.GPS -> TrackInk.GNSS
            TimelineTrack.DOZE, TimelineTrack.TOP_APP, TimelineTrack.SIGNAL, TimelineTrack.TEMPERATURE -> TrackInk.NEUTRAL
        }

/** The radios and readings form a second group under the phone's own states, as in the design lab. */
private val SECOND_GROUP =
    setOf(TimelineTrack.MOBILE_RADIO, TimelineTrack.WIFI, TimelineTrack.GPS, TimelineTrack.SIGNAL, TimelineTrack.TEMPERATURE)

/**
 * This timeline as the chart draws it, with [tracks] in order and each named by [name]. Light Doze
 * is the lighter state of its band; the top app's stretches alternate between strong and light so
 * one app ends where the next begins; the signal is a stepped line and the temperature a line
 * between the span's coolest and warmest readings.
 */
internal fun Timeline.series(
    tracks: List<TimelineTrack>,
    name: (TimelineTrack) -> String,
): TimelineSeries {
    var grouped = false
    val bands =
        tracks.map { track ->
            val spaced = !grouped && track in SECOND_GROUP && track != tracks.first()
            if (spaced) grouped = true
            val spans = this.tracks[track].orEmpty()
            when (track) {
                TimelineTrack.SIGNAL -> TimelineBand(name(track), track.ink, line = signal(spans), stepped = true, spaced = spaced)
                TimelineTrack.TEMPERATURE -> TimelineBand(name(track), track.ink, line = temperatureLine(), spaced = spaced)
                else -> TimelineBand(name(track), track.ink, marks = marks(track, spans), spaced = spaced)
            }
        }
    return TimelineSeries(
        level = level.map { LevelMark(it.wallMs, it.levelPct.toFloat(), it.startsSegment) },
        current = current.map { CurrentMark(it.wallMs, it.currentMa) },
        bands = bands,
        // Before and after what was read nothing is known either: a window dragged past it shows no ground there.
        unknown =
            listOf(TrackMark(fromWallMs - BEYOND_MS, fromWallMs)) +
                unknown.map { TrackMark(it.fromWallMs, it.toWallMs) } +
                TrackMark(toWallMs, toWallMs + BEYOND_MS),
    )
}

/** Longer than any window and the history kept (SPEC 7.7). */
private const val BEYOND_MS = 60L * 24 * 60 * 60 * 1000

private fun marks(
    track: TimelineTrack,
    spans: List<TrackSpan>,
): List<TrackMark> {
    var light = true
    var app: Int? = null
    return spans.map { span ->
        when (track) {
            TimelineTrack.DOZE -> {
                TrackMark(span.fromWallMs, span.toWallMs, light = span.value == TrackSpan.DOZE_LIGHT)
            }

            TimelineTrack.TOP_APP -> {
                if (span.uid != app) light = !light
                app = span.uid
                TrackMark(span.fromWallMs, span.toWallMs, light)
            }

            else -> {
                TrackMark(span.fromWallMs, span.toWallMs)
            }
        }
    }
}

/** Each strength from its start to its end, as a share of the strongest. */
private fun signal(spans: List<TrackSpan>): List<TrackValue> =
    spans.flatMap { span ->
        val share = span.value.toFloat() / TrackSpan.SIGNAL_MAX
        listOf(TrackValue(span.fromWallMs, share), TrackValue(span.toWallMs, share))
    }

private fun Timeline.temperatureLine(): List<TrackValue> {
    val low = temperature.minOfOrNull { it.deciC } ?: return emptyList()
    val high = temperature.maxOf { it.deciC }
    val range = (high - low).coerceAtLeast(1)
    val line = temperature.map { TrackValue(it.wallMs, (it.deciC - low).toFloat() / range) }
    // A reading stands until the next: the line runs on to where the history ends.
    val end = knownUntilWallMs?.takeIf { it > temperature.last().wallMs } ?: return line
    return line + TrackValue(end, line.last().share)
}

/** Tick steps in minutes, shortest first. */
private val STEPS_MIN = listOf(1L, 5L, 15L, 30L, 60L, 180L, 360L, 720L, 1_440L)
private const val MAX_TICKS = 8
private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L

/**
 * The time axis for a window: at most [MAX_TICKS] ticks on round local times, the hour's own form
 * from an hour's step up ("12:00", and the weekday at midnight) and the clock below it.
 */
internal fun timeTicks(
    fromMs: Long,
    toMs: Long,
    zone: ZoneId,
    formats: Formats,
): List<TimeTick> {
    val minutes = (toMs - fromMs) / MS_PER_MINUTE
    val step = STEPS_MIN.firstOrNull { minutes / it <= MAX_TICKS } ?: STEPS_MIN.last()
    var at = Instant.ofEpochMilli(fromMs).atZone(zone).truncatedTo(ChronoUnit.DAYS)
    val ticks = ArrayList<TimeTick>()
    while (at.toInstant().toEpochMilli() <= toMs) {
        val ms = at.toInstant().toEpochMilli()
        if (ms >= fromMs) ticks += TimeTick(ms, if (step >= MINUTES_PER_HOUR) formats.hourTick(ms, zone) else formats.clock(ms, zone))
        at = at.plusMinutes(step)
    }
    return ticks
}

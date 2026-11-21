// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import io.github.akane599.coulomb.core.data.CurrentPoint
import io.github.akane599.coulomb.core.data.LevelPoint
import io.github.akane599.coulomb.core.data.TemperaturePoint
import io.github.akane599.coulomb.core.data.Timeline
import io.github.akane599.coulomb.core.data.TimelineTrack
import io.github.akane599.coulomb.core.data.TrackSpan
import kotlin.math.sin

/** The day before [NOW], as the design lab draws it; the history ends 20 minutes before now. */
internal val DAY_WINDOW = TimeWindow(NOW - DAY, NOW)
private val KNOWN_UNTIL = NOW - 20 * MINUTE

private fun at(hoursAgo: Double): Long = NOW - (hoursAgo * HOUR).toLong()

/**
 * A day of history: use through the afternoon and evening from 88% to 38%, a night on the charger
 * to 100%, unplugged in the morning. The screen is on in stretches while the phone is used, the
 * phone dozes at night, wakes every few minutes with the screen off, and the radios follow.
 */
internal fun labDay(): Timeline {
    val plugged = TrackSpan(at(9.1), at(0.4))
    val screen =
        listOf(23.5 to 22.9, 21.8 to 21.2, 20.0 to 18.6, 17.5 to 17.3, 16.0 to 14.8, 13.2 to 12.9, 11.5 to 10.2, 9.3 to 9.1, 0.4 to 0.0)
            .map { (from, to) -> TrackSpan(at(from), minOf(at(to), KNOWN_UNTIL)) }
    val doze =
        listOf(
            TrackSpan(at(8.6), at(8.2), TrackSpan.DOZE_LIGHT),
            TrackSpan(at(8.2), at(5.0), TrackSpan.DOZE_DEEP),
            TrackSpan(at(5.0), at(4.95), TrackSpan.DOZE_LIGHT),
            TrackSpan(at(4.95), at(1.6), TrackSpan.DOZE_DEEP),
            TrackSpan(at(1.6), at(0.4), TrackSpan.DOZE_LIGHT),
        )
    val (awake, locks) = wakeups(screen, doze)
    val radio =
        awake
            .filterIndexed {
                i,
                _,
                ->
                i % 2 == 0
            }.map { TrackSpan(it.fromWallMs, it.fromWallMs + maxOf(12_000, it.durationMs / 3)) }
    return Timeline(
        fromWallMs = DAY_WINDOW.fromMs,
        toWallMs = DAY_WINDOW.toMs,
        level = level(),
        current = current(screen, plugged),
        temperature =
            (0..47).map { i ->
                val wall = DAY_WINDOW.fromMs + i * 30 * MINUTE
                TemperaturePoint(wall, 300 + (60 * sin(i / 6.0)).toInt() + if (wall in plugged.fromWallMs..at(4.0)) 60 else 0)
            },
        tracks =
            mapOf(
                TimelineTrack.SCREEN to screen,
                TimelineTrack.CHARGING to listOf(plugged),
                TimelineTrack.DOZE to doze,
                TimelineTrack.AWAKE to awake,
                TimelineTrack.TOP_APP to topApps(screen),
                TimelineTrack.WAKELOCKS to locks,
                TimelineTrack.MOBILE_RADIO to radio.sortedBy { it.fromWallMs },
                TimelineTrack.WIFI to listOf(TrackSpan(at(9.5), at(0.2))),
                TimelineTrack.GPS to listOf(TrackSpan(at(19.6), at(18.9)), TrackSpan(at(15.4), at(15.3))),
                TimelineTrack.SIGNAL to signal(),
                TimelineTrack.TEMPERATURE to emptyList(),
            ),
        unknown = listOf(TrackSpan(KNOWN_UNTIL, DAY_WINDOW.toMs)),
        knownUntilWallMs = KNOWN_UNTIL,
    )
}

/** A wakeup every five minutes or so with the screen off, every fourth of them in deep Doze; half hold a wakelock. */
private fun wakeups(
    screen: List<TrackSpan>,
    doze: List<TrackSpan>,
): Pair<List<TrackSpan>, List<TrackSpan>> {
    val awake = ArrayList<TrackSpan>(screen.map { it.copy(tag = "screen") })
    val locks = ArrayList<TrackSpan>()
    var t = DAY_WINDOW.fromMs + 3 * MINUTE
    var index = 0
    while (t < KNOWN_UNTIL) {
        val used = screen.any { t >= it.fromWallMs - MINUTE && t <= it.toWallMs + MINUTE }
        val deep = doze.any { it.value == TrackSpan.DOZE_DEEP && t in it.fromWallMs..it.toWallMs }
        if (!used && (!deep || index % 4 == 0)) {
            val reason = if (index % 3 == 0) "Abort: Pending Wakeup Sources: ipa_ws" else "200 qcom,glink-smem-native-xprt-modem"
            awake += TrackSpan(t, t + 9_000, tag = reason)
            if (index % 2 == 0) locks += TrackSpan(t + 1_000, t + 8_000, uid = 10_123, tag = "*job*/app.halcyon/.sync.SyncJob")
        }
        t += 5 * MINUTE + (index % 3) * 40_000L
        index++
    }
    return awake.sortedBy { it.fromWallMs } to locks
}

/** Two apps on top in each stretch of use. */
private fun topApps(screen: List<TrackSpan>): List<TrackSpan> {
    val apps = listOf(10_123 to "app.halcyon", 10_241 to "app.wayline", 10_310 to "app.tally")
    return screen.flatMapIndexed { i, span ->
        val mid = (span.fromWallMs + span.toWallMs) / 2
        val (firstUid, firstName) = apps[i % apps.size]
        val (secondUid, secondName) = apps[(i + 1) % apps.size]
        listOf(
            TrackSpan(span.fromWallMs, mid, uid = firstUid, tag = firstName),
            TrackSpan(mid, span.toWallMs, uid = secondUid, tag = secondName),
        )
    }
}

private fun signal(): List<TrackSpan> =
    listOf(24.0 to 3, 20.5 to 4, 17.0 to 2, 15.5 to 1, 14.0 to 3, 9.0 to 4, 3.0 to 3)
        .zipWithNext { (from, value), (to, _) -> TrackSpan(at(from), at(to), value) } + TrackSpan(at(3.0), KNOWN_UNTIL, 3)

private fun level(): List<LevelPoint> {
    val falling = (0..50).map { i -> LevelPoint(at(24.0 - i * 14.9 / 50), 88 - i, startsSegment = false) }
    val charging = (1..62).map { i -> LevelPoint(at(9.1 - i * 5.0 / 62), 38 + i, startsSegment = false) }
    return falling + charging + LevelPoint(at(0.2), 99, startsSegment = false)
}

private fun current(
    screen: List<TrackSpan>,
    plugged: TrackSpan,
): List<CurrentPoint> =
    (0 until 24 * 12).mapNotNull { i ->
        val wall = DAY_WINDOW.fromMs + i * 5 * MINUTE
        val wobble = 60 * sin(i / 3.0)
        when {
            wall > KNOWN_UNTIL -> null

            wall in plugged.fromWallMs..at(
                4.1,
            )
            -> CurrentPoint(wall, 2_600.0 - (wall - plugged.fromWallMs) / HOUR.toDouble() * 480 + wobble)

            wall in plugged.fromWallMs..plugged.toWallMs -> CurrentPoint(wall, 12.0)

            screen.any { wall in it.fromWallMs..it.toWallMs } -> CurrentPoint(wall, -640.0 + wobble * 3)

            // Asleep, Coulomb takes no samples (SPEC 7.4): the area has gaps there.
            i % 6 == 0 -> CurrentPoint(wall, -46.0)

            else -> null
        }
    }

internal fun dayState(
    window: TimeWindow = DAY_WINDOW,
    cursorMs: Long? = null,
    picked: Picked? = null,
    tracks: Set<TimelineTrack> = TimelineTrack.entries.toSet(),
): TimelineUiState =
    TimelineUiState(
        window = window,
        nowMs = NOW,
        timeline = labDay(),
        tracks = tracks,
        cursorMs = cursorMs,
        picked = picked,
        latestNight = labNight(),
        labels = mapOf(10_123 to "Halcyon", 10_241 to "Wayline", 10_310 to "Tally"),
    )

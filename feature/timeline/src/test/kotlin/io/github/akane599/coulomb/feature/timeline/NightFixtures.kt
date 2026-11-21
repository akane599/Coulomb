// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import io.github.akane599.coulomb.core.data.AlarmEntry
import io.github.akane599.coulomb.core.data.AppDrain
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.data.LevelPoint
import io.github.akane599.coulomb.core.data.NightSummary
import io.github.akane599.coulomb.core.data.Timeline
import io.github.akane599.coulomb.core.data.TimelineTrack
import io.github.akane599.coulomb.core.data.TrackSpan
import io.github.akane599.coulomb.core.data.WakeCoverage
import io.github.akane599.coulomb.core.data.WakeEntry
import io.github.akane599.coulomb.core.data.WindowDrains
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage

internal const val MINUTE = 60_000L
internal const val HOUR = 60 * MINUTE
internal const val DAY = 24 * HOUR

/** Thursday 2026-11-05 09:30 UTC, the fixtures' present. */
internal const val NOW = 1_793_871_000_000L

/** Last night: the screen went off at 23:40 and came on at 07:12. */
internal const val NIGHT_START = NOW - 9 * HOUR - 50 * MINUTE
internal const val NIGHT_END = NOW - 2 * HOUR - 18 * MINUTE

/** SPEC 9.3's example night: 7 h 32 min, 6 points at 0.8 %/h, 91% deep sleep, deep Doze after 42 minutes. */
internal fun labNight(): NightSummary =
    NightSummary(
        id = 41,
        startWallMs = NIGHT_START,
        endWallMs = NIGHT_END,
        durationMs = NIGHT_END - NIGHT_START,
        startLevel = 81,
        endLevel = 75,
        ratePctPerHour = 6 / ((NIGHT_END - NIGHT_START) / HOUR.toDouble()),
        charged = false,
        deepSleepShare = 0.91,
        deepDozeReached = true,
        deepDozeAfterMs = 42 * MINUTE,
    )

/** A list with every kind of row: a rate, a drop too small for one, a night on the charger, one without Doze, one unknown. */
internal fun labNights(): List<NightSummary> =
    listOf(
        labNight(),
        night(40, NOW - 33 * HOUR, 8 * HOUR + 5 * MINUTE, 92 to 79, sleep = 0.62, doze = false),
        night(39, NOW - 2 * DAY - 3 * HOUR, 96 * MINUTE, 64 to 63, sleep = 0.97, dozeAfter = 31 * MINUTE),
        night(38, NOW - 2 * DAY - 10 * HOUR, 7 * HOUR + 48 * MINUTE, 48 to 100, sleep = 0.0, charged = true, doze = false),
        night(37, NOW - 3 * DAY - 9 * HOUR, 6 * HOUR + 15 * MINUTE, 70 to 66, sleep = null, doze = null),
        night(36, NOW - 9 * DAY - 9 * HOUR, 9 * HOUR + 2 * MINUTE, 88 to 81, sleep = 0.94, dozeAfter = 55 * MINUTE),
    )

@Suppress("LongParameterList") // One fixture row.
private fun night(
    id: Long,
    start: Long,
    duration: Long,
    levels: Pair<Int, Int>,
    sleep: Double?,
    dozeAfter: Long? = null,
    doze: Boolean? = dozeAfter != null,
    charged: Boolean = false,
): NightSummary {
    val drop = levels.first - levels.second
    return NightSummary(
        id = id,
        startWallMs = start,
        endWallMs = start + duration,
        durationMs = duration,
        startLevel = levels.first,
        endLevel = levels.second,
        ratePctPerHour = (drop / (duration / HOUR.toDouble())).takeIf { !charged && drop >= 3 },
        charged = charged,
        deepSleepShare = sleep,
        deepDozeReached = doze,
        deepDozeAfterMs = dozeAfter,
    )
}

/**
 * The lab night as history: light Doze from 20 minutes in, deep from 42 with a two-minute
 * maintenance window every hour and a half, which Android records as Doze off; a wakeup every
 * few minutes at first and every quarter of an hour once in deep Doze, one six-minute sync at
 * 03:10; the mobile radio now and then; Wi-Fi twice.
 */
internal fun labTimeline(): Timeline {
    val from = NIGHT_START
    val to = NIGHT_END
    val doze = ArrayList<TrackSpan>()
    doze += TrackSpan(from + 20 * MINUTE, from + 42 * MINUTE, TrackSpan.DOZE_LIGHT)
    var at = from + 42 * MINUTE
    while (at < to) {
        val deepEnd = minOf(at + 88 * MINUTE, to)
        doze += TrackSpan(at, deepEnd, TrackSpan.DOZE_DEEP)
        at = deepEnd + 2 * MINUTE
    }
    val awake = ArrayList<TrackSpan>()
    val locks = ArrayList<TrackSpan>()
    var wake = from
    var index = 0
    while (wake < to - MINUTE) {
        val deep = wake > from + 42 * MINUTE
        val length = if (index % 7 == 3) 110_000L else 40_000L
        awake +=
            TrackSpan(
                wake,
                wake + length,
                tag =
                    if (index % 3 ==
                        0
                    ) {
                        "Abort: Pending Wakeup Sources: ipa_ws"
                    } else {
                        "200 qcom,glink-smem-native-xprt-modem"
                    },
            )
        if (index % 2 == 0) locks += TrackSpan(wake + 2_000, wake + length - 2_000, uid = 10_123, tag = "*job*/app.halcyon/.sync.SyncJob")
        wake += if (deep) 14 * MINUTE + (index % 4) * MINUTE else 150_000L + (index % 3) * 20_000L
        index++
    }
    val sync = from + 3 * HOUR + 30 * MINUTE
    awake += TrackSpan(sync, sync + 6 * MINUTE, tag = "alarm")
    locks += TrackSpan(sync, sync + 6 * MINUTE, uid = 10_515, tag = "*job*/mail.northwind/.Fetch")
    val radio = (1..9).map { TrackSpan(from + it * 47 * MINUTE, from + it * 47 * MINUTE + 25_000) }
    return Timeline(
        fromWallMs = from,
        toWallMs = to,
        level = (0..6).map { LevelPoint(from + it * (to - from) / 6, 81 - it, startsSegment = false) },
        current = emptyList(),
        temperature = emptyList(),
        tracks =
            TimelineTrack.entries.associateWith { emptyList<TrackSpan>() } +
                mapOf(
                    TimelineTrack.DOZE to doze,
                    TimelineTrack.AWAKE to awake.sortedBy { it.fromWallMs },
                    TimelineTrack.WAKELOCKS to locks.sortedBy { it.fromWallMs },
                    TimelineTrack.MOBILE_RADIO to radio,
                    TimelineTrack.WIFI to listOf(TrackSpan(from, from + 25 * MINUTE), TrackSpan(to - 18 * MINUTE, to)),
                ),
        unknown = emptyList(),
        knownUntilWallMs = to,
    )
}

/** Who used the battery that night: 300 mAh measured, half of the modeled drain Halcyon's. */
internal fun labNightReport(): DrainReport =
    DrainReport(
        span = DrainSpan.Night(41),
        drains =
            WindowDrains(
                fromWallMs = NIGHT_START,
                toWallMs = NIGHT_END,
                measuredMah = 300.0,
                measuredProvenance = Provenance.MEASURED,
                modeledMah = 268.0,
                apps =
                    listOf(
                        AppDrain(10_123, "Halcyon", isSystem = false, iconPackage = null, mah = 151.0),
                        AppDrain(10_515, "Northwind Mail", isSystem = false, iconPackage = null, mah = 38.0),
                        AppDrain(1_000, null, isSystem = true, iconPackage = null, mah = 22.0),
                    ),
                otherMah = 9.0,
                deviceMah = 48.0,
            ),
        startLevel = 81,
        endLevel = 75,
        durationMs = NIGHT_END - NIGHT_START,
        screenOffMs = NIGHT_END - NIGHT_START,
        onBatteryMs = NIGHT_END - NIGHT_START,
        screenOffOnBatteryMs = NIGHT_END - NIGHT_START,
        measuredMs = NIGHT_END - NIGHT_START,
        lowerBound = true,
        chargedInSpan = false,
        wakes =
            listOf(
                WakeEntry(WakeKind.PARTIAL, 10_123, "Halcyon", null, "*job*/app.halcyon/.sync.SyncJob", 21 * MINUTE, 41, null, null),
                WakeEntry(WakeKind.PARTIAL, 10_515, "Northwind Mail", null, "*job*/mail.northwind/.Fetch", 6 * MINUTE, 12, null, null),
                WakeEntry(WakeKind.PARTIAL, 1_000, null, null, "*alarm*", 3 * MINUTE, 96, null, null),
                WakeEntry(WakeKind.KERNEL, null, null, null, "PowerManagerService.WakeLocks", 31 * MINUTE, 149, null, null),
                WakeEntry(WakeKind.KERNEL, null, null, null, "qcom_rx_wakelock", 9 * MINUTE, 412, null, null),
                WakeEntry(WakeKind.WAKEUP_REASON, null, null, null, "200 qcom,glink-smem-native-xprt-modem", 0, 61, null, null),
                WakeEntry(WakeKind.WAKEUP_REASON, null, null, null, "Abort: Pending Wakeup Sources: ipa_ws", 0, 28, null, null),
            ),
        wakeCoverage = WakeCoverage(Coverage.WHOLE, Coverage.NONE, Coverage.NONE),
        alarms =
            listOf(
                AlarmEntry(10_123, "Halcyon", null, 41),
                AlarmEntry(10_515, "Northwind Mail", null, 12),
                AlarmEntry(1_000, null, null, 7),
            ),
        heldMs = mapOf(10_123 to 21 * MINUTE, 10_515 to 6 * MINUTE, 1_000 to 3 * MINUTE),
    )

internal fun labDetail(): NightDetail = NightDetail(labNight(), labNightReport(), labTimeline())

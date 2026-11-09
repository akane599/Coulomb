// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import io.github.akane599.coulomb.core.data.AppBreakdown
import io.github.akane599.coulomb.core.data.AppCounters
import io.github.akane599.coulomb.core.data.AppDetailReport
import io.github.akane599.coulomb.core.data.AppDrain
import io.github.akane599.coulomb.core.data.ComponentDrain
import io.github.akane599.coulomb.core.data.DayUse
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.data.ProcessStates
import io.github.akane599.coulomb.core.data.WakeCoverage
import io.github.akane599.coulomb.core.data.WakeEntry
import io.github.akane599.coulomb.core.data.WindowDrains
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import io.github.akane599.coulomb.core.model.stats.PowerComponent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal const val MINUTE = 60_000L
internal const val HOUR = 60 * MINUTE
internal const val UID = 10_123
internal const val PACKAGE = "app.halcyon.video"

/** 14:20 UTC on the day the screen was built; the screenshots use UTC. */
internal val NOW: Long = Instant.parse("2026-11-08T14:20:00Z").toEpochMilli()
internal val UNPLUG: Long = NOW - (5 * HOUR + 12 * MINUTE)
internal val TODAY: LocalDate = LocalDate.of(2026, 11, 8)
internal val UPDATED: LocalDate = LocalDate.of(2026, 11, 3)

/** The lab's window (design-lab/app-detail.html): 1,204 mAh measured, 1,062 modeled, Halcyon 402. */
internal fun labDrains(halcyon: Double = 402.0): WindowDrains =
    WindowDrains(
        fromWallMs = UNPLUG,
        toWallMs = NOW,
        measuredMah = 1_204.0,
        measuredProvenance = Provenance.MEASURED,
        modeledMah = 1_062.0,
        apps =
            listOf(
                AppDrain(UID, "Halcyon", isSystem = false, iconPackage = PACKAGE, mah = halcyon),
                AppDrain(10_241, "Wayline", isSystem = false, iconPackage = null, mah = 168.0),
                AppDrain(1_000, null, isSystem = true, iconPackage = null, mah = 96.0),
            ),
        otherMah = 306.0,
        deviceMah = 90.0,
    )

/** The lab's wakelocks: a job, a push lock and a playback lock (design-lab/app-detail.html). */
internal fun labWakes(): List<WakeEntry> =
    listOf(
        WakeEntry(WakeKind.PARTIAL, UID, "Halcyon", PACKAGE, "*job*r/$PACKAGE/.sync.FeedPrefetchJob", 22 * MINUTE, 31, 1.0, null),
        WakeEntry(WakeKind.PARTIAL, UID, "Halcyon", PACKAGE, "HalcyonPush:NotificationWakeLock", 9 * MINUTE, 41, 0.95, null),
        WakeEntry(WakeKind.PARTIAL, UID, "Halcyon", PACKAGE, "ExoPlayer:WakeLockManager", 6 * MINUTE, 2, 0.0, null),
    )

internal fun labReport(drains: WindowDrains = labDrains()): DrainReport =
    DrainReport(
        span = DrainSpan.SinceUnplugged,
        drains = drains,
        startLevel = 100,
        endLevel = 74,
        durationMs = NOW - UNPLUG,
        screenOffMs = 3 * HOUR + 20 * MINUTE,
        onBatteryMs = NOW - UNPLUG,
        screenOffOnBatteryMs = 3 * HOUR + 20 * MINUTE,
        measuredMs = NOW - UNPLUG,
        lowerBound = false,
        chargedInSpan = false,
        wakes = labWakes(),
        wakeCoverage = WakeCoverage(Coverage.WHOLE, Coverage.NONE, Coverage.NONE),
        alarms = emptyList(),
        components = emptyList(),
    )

/** The lab's 14 days: 100 to 140 mAh until the update on 3 November, then 236 to 402. */
internal fun labTrend(gapDays: Set<Int> = emptySet()): List<DayUse> {
    val mah = listOf(112.0, 104.0, 131.0, 140.0, 118.0, 100.0, 126.0, 122.0, 236.0, 288.0, 310.0, 344.0, 297.0, 402.0)
    return mah.mapIndexed { index, value ->
        DayUse(TODAY.minusDays((mah.lastIndex - index).toLong()), value.takeUnless { index in gapDays })
    }
}

internal fun labDetail(
    drains: WindowDrains = labDrains(),
    brokenDown: Boolean = true,
    used: Boolean = true,
    trend: List<DayUse> = labTrend(),
    counters: AppCounters = labCounters(),
    label: String? = "Halcyon",
): AppDetailReport =
    AppDetailReport(
        uid = UID,
        label = label,
        iconPackage = PACKAGE,
        report = labReport(drains),
        app = if (used) drains.apps.first { it.uid == UID }.copy(label = label) else null,
        components =
            if (brokenDown && used) {
                AppBreakdown(
                    listOf(
                        ComponentDrain(PowerComponent.SCREEN, 188.0),
                        ComponentDrain(PowerComponent.MOBILE_RADIO, 121.0),
                        ComponentDrain(PowerComponent.CPU, 74.0),
                        ComponentDrain(PowerComponent.WIFI, 12.0),
                        ComponentDrain(PowerComponent.OTHER, 7.0),
                    ),
                    notBrokenDownMah = null,
                )
            } else {
                null
            },
        processStates = if (brokenDown && used) ProcessStates(236.0, 98.0, 51.0, 17.0, null) else null,
        wakes = if (used) labWakes() else emptyList(),
        counters = if (used) counters else AppCounters(),
        trend = trend,
        updatedWallMs = UPDATED.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

internal fun labCounters(): AppCounters =
    AppCounters(
        alarmWakeups = 41,
        jobCount = 31,
        jobRuntimeMs = 22 * MINUTE,
        mobileRxBytes = 298_000_000,
        mobileTxBytes = 14_000_000,
        wifiRxBytes = 45_000_000,
        wifiTxBytes = 3_000_000,
        foregroundActivityMs = 52 * MINUTE,
        foregroundServiceMs = 18 * MINUTE,
    )

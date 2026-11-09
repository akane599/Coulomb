// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import io.github.akane599.coulomb.core.data.AlarmEntry
import io.github.akane599.coulomb.core.data.AppDrain
import io.github.akane599.coulomb.core.data.ComponentApp
import io.github.akane599.coulomb.core.data.ComponentDrain
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.data.PowerComponent
import io.github.akane599.coulomb.core.data.SpanBreakdown
import io.github.akane599.coulomb.core.data.WakeCoverage
import io.github.akane599.coulomb.core.data.WakeEntry
import io.github.akane599.coulomb.core.data.WindowDrains
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import io.github.akane599.coulomb.core.model.stats.ComponentPower
import io.github.akane599.coulomb.core.model.stats.PowerColumn
import java.time.Instant

internal const val MINUTE = 60_000L
internal const val HOUR = 60 * MINUTE

/** 14:20 UTC on the day the screen was built. */
internal val NOW: Long = Instant.parse("2026-11-08T14:20:00Z").toEpochMilli()
internal val UNPLUG: Long = NOW - (5 * HOUR + 12 * MINUTE)

/** The lab's window (design-lab/drain.html): 1,204 mAh measured, 1,062 modeled, 142 unattributed. */
internal fun labDrains(): WindowDrains =
    WindowDrains(
        fromWallMs = UNPLUG,
        toWallMs = NOW,
        measuredMah = 1_204.0,
        measuredProvenance = Provenance.MEASURED,
        modeledMah = 1_062.0,
        apps =
            listOf(
                AppDrain(10_123, "Halcyon", isSystem = false, iconPackage = null, mah = 402.0),
                AppDrain(10_241, "Wayline", isSystem = false, iconPackage = null, mah = 168.0),
                AppDrain(1_000, null, isSystem = true, iconPackage = null, mah = 96.0),
                AppDrain(10_310, "Tally", isSystem = false, iconPackage = null, mah = 88.0),
                AppDrain(10_402, "Driftly", isSystem = false, iconPackage = null, mah = 74.0),
                AppDrain(10_515, "Northwind Mail", isSystem = false, iconPackage = null, mah = 41.0),
                AppDrain(10_077, "Camera", isSystem = true, iconPackage = null, mah = 36.0),
                AppDrain(10_600, "Lumen", isSystem = false, iconPackage = null, mah = 22.0),
                AppDrain(10_601, "Quire", isSystem = false, iconPackage = null, mah = 14.0),
                AppDrain(10_602, "Parcel", isSystem = false, iconPackage = null, mah = 9.0),
                AppDrain(1_027, "Nfc Service", isSystem = true, iconPackage = null, mah = 4.0),
            ),
        otherMah = 18.0,
        deviceMah = 90.0,
    )

/** S5's coarse device items: a few named sippers and the rest unnamed. */
internal fun coarseComponents(): List<ComponentDrain> =
    listOf(
        ComponentDrain(PowerComponent.SCREEN, 388.0),
        ComponentDrain(PowerComponent.OTHER, 351.0),
        ComponentDrain(PowerComponent.MOBILE_RADIO, 162.0),
        ComponentDrain(PowerComponent.IDLE, 74.0),
        ComponentDrain(PowerComponent.WIFI, 52.0),
        ComponentDrain(PowerComponent.BLUETOOTH, 21.0),
        ComponentDrain(PowerComponent.CAMERA, 14.0),
    )

/** S4's standard components (ADR-0035). */
internal fun fullComponents(): List<ComponentDrain> =
    listOf(
        ComponentDrain(PowerComponent.SCREEN, 388.0),
        ComponentDrain(PowerComponent.CPU, 246.0),
        ComponentDrain(PowerComponent.MOBILE_RADIO, 162.0),
        ComponentDrain(PowerComponent.IDLE, 74.0),
        ComponentDrain(PowerComponent.GNSS, 61.0),
        ComponentDrain(PowerComponent.WIFI, 52.0),
        ComponentDrain(PowerComponent.WAKELOCK, 31.0),
        ComponentDrain(PowerComponent.BLUETOOTH, 21.0),
        ComponentDrain(PowerComponent.CAMERA, 14.0),
        ComponentDrain(PowerComponent.SENSORS, 13.0),
    )

/**
 * S4 over most of the lab's window (ADR-0035 §7): its components with their top apps, which with
 * the 48 mAh not broken down add up to the 1,062 mAh modeled.
 */
internal fun brokenDownComponents(): List<ComponentDrain> =
    listOf(
        ComponentDrain(
            PowerComponent.SCREEN,
            340.0,
            listOf(app(10_123, "Halcyon", 188.0), app(10_241, "Wayline", 50.0), app(10_310, "Tally", 41.0)),
        ),
        ComponentDrain(
            PowerComponent.CPU,
            246.0,
            listOf(app(10_123, "Halcyon", 74.0), app(1_000, null, 60.0), app(10_402, "Driftly", 31.0)),
        ),
        ComponentDrain(PowerComponent.MOBILE_RADIO, 162.0, listOf(app(10_123, "Halcyon", 121.0), app(10_515, "Northwind Mail", 18.0))),
        ComponentDrain(PowerComponent.IDLE, 74.0),
        ComponentDrain(PowerComponent.GNSS, 61.0, listOf(app(10_241, "Wayline", 61.0))),
        ComponentDrain(PowerComponent.WIFI, 52.0, listOf(app(10_123, "Halcyon", 12.0), app(10_515, "Northwind Mail", 9.0))),
        ComponentDrain(PowerComponent.WAKELOCK, 31.0, listOf(app(1_000, null, 30.0))),
        ComponentDrain(PowerComponent.BLUETOOTH, 21.0, listOf(app(10_402, "Driftly", 21.0))),
        ComponentDrain(PowerComponent.CAMERA, 14.0, listOf(app(10_077, "Camera", 14.0))),
        ComponentDrain(PowerComponent.SENSORS, 13.0, listOf(app(10_241, "Wayline", 6.0))),
    )

private fun app(
    uid: Int,
    label: String?,
    mah: Double,
) = ComponentApp(uid, label, mah)

/** The lab's chips (design-lab/drain.html): Halcyon whole, Wayline and Android System with a remainder. */
internal fun labBreakdown(): SpanBreakdown =
    SpanBreakdown(
        mapOf(
            10_123 to
                power(
                    PowerColumn.SCREEN to 188,
                    PowerColumn.MOBILE_RADIO to 121,
                    PowerColumn.CPU to 74,
                    PowerColumn.WIFI to 12,
                    PowerColumn.OTHER to 7,
                ),
            10_241 to power(PowerColumn.GNSS to 61, PowerColumn.SCREEN to 50, PowerColumn.CPU to 20, PowerColumn.SENSORS to 6),
            1_000 to power(PowerColumn.CPU to 60, PowerColumn.WAKELOCK to 30),
        ),
    )

/** Whole mAh as S4's dC. */
private fun power(vararg mah: Pair<PowerColumn, Int>): ComponentPower =
    ComponentPower.of(mah.associate { (column, value) -> column to (value * ComponentPower.DC_PER_MAH).toLong() })

/** The lab's report with S4's breakdown (ADR-0035 §7). */
internal fun brokenDownReport(breakdownOff: Boolean = false): DrainReport =
    report(components = brokenDownComponents())
        .copy(componentsNotBrokenDownMah = 48.0, breakdown = labBreakdown(), breakdownOff = breakdownOff)

/** Batterystats counts partial and kernel wakelocks only with the screen off: they have no share (ADR-0036 §34). */
internal fun wakes(): List<WakeEntry> =
    listOf(
        WakeEntry(WakeKind.PARTIAL, 10_123, "Halcyon", null, "*job*/app.halcyon/.sync.SyncJob", 52 * MINUTE, 38, null, null),
        WakeEntry(WakeKind.PARTIAL, 1_000, null, null, "*alarm*", 31 * MINUTE, 412, null, null),
        WakeEntry(WakeKind.PARTIAL, 10_402, "Driftly", null, "AudioMix", 65 * MINUTE, 3, null, null),
        WakeEntry(WakeKind.PARTIAL, 10_515, "Northwind Mail", null, "*job*/mail.northwind/.Fetch", 4 * MINUTE, 38, null, null),
        WakeEntry(WakeKind.PARTIAL, 10_241, "Wayline", null, "LocationUpdates", 24 * MINUTE, 6, null, null),
        WakeEntry(WakeKind.PARTIAL, 10_310, "Tally", null, "WorkManager", 40_000, 9, null, null),
        WakeEntry(WakeKind.KERNEL, null, null, null, "qcom_rx_wakelock", 41 * MINUTE, 2_210, null, null),
        WakeEntry(WakeKind.KERNEL, null, null, null, "PowerManagerService.WakeLocks", 2 * HOUR, 905, null, null),
        WakeEntry(WakeKind.WAKEUP_SOURCE, null, null, null, "qcom_rx_wakelock", 41 * MINUTE, 2_210, null, 812),
        WakeEntry(WakeKind.WAKEUP_REASON, null, null, null, "Abort: Pending Wakeup Sources: ipa_ws", 3 * MINUTE, 140, 0.85, null),
        WakeEntry(WakeKind.RESUME_CAUSE, null, null, null, "200 qcom,glink-smem-native-xprt-modem", 0, 1_120, null, null),
    )

internal fun alarms(): List<AlarmEntry> =
    listOf(
        AlarmEntry(10_515, "Northwind Mail", null, 38),
        AlarmEntry(1_000, null, null, 21),
        AlarmEntry(10_123, "Halcyon", null, 1),
    )

/** Shell mode over a whole window: S5's records, no root kernel sources (ADR-0028). */
internal val SHELL_COVERAGE = WakeCoverage(Coverage.WHOLE, Coverage.NONE, Coverage.NONE)

internal fun report(
    span: DrainSpan = DrainSpan.SinceUnplugged,
    drains: WindowDrains = labDrains(),
    components: List<ComponentDrain> = coarseComponents(),
    wakeCoverage: WakeCoverage = SHELL_COVERAGE,
) = DrainReport(
    span = span,
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
    wakes = wakes(),
    wakeCoverage = wakeCoverage,
    alarms = alarms(),
    components = components,
)

internal fun ready(
    report: DrainReport? = report(),
    chosen: DrainSpan = report?.span ?: DrainSpan.SinceUnplugged,
    includeSystem: Boolean = false,
    scaled: Boolean = false,
) = DrainUiState.Ready(chosen, report, current = true, includeSystem = includeSystem, scaled = scaled)

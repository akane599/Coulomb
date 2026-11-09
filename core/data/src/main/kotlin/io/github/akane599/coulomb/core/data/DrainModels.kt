// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.stats.ComponentPower
import io.github.akane599.coulomb.core.model.stats.PowerColumn
import java.time.LocalDate

/** A window Drain can show (SPEC 10.3, ADR-0023 §4). Every span ends at the latest heavy snapshot, or at its own end. */
public sealed interface DrainSpan {
    /** From the unplug; plugged in, the last discharge session. */
    public data object SinceUnplugged : DrainSpan

    /** From the day's first heavy snapshot (ADR-0018 day windows). */
    public data object Today : DrainSpan

    /** The 24 hours before the latest heavy snapshot, from the first heavy snapshot in them. */
    public data object Last24Hours : DrainSpan

    /** The longest screen-off session of an hour or more that ended in the last 24 hours (ADR-0036). */
    public data object LastNight : DrainSpan

    /** Whole local days from [first] to [last], within the 30 days consecutive windows are kept (ADR-0021). */
    public data class Days(
        val first: LocalDate,
        val last: LocalDate,
    ) : DrainSpan {
        init {
            require(!last.isBefore(first)) { "a range ends on or after its first day" }
        }
    }

    public companion object {
        /** The presets of the window selector, in order (ADR-0023 §4). */
        public val PRESETS: List<DrainSpan> = listOf(SinceUnplugged, Today, Last24Hours)

        /** How far back a custom range may start: consecutive windows are kept 30 days (ADR-0021). */
        public const val CUSTOM_DAYS: Long = 30
    }
}

/** Who and what used the battery in a [DrainSpan] (SPEC 10.3). */
public data class DrainReport(
    val span: DrainSpan,
    val drains: WindowDrains,
    val startLevel: Int?,
    val endLevel: Int?,
    /** The span's realtime: its screen-on plus screen-off time. */
    val durationMs: Long,
    val screenOffMs: Long,
    /**
     * Of [durationMs], the time on battery by batterystats' own clock, summed from the windows the
     * apps and records come from. Batterystats counts power and most wake time only then (ADR-0036
     * §34, §35).
     */
    val onBatteryMs: Long,
    /** Of [onBatteryMs], the time with the screen off, by the same clock. */
    val screenOffOnBatteryMs: Long,
    /** The time of the windows the measured total sums; 0 without one. */
    val measuredMs: Long,
    /**
     * The apps were summed from consecutive windows, each of which folds its small UIDs into the
     * remainder, so an app's total is a lower bound (ADR-0032 §4).
     */
    val lowerBound: Boolean,
    /** Some of the span was spent charging: the measured total counts only the discharge (ADR-0036). */
    val chargedInSpan: Boolean,
    /** Every wakelock, kernel source, wakeup reason and resume cause, longest first. */
    val wakes: List<WakeEntry>,
    /** Which sources of [wakes] and [alarms] the span collected; a missing source is never zero. */
    val wakeCoverage: WakeCoverage,
    /** Wakeup alarms per app, most first (S5 reports wakeup alarms only). */
    val alarms: List<AlarmEntry>,
    /**
     * The device's power by component, apps included, largest first. Modeled. From S4 when its
     * windows hold at least half the span's modeled total ([componentsNotBrokenDownMah] is then set),
     * else S5's coarse device rows (ADR-0035 §7).
     */
    val components: List<ComponentDrain> = emptyList(),
    /** With S4's [components]: the modeled energy of the windows S4 did not break down. */
    val componentsNotBrokenDownMah: Double? = null,
    /** S4's per-app breakdown; null when no window of the span was broken down. */
    val breakdown: SpanBreakdown? = null,
    /** Some windows of the span were not broken down because Component breakdown was off (ADR-0035 §2). */
    val breakdownOff: Boolean = false,
)

/** One power component's share of a span: the device's total for it, apps included. Modeled. */
public data class ComponentDrain(
    val component: PowerComponent,
    val mah: Double,
    /** With S4: the apps that used the most of it, most first (SPEC 10.3). */
    val topApps: List<ComponentApp> = emptyList(),
)

/** One app's use of a component. A null [label] has no name yet. */
public data class ComponentApp(
    val uid: Int,
    val label: String?,
    val mah: Double,
)

/**
 * S4's breakdown of a span (ADR-0035 §7): each app's components summed over the span's
 * broken-down windows. Modeled.
 */
public data class SpanBreakdown(
    /** By UID, with [WindowUidEntity.OTHER_UID] for the windows' small apps; S4's columns in dC. */
    val apps: Map<Int, ComponentPower>,
) {
    /**
     * [uid]'s components, largest first, custom included as S5's app totals include it, and what
     * is left of its [totalMah] over the span; null when S4 broke down none of its use.
     */
    public fun of(
        uid: Int,
        totalMah: Double,
    ): AppBreakdown? {
        val power = apps[uid] ?: return null
        val parts =
            PowerColumn.components
                .filter { power[it] > 0 }
                .map { ComponentDrain(PowerComponent.of(it), power.mah(it)) }
                .sortedByDescending { it.mah }
        if (parts.isEmpty()) return null
        // Decision 5 keeps the remainder from going below zero by more than its margin; below zero shows as none.
        val rest = totalMah - parts.sumOf { it.mah }
        return AppBreakdown(parts, rest.takeIf { it >= MIN_REST_MAH })
    }

    private companion object {
        /** A remainder under this is rounding, not a part of the app's use. */
        const val MIN_REST_MAH = 0.05
    }
}

/** One app's components in a span, and the use S4 did not break down (ADR-0035 §7). */
public data class AppBreakdown(
    val parts: List<ComponentDrain>,
    val notBrokenDownMah: Double?,
)

/**
 * A power component as Drain names it (SPEC 6.5). S5 names the device's items by the old sipper
 * kinds and leaves the rest unnamed ([OTHER]); S4 names every standard component (ADR-0035).
 */
public enum class PowerComponent {
    SCREEN,
    CPU,
    MOBILE_RADIO,
    WIFI,
    BLUETOOTH,
    GNSS,
    SENSORS,
    CAMERA,
    AUDIO,
    VIDEO,
    FLASHLIGHT,
    WAKELOCK,
    MEMORY,
    PHONE,
    AMBIENT_DISPLAY,
    IDLE,
    SYSTEM_SERVICES,
    CUSTOM,
    OTHER,
    ;

    public companion object {
        /** S4's column as Drain names it; the process-state columns are not components. */
        public fun of(column: PowerColumn): PowerComponent {
            require(!column.isProcessState) { "$column is a process state" }
            // Every component column has the component's name.
            return valueOf(column.name)
        }

        /**
         * The component a stored row names: S5's sipper kinds (`SipperKind`, upper case) or the
         * platform's component names (`BatteryConsumer.sPowerComponentNames`, lower case).
         */
        public fun ofStored(name: String): PowerComponent =
            when (name) {
                "SCREEN", "screen" -> SCREEN
                "cpu" -> CPU
                "CELL", "mobile_radio" -> MOBILE_RADIO
                "WIFI", "wifi" -> WIFI
                "BLUETOOTH", "bluetooth" -> BLUETOOTH
                "gnss" -> GNSS
                "sensors" -> SENSORS
                "CAMERA", "camera" -> CAMERA
                "audio" -> AUDIO
                "video" -> VIDEO
                "FLASHLIGHT", "flashlight" -> FLASHLIGHT
                "wakelock" -> WAKELOCK
                "MEMORY", "memory" -> MEMORY
                "PHONE", "phone" -> PHONE
                "AMBIENT_DISPLAY", "ambient_display" -> AMBIENT_DISPLAY
                "IDLE", "idle" -> IDLE
                "system_services" -> SYSTEM_SERVICES
                "custom" -> CUSTOM
                else -> OTHER
            }
    }
}

/**
 * The time a wake record's counter runs (ADR-0036 §34), so the time a record's figures are out of.
 * Batterystats counts partial wakelocks, kernel wakelocks and wakeup alarms only on battery with the
 * screen off, and wakeup reasons whenever on battery. The kernel's own counters (S9, S27) run all
 * the time.
 */
public enum class WakeBasis {
    SCREEN_OFF_ON_BATTERY,
    ON_BATTERY,
    WHOLE_SPAN,
    ;

    public companion object {
        /**
         * S5's wakeup alarms: `Uid.Pkg.noteWakeupAlarmLocked` counts on `mOnBatteryScreenOffTimeBase`
         * (BatteryStatsImpl.java android-16.0.0_r4 L10126, android-17.0.0_r1 L9690).
         */
        public val ALARMS: WakeBasis = SCREEN_OFF_ON_BATTERY
    }
}

/**
 * When this kind's counter runs. BatteryStatsImpl.java: a partial wakelock's timer and each kernel
 * wakelock's run on `mOnBatteryScreenOffTimeBase` (android-16.0.0_r4 L10643-10645, L4499-4504;
 * android-17.0.0_r1 L10207-10209, L4050-4055), a wakeup reason's on `mOnBatteryTimeBase` (16:
 * L4486-4491; 17: L4037-4042). `updateTimeBasesLocked` runs the first only while unplugged and
 * `!Display.isOnState` (17: L4093-4131), the same test that runs the screen-on timer (17: L5247-5249).
 */
public val WakeKind.basis: WakeBasis
    get() =
        when (this) {
            WakeKind.PARTIAL, WakeKind.KERNEL -> WakeBasis.SCREEN_OFF_ON_BATTERY
            WakeKind.WAKEUP_REASON -> WakeBasis.ON_BATTERY
            WakeKind.WAKEUP_SOURCE, WakeKind.RESUME_CAUSE, WakeKind.SUSPEND_ABORT -> WakeBasis.WHOLE_SPAN
        }

/** The time of [basis] in this span. */
public fun DrainReport.basisMs(basis: WakeBasis): Long =
    when (basis) {
        WakeBasis.SCREEN_OFF_ON_BATTERY -> screenOffOnBatteryMs
        WakeBasis.ON_BATTERY -> onBatteryMs
        WakeBasis.WHOLE_SPAN -> durationMs
    }

/** One wakelock, kernel wakelock, wakeup reason or root kernel record in a span (SPEC 10.3, Wakeups lens). */
public data class WakeEntry(
    val kind: WakeKind,
    /** The holder of a partial wakelock; null for kernel records. */
    val uid: Int?,
    val label: String?,
    val iconPackage: String?,
    val tag: String,
    /** Measured. */
    val totalMs: Long,
    /** Measured. */
    val count: Long,
    /**
     * The share of its time with the screen off, 0 to 1, over the windows that were wholly screen-on
     * or wholly screen-off. Only for kinds counted on battery ([WakeBasis.ON_BATTERY]): a kind counted
     * only with the screen off is always whole, and a root kind also counts on the charger, where
     * there is no screen time to split by. Null when no window qualified. Derived.
     */
    val screenOffShare: Double?,
    /** S9 sources only: how many times it woke the phone. */
    val wakeups: Long?,
)

/** One app's wakeup alarms in a span, or the small apps' together ([SMALL_APPS_UID]). Measured. */
public data class AlarmEntry(
    val uid: Int,
    val label: String?,
    val iconPackage: String?,
    val wakeups: Long,
) {
    /** The UIDs no window kept rows for, together (ADR-0015). */
    val smallApps: Boolean get() = uid == SMALL_APPS_UID

    public companion object {
        public const val SMALL_APPS_UID: Int = WindowUidEntity.OTHER_UID
    }
}

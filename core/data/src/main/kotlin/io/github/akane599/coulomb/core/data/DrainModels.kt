// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.model.WakeKind
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
     * The apps were summed from consecutive windows, each of which folds its small UIDs into the
     * remainder, so an app's total is a lower bound (ADR-0032 §4).
     */
    val lowerBound: Boolean,
    /** Some of the span was spent charging: the measured total counts only the discharge (ADR-0036). */
    val chargedInSpan: Boolean,
    /** Every wakelock, kernel source, wakeup reason and resume cause, longest first. */
    val wakes: List<WakeEntry>,
    /** Wakeup alarms per app, most first (S5 reports wakeup alarms only). */
    val alarms: List<AlarmEntry>,
)

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
     * or wholly screen-off; null when none was. Derived.
     */
    val screenOffShare: Double?,
    /** S9 sources only: how many times it woke the phone. */
    val wakeups: Long?,
)

/** One app's wakeup alarms in a span. Measured. */
public data class AlarmEntry(
    val uid: Int,
    val label: String?,
    val iconPackage: String?,
    val wakeups: Long,
)

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.analytics.TimeLeft
import io.github.akane599.coulomb.core.model.Provenance

/** The latest battery sample (SPEC 7.3). */
public data class BatteryNow(
    /** The fuel gauge's level: Modeled (SPEC 6.7). */
    val levelPct: Int,
    /** Calibrated current, positive while charging; null before calibration (SPEC 6.1). Measured. */
    val currentMa: Double?,
    val plugged: Boolean,
    /** `BatteryManager.BATTERY_STATUS_*`. */
    val status: Int,
    val tempDeciC: Int,
    val voltageMv: Int,
    val screenOn: Boolean,
    val wallMs: Long,
)

/** One sample's read of the battery (SPEC 10.2). */
public data class BatteryReading(
    val battery: BatteryNow,
    /** The open discharge session, or null before one opens. */
    val discharge: DischargeSummary?,
    /** The level since that session began, oldest first; empty without one. */
    val levels: List<LevelPoint>,
    /** The last [BatteryRepository.LIVE_WINDOW_MS] of calibrated current, oldest first. */
    val live: List<CurrentPoint>,
)

/** One level reading since the unplug (SPEC 10.2's scale); Modeled, as the level is. */
public data class LevelPoint(
    val wallMs: Long,
    val levelPct: Int,
    /** The phone rebooted before this reading: the line breaks here. */
    val startsSegment: Boolean,
)

/** One point of the live current chart (SPEC 10.2). */
public data class CurrentPoint(
    val wallMs: Long,
    /** Positive while charging. */
    val currentMa: Double,
)

/**
 * A screen state's share of the current discharge (SPEC 10.2): level-based %/h over its hours,
 * which SPEC 6.2 shows until the capacity estimate exists. Derived.
 */
public data class StateRate(
    val pctPerHour: Double,
    val hours: Double,
    /** 0 to 1; below 0.5 the figure carries the low-confidence mark (SPEC 6.7). */
    val quality: Double,
) {
    /**
     * SPEC 6.2 shows a rate only after 15 minutes or 20 mAh; until then the strip says
     * "Collecting". Only the time counts here: the level rate has no charge change to test.
     */
    val ready: Boolean get() = hours >= MIN_HOURS

    val provenance: Provenance get() = Provenance.DERIVED

    public companion object {
        public const val MIN_HOURS: Double = 0.25
    }
}

/**
 * The share of screen-off time the CPU slept in the current discharge (SPEC 10.2's strip): one minus
 * the screen-off sessions' awake share, weighted by their time in the discharge. Measured.
 */
public data class DeepSleepShare(
    /** 0 to 1. */
    val share: Double,
    /** The screen-off hours [share] is read from. */
    val hours: Double,
    /**
     * 0 to 1: the time-weighted completeness of those sessions (a half for one cut short by a reboot
     * or a gap), times the window's length up to an hour. Below 0.5 the figure carries the
     * low-confidence mark (SPEC 6.7).
     */
    val quality: Double,
) {
    /** As [StateRate.ready]: a share of a few minutes of screen-off time says little. */
    val ready: Boolean get() = hours >= StateRate.MIN_HOURS
}

/** The open discharge session: SPEC 10.2's hero, verdict and session strip. */
public data class DischargeSummary(
    val startWallMs: Long,
    val startLevel: Int,
    val levelPct: Int,
    val screenOn: StateRate?,
    val screenOff: StateRate?,
    /** Null until a screen-off session with an awake share overlaps the discharge. */
    val deepSleep: DeepSleepShare?,
    /** SPEC 6.8, for the screen state the phone is in now; null when there is nothing to estimate from. */
    val timeLeft: TimeLeft?,
)

/** One UID's drain in a window. A null [label] has no name yet; the UI names it from its UID. */
public data class AppDrain(
    val uid: Int,
    val label: String?,
    val isSystem: Boolean,
    /** The package whose icon stands for the UID ([AppIcons]). */
    val iconPackage: String?,
    /** Modeled (SPEC 6.5). */
    val mah: Double,
)

/**
 * Who used the battery in a window (SPEC 6.5). Shares divide by [total]: the measured drain, or the
 * modeled total where that is larger, so the apps, [otherMah], [deviceMah] and [unattributedMah]
 * add up to the whole.
 */
public data class WindowDrains(
    val fromWallMs: Long,
    val toWallMs: Long,
    val measuredMah: Double?,
    /** Measured from the charge counter, Estimated from the level change (ADR-0018). */
    val measuredProvenance: Provenance?,
    /** The power model's device total, apps and the rest; Modeled (SPEC 6.5). */
    val modeledMah: Double,
    /** Ranked, largest first; the folded remainder is [otherMah]. */
    val apps: List<AppDrain>,
    /** The UIDs not stored one by one (ADR-0015). */
    val otherMah: Double,
    /**
     * What the model charged to no app: phone idle, mobile network standby and the like (SPEC
     * 6.5's friendly system rows). Modeled.
     */
    val deviceMah: Double = 0.0,
) {
    val total: Double get() = maxOf(measuredMah ?: 0.0, modeledMah)

    /** Measured minus modeled, where measured is larger (SPEC 6.5 reconciliation); Derived. */
    val unattributedMah: Double get() = ((measuredMah ?: 0.0) - modeledMah).coerceAtLeast(0.0)

    /** [mah]'s share of [total], 0 to 1; Derived. */
    public fun share(mah: Double): Double = if (total > 0) mah / total else 0.0
}

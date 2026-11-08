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

/** One point of the live current chart (SPEC 10.2). */
public data class CurrentPoint(
    val wallMs: Long,
    /** Positive while charging. */
    val currentMa: Double,
)

/** A screen state's share of the current discharge: Derived %/h over its hours (SPEC 10.2). */
public data class StateRate(
    val pctPerHour: Double,
    val hours: Double,
)

/** The open discharge session: SPEC 10.2's hero, verdict and session strip. */
public data class DischargeSummary(
    val startWallMs: Long,
    val startLevel: Int,
    val levelPct: Int,
    val screenOn: StateRate?,
    val screenOff: StateRate?,
    /** Share of screen-off time the CPU slept, 0 to 1; Measured. Null before a screen-off session closes. */
    val deepSleepShare: Double?,
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
 * modeled sum where that is larger, so the apps and the unattributed rest add up to the whole.
 */
public data class WindowDrains(
    val fromWallMs: Long,
    val toWallMs: Long,
    val measuredMah: Double?,
    /** Measured from the charge counter, Estimated from the level change (ADR-0018). */
    val measuredProvenance: Provenance?,
    val modeledMah: Double,
    /** Ranked, largest first; the folded remainder is [otherMah]. */
    val apps: List<AppDrain>,
    /** The UIDs not stored one by one (ADR-0015). */
    val otherMah: Double,
) {
    val total: Double get() = maxOf(measuredMah ?: 0.0, modeledMah)

    /** [mah]'s share of [total], 0 to 1; Derived. */
    public fun share(mah: Double): Double = if (total > 0) mah / total else 0.0
}

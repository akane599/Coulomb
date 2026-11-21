// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.model.stats.ComponentPower
import io.github.akane599.coulomb.core.model.stats.PowerColumn
import java.time.LocalDate

/**
 * One app over a window (SPEC 10.4, ADR-0041). [report] is Drain's report for the same span, so the
 * header's figure and share are those of the Drain row; the rest is this app's.
 */
public data class AppDetailReport(
    val uid: Int,
    /** The app's name, from its row or the last one seen; null while it has none (SPEC 6.5). */
    val label: String?,
    /** The package whose icon stands for the UID, and whose App info opens ([AppIcons]). */
    val iconPackage: String?,
    val report: DrainReport,
    /** The app's row in [report]; null when it recorded no use in the window. */
    val app: AppDrain?,
    /** Its components (S4); null without S4's breakdown of any of its use. */
    val components: AppBreakdown?,
    /** Its process states (S4); null as [components]. */
    val processStates: ProcessStates?,
    /** Its wake records, longest first, from [report]'s, with [report]'s coverage. */
    val wakes: List<WakeEntry>,
    val counters: AppCounters,
    /**
     * The longest single hold of each of the app's partial wakelocks, by tag, as Android's
     * statistics had it at the span's end: since those statistics began, on battery, so a hold may
     * lie before the span. Empty when that snapshot's wakelock rows are no longer kept. Measured.
     */
    val longestHolds: Map<String, Long> = emptyMap(),
    /** The last 14 local days, oldest first, today last. */
    val trend: List<DayUse>,
    /** The app's last update, from the platform, when it falls in [trend]'s days (ADR-0041 §5). */
    val updatedWallMs: Long? = null,
)

/**
 * An app's S4 energy by process state over the broken-down windows, and the rest of its total
 * ([notSplitMah], shown from 0.05 mAh). Modeled.
 */
public data class ProcessStates(
    val foregroundMah: Double,
    val backgroundMah: Double,
    val foregroundServiceMah: Double,
    val cachedMah: Double,
    val notSplitMah: Double?,
) {
    public companion object {
        /** A remainder under this is rounding (ADR-0035 §7). */
        internal const val MIN_REST_MAH: Double = 0.05

        /** Null when S4 split none of the app's use by state. */
        public fun of(
            power: ComponentPower?,
            totalMah: Double,
        ): ProcessStates? {
            if (power == null || PowerColumn.processStates.none { power[it] > 0 }) return null
            val states =
                ProcessStates(
                    foregroundMah = power.mah(PowerColumn.FOREGROUND),
                    backgroundMah = power.mah(PowerColumn.BACKGROUND),
                    foregroundServiceMah = power.mah(PowerColumn.FOREGROUND_SERVICE),
                    cachedMah = power.mah(PowerColumn.CACHED),
                    notSplitMah = null,
                )
            val rest = totalMah - states.splitMah
            return states.copy(notSplitMah = rest.takeIf { it >= MIN_REST_MAH })
        }
    }

    public val splitMah: Double get() = foregroundMah + backgroundMah + foregroundServiceMah + cachedMah
}

/**
 * An app's counters over the window (ADR-0041 §1). Null means not recorded. The collector stores
 * no zeros, so the reader fills them in as 0 for an app with a row whose records were all read.
 */
public data class AppCounters(
    val alarmWakeups: Long? = null,
    val jobCount: Long? = null,
    val jobRuntimeMs: Long? = null,
    val mobileRxBytes: Long? = null,
    val mobileTxBytes: Long? = null,
    val wifiRxBytes: Long? = null,
    val wifiTxBytes: Long? = null,
    val foregroundActivityMs: Long? = null,
    val foregroundServiceMs: Long? = null,
    /**
     * The time the app held partial wakelocks, with overlaps between its tags removed (S5's `awl`
     * line). The per-tag rows overlap, so their sum is not this (ADR-0039 §4).
     */
    val partialWakelockMs: Long? = null,
    /** Processor time in the app's own code. Measured. */
    val cpuUserMs: Long? = null,
    /** Processor time the kernel spent on the app's calls. Measured. */
    val cpuSystemMs: Long? = null,
    /**
     * The app's share of the time the mobile radio was active. Android splits that time among the
     * apps by their packets (BatteryStatsImpl, android-16.0.0_r4 L12250-12266, android-17.0.0_r1
     * L11840-11859), so it is Modeled, not a time the app held the radio.
     */
    val radioActiveMs: Long? = null,
    /** Time its syncs ran. Measured. */
    val syncMs: Long? = null,
    /** Jobs Android stopped as abandoned (Android 16 and later); null where S5 does not report it. */
    val jobsAbandoned: Long? = null,
) {
    /** Both halves of the processor time, when either was recorded. */
    val cpuMs: Long? get() = if (cpuUserMs == null && cpuSystemMs == null) null else (cpuUserMs ?: 0) + (cpuSystemMs ?: 0)

    /** Every counter, a missing one as 0: for an app whose records were all read. */
    internal fun zeroFilled(): AppCounters =
        AppCounters(
            alarmWakeups = alarmWakeups.orZero(),
            jobCount = jobCount.orZero(),
            jobRuntimeMs = jobRuntimeMs.orZero(),
            mobileRxBytes = mobileRxBytes.orZero(),
            mobileTxBytes = mobileTxBytes.orZero(),
            wifiRxBytes = wifiRxBytes.orZero(),
            wifiTxBytes = wifiTxBytes.orZero(),
            foregroundActivityMs = foregroundActivityMs.orZero(),
            foregroundServiceMs = foregroundServiceMs.orZero(),
            partialWakelockMs = partialWakelockMs.orZero(),
            cpuUserMs = cpuUserMs.orZero(),
            cpuSystemMs = cpuSystemMs.orZero(),
            radioActiveMs = radioActiveMs.orZero(),
            syncMs = syncMs.orZero(),
            // Not every build reports abandoned stops, so a missing count stays unknown.
            jobsAbandoned = jobsAbandoned,
        )

    private fun Long?.orZero(): Long = this ?: 0
}

/**
 * The app's mAh on one local day (ADR-0041 §4): summed over the consecutive windows that end that
 * day, or null when no window does. Modeled; a lower bound by under 0.1 mAh a window where the app
 * was folded into small apps.
 */
public data class DayUse(
    val day: LocalDate,
    val mah: Double?,
)

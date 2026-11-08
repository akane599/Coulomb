// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.akane599.coulomb.core.data.AlarmEntry
import io.github.akane599.coulomb.core.data.AppDrain
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.data.PowerComponent
import io.github.akane599.coulomb.core.data.WakeBasis
import io.github.akane599.coulomb.core.data.WakeEntry
import io.github.akane599.coulomb.core.data.basis
import io.github.akane599.coulomb.core.data.basisMs
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import kotlin.math.abs
import kotlin.math.roundToLong

/** What a compared figure counts, per hour of the time its counter ran (ADR-0038 §3, §4). */
internal enum class RateUnit { MAH, MINUTES, COUNT }

/** One of the two periods: its days, and the report read for them; null when they have no two heavy snapshots. */
@Immutable
internal data class Period(
    val days: DrainSpan.Days,
    val report: DrainReport?,
) {
    /**
     * The measured drain per hour of the windows it sums; null without one, without a drain to
     * show, or below SPEC 6.2's floor.
     */
    val drainPerHour: Double?
        get() {
            val report = report ?: return null
            val mah = report.drains.measuredMah ?: return null
            val ms = report.measuredMs
            if (ms <= 0 || mah <= 0 || tooShort) return null
            return mah / (ms / MS_PER_HOUR)
        }

    /** The drain was estimated from the level, not read from the counter (ADR-0018). */
    val estimated: Boolean get() = report?.drains?.estimated == true

    /**
     * An Estimated [drainPerHour] as the range it stands for, low to high per hour (ADR-0036 §17);
     * null when it is Measured or how far it can be off is unknown.
     */
    val drainRangePerHour: Pair<Double, Double>?
        get() {
            val drains = report?.drains ?: return null
            if (drainPerHour == null || !drains.estimated || drains.levelEstimate == null) return null
            val hours = report.measuredMs / MS_PER_HOUR
            val low = drains.measuredLowMah ?: return null
            val high = drains.measuredHighMah ?: return null
            return low / hours to high / hours
        }

    /** The counter was read and showed no charge used. */
    val noDrain: Boolean get() = !tooShort && (report?.drains?.measuredMah ?: 1.0) <= 0

    /** S5 was read at both ends of some window, so the period has app and component figures. */
    val statsCollected: Boolean get() = report != null && report.wakeCoverage.batteryStats != Coverage.NONE

    /** Some wake source covered part of the period. */
    val wakesCollected: Boolean
        get() =
            report?.wakeCoverage?.let { listOf(it.batteryStats, it.wakeupSources, it.kernelLog).any { c -> c != Coverage.NONE } } == true

    /** The screen-on part of the time on battery. */
    val screenOnMs: Long get() = report?.let { (it.onBatteryMs - it.screenOffOnBatteryMs).coerceAtLeast(0) } ?: 0

    /** Enough time on battery for the apps' and components' rates (SPEC 6.2). */
    val ratesCounted: Boolean get() = report != null && report.onBatteryMs >= MIN_RATE_MS

    /** A measured drain over too little time, and too small, for a rate (SPEC 6.2: 15 minutes or 20 mAh). */
    val tooShort: Boolean
        get() {
            val mah = report?.drains?.measuredMah ?: return false
            return report.measuredMs < MIN_RATE_MS && mah < MIN_RATE_MAH
        }
}

/** What a compared row stands for. */
internal sealed interface CompareSubject {
    data class App(
        val app: AppDrain,
    ) : CompareSubject

    /** The power the model charged to no app (SPEC 6.5). */
    data object Device : CompareSubject

    /** The UIDs no window stored one by one (ADR-0015). */
    data object SmallApps : CompareSubject

    /** The system apps the switch leaves out, together, as Drain folds them (ADR-0036 §10). */
    data object HiddenSystem : CompareSubject

    data class Component(
        val component: PowerComponent,
    ) : CompareSubject

    data class Wake(
        val entry: WakeEntry,
    ) : CompareSubject

    data class Alarm(
        val entry: AlarmEntry,
    ) : CompareSubject
}

/**
 * One record in both periods, per hour of the time its counter ran. A figure is null where its
 * period has none to give: not read, its source not collected, or too little time counted. Zero
 * means the period counted and the record was not there.
 */
@Immutable
internal data class ComparedRow(
    val key: String,
    val subject: CompareSubject,
    val first: Double?,
    val second: Double?,
) {
    /** The second period's rate less the first's; null unless both are known. */
    val change: Double? get() = if (first != null && second != null) second - first else null
}

/** One Wakeups group in both periods (ADR-0038 §5). */
@Immutable
internal data class ComparedWakeGroup(
    val key: String,
    @param:StringRes val title: Int,
    @param:StringRes val definition: Int?,
    val unit: RateUnit,
    /** When the group's counter runs; null when the two periods read the group from different sources. */
    val basis: WakeBasis?,
    /** Each period's time of [basis]; null for a period with no report. */
    val firstBasisMs: Long?,
    val secondBasisMs: Long?,
    /** Each period's coverage of the group's source; null for a period with no report. */
    val firstCoverage: Coverage?,
    val secondCoverage: Coverage?,
    /** Ranked by change; empty when [basis] is null. */
    val rows: List<ComparedRow>,
) {
    /** The rate that fills a bar, found once rather than for each row. */
    val scale: Double = barScale(rows)

    /** The two periods read the group from the same source, so its rows compare. */
    val comparable: Boolean get() = basis != null

    /** The first period has figures for the group: its source covered some of it, long enough for a rate. */
    val firstCounted: Boolean get() = counted(firstCoverage, firstBasisMs)

    val secondCounted: Boolean get() = counted(secondCoverage, secondBasisMs)

    private fun counted(
        coverage: Coverage?,
        basisMs: Long?,
    ): Boolean = covered(basis, coverage) && basisMs != null && basisMs >= MIN_RATE_MS
}

/**
 * A source's records give rates: it covered the period, and, for a root source, the whole period.
 * Batterystats' bases come from the windows that read S5's device section, so its partial
 * coverage divides by the time it covered; a root source's base is the whole period, which it may
 * have covered only in part (ADR-0038 §5).
 */
private fun covered(
    basis: WakeBasis?,
    coverage: Coverage?,
): Boolean =
    when (coverage) {
        null, Coverage.NONE -> false
        Coverage.PARTIAL -> basis != null && basis != WakeBasis.WHOLE_SPAN
        Coverage.WHOLE -> basis != null
    }

/** Compare two periods (ADR-0038): the periods, then each lens's rows ranked by the size of their change. */
@Immutable
internal data class Comparison(
    val first: Period,
    val second: Period,
    val apps: List<ComparedRow>,
    /** System apps the switch leaves out, in either period. */
    val hiddenSystem: Int,
    val components: List<ComparedRow>,
    val wakes: List<ComparedWakeGroup>,
) {
    /** Each list's rate that fills a bar, found once rather than for each row. */
    val appsScale: Double = barScale(apps)
    val componentsScale: Double = barScale(components)

    /** Either period summed its apps over shorter windows, so their figures are lower bounds (ADR-0032 §4). */
    val lowerBound: Boolean get() = first.report?.lowerBound == true || second.report?.lowerBound == true

    /** Both periods ran long enough on battery for the apps' and components' rates. */
    val ratesCounted: Boolean get() = first.ratesCounted && second.ratesCounted

    /** Both periods have a drain rate, and at least one was estimated, so the change is not shown (ADR-0038 §6). */
    val changeEstimated: Boolean
        get() = first.drainPerHour != null && second.drainPerHour != null && (first.estimated || second.estimated)

    /** The second period's drain rate less the first's; null unless both were measured by the counter. */
    val drainDifference: Double?
        get() {
            if (changeEstimated) return null
            val before = first.drainPerHour ?: return null
            val after = second.drainPerHour ?: return null
            return after - before
        }

    /** The second period's drain rate over the first's, less one; null unless both were measured by the counter. */
    val drainChange: Double?
        get() {
            val difference = drainDifference ?: return null
            val before = first.drainPerHour ?: return null
            return if (before > 0) difference / before else null
        }
}

/** The list's largest rate fills a bar (ADR-0038 §8). */
private fun barScale(rows: List<ComparedRow>): Double = rows.maxOfOrNull { maxOf(it.first ?: 0.0, it.second ?: 0.0) } ?: 0.0

/** Builds a [Comparison]. Pure. */
internal fun compare(
    first: Period,
    second: Period,
    includeSystem: Boolean,
): Comparison {
    val a = first.report
    val b = second.report
    val hidden =
        listOfNotNull(a, b)
            .flatMap { report ->
                report.drains.apps
                    .filter { it.isSystem }
                    .map { it.uid }
            }.toSet()
    // Each period's groups once; every group reads its own view.
    val viewsA = a?.let(::wakeGroups)
    val viewsB = b?.let(::wakeGroups)
    return Comparison(
        first = first,
        second = second,
        apps = rows(appFigures(a, includeSystem), appFigures(b, includeSystem)),
        hiddenSystem = if (includeSystem) 0 else hidden.size,
        components = rows(componentFigures(a), componentFigures(b)),
        wakes =
            WakeGroup.entries.map { group ->
                wakeGroup(group, a, viewsA?.first { it.group == group }, b, viewsB?.first { it.group == group })
            } + alarmGroup(a, b),
    )
}

/** SPEC 6.2: a rate needs a window of 15 minutes, or 20 mAh. */
internal const val MIN_RATE_MS: Long = 15 * 60_000L
internal const val MIN_RATE_MAH: Double = 20.0
internal const val MS_PER_HOUR: Double = 3_600_000.0
private const val MS_PER_MINUTE = 60_000.0

/** A period's records by key, each as its subject and its amount in the period. */
private class Figures(
    /** The time the amounts were counted over. */
    val basisMs: Long,
    val amounts: Map<String, Pair<CompareSubject, Double>>,
) {
    /** Per hour of [basisMs]; null when too little time was counted to give a rate. */
    fun perHour(key: String): Double? = if (basisMs < MIN_RATE_MS) null else (amounts[key]?.second ?: 0.0) / (basisMs / MS_PER_HOUR)
}

/**
 * Apps per hour on battery, with the phone's own row and the small apps. System apps follow the
 * switch; left out, they share one row.
 */
private fun appFigures(
    report: DrainReport?,
    includeSystem: Boolean,
): Figures? {
    report ?: return null
    val drains = report.drains
    val (system, others) = drains.apps.partition { it.isSystem }
    val amounts =
        buildMap {
            (if (includeSystem) drains.apps else others).forEach { put("app-${it.uid}", CompareSubject.App(it) to it.mah) }
            if (!includeSystem) put(HIDDEN_KEY, CompareSubject.HiddenSystem to system.sumOf { it.mah })
            put(DEVICE_KEY, CompareSubject.Device to drains.deviceMah)
            put(SMALL_KEY, CompareSubject.SmallApps to drains.otherMah)
        }
    return Figures(report.onBatteryMs, amounts)
}

/** Components per hour on battery: the device's power for each, apps included. */
private fun componentFigures(report: DrainReport?): Figures? {
    report ?: return null
    return Figures(
        report.onBatteryMs,
        report.components.associate {
            "component-${it.component}" to
                (CompareSubject.Component(it.component) to it.mah)
        },
    )
}

/**
 * The rows of two periods' [Figures]: every record either period has, ranked by the size of its
 * change, then by its larger rate. A record at zero in both is left out.
 */
private fun rows(
    first: Figures?,
    second: Figures?,
): List<ComparedRow> {
    val subjects = linkedMapOf<String, CompareSubject>()
    listOfNotNull(first, second).forEach { figures -> figures.amounts.forEach { (key, value) -> subjects.putIfAbsent(key, value.first) } }
    return subjects
        .map { (key, subject) -> ComparedRow(key, subject, first?.perHour(key), second?.perHour(key)) }
        .filter { (it.first ?: 0.0) > 0 || (it.second ?: 0.0) > 0 }
        .sortedWith(BY_CHANGE)
}

/**
 * The largest change first, to the tenth the rows show, so changes too small to show sort as none
 * would, by the larger rate.
 */
private val BY_CHANGE: Comparator<ComparedRow> =
    compareByDescending<ComparedRow> { row -> row.change?.let { (abs(it) * TENTHS).roundToLong() } ?: -1L }
        .thenByDescending { maxOf(it.first ?: 0.0, it.second ?: 0.0) }

/**
 * One Wakeups group in both periods. Each period shows the source [wakeGroups] picks for it; when
 * both have a source that covered something and they differ, the rows do not compare. A period
 * whose source covered nothing has no figures, never zeros (ADR-0028 §3).
 */
private fun wakeGroup(
    group: WakeGroup,
    a: DrainReport?,
    viewA: WakeGroupView?,
    b: DrainReport?,
    viewB: WakeGroupView?,
): ComparedWakeGroup {
    val basis = sharedKind(viewA, viewB)?.basis
    val timed = listOfNotNull(viewA, viewB).any { view -> view.entries.any { it.totalMs > 0 } }
    val unit = if (timed) RateUnit.MINUTES else RateUnit.COUNT
    return ComparedWakeGroup(
        key = group.name,
        title = group.title,
        definition = group.definition,
        unit = unit,
        basis = basis,
        firstBasisMs = basis?.let { a?.basisMs(it) },
        secondBasisMs = basis?.let { b?.basisMs(it) },
        firstCoverage = viewA?.coverage,
        secondCoverage = viewB?.coverage,
        rows = if (basis == null) emptyList() else rows(wakeFigures(a, viewA, basis, timed), wakeFigures(b, viewB, basis, timed)),
    )
}

/**
 * The record kind both periods read a group from: the one source that covered something, else the
 * kind either period shows. Null when the two periods' sources differ.
 */
private fun sharedKind(
    a: WakeGroupView?,
    b: WakeGroupView?,
): WakeKind? {
    val views = listOfNotNull(a, b)
    val kinds = views.filter { it.coverage != Coverage.NONE }.map { it.kind }.toSet()
    return if (kinds.size > 1) null else kinds.singleOrNull() ?: views.firstOrNull()?.kind
}

/** A period's figures for one wake group: minutes held or counts, over the group's [basis]. */
private fun wakeFigures(
    report: DrainReport?,
    view: WakeGroupView?,
    basis: WakeBasis,
    timed: Boolean,
): Figures? {
    if (report == null || view == null || !covered(basis, view.coverage)) return null
    val amounts =
        view.entries.associate { entry ->
            val amount = if (timed) entry.totalMs / MS_PER_MINUTE else entry.count.toDouble()
            "wake-${entry.kind}-${entry.uid}-${entry.tag}" to (CompareSubject.Wake(entry) to amount)
        }
    return Figures(report.basisMs(basis), amounts)
}

/** S5's wakeup alarms per app, per hour with the screen off on battery. */
private fun alarmGroup(
    a: DrainReport?,
    b: DrainReport?,
): ComparedWakeGroup {
    val basis = WakeBasis.ALARMS

    fun figures(report: DrainReport?): Figures? {
        if (report == null || report.wakeCoverage.batteryStats == Coverage.NONE) return null
        return Figures(
            report.basisMs(basis),
            report.alarms.associate { "alarm-${it.uid}" to (CompareSubject.Alarm(it) to it.wakeups.toDouble()) },
        )
    }
    return ComparedWakeGroup(
        key = "ALARMS",
        title = R.string.drain_wake_alarms,
        definition = null,
        unit = RateUnit.COUNT,
        basis = basis,
        firstBasisMs = a?.basisMs(basis),
        secondBasisMs = b?.basisMs(basis),
        firstCoverage = a?.wakeCoverage?.batteryStats,
        secondCoverage = b?.wakeCoverage?.batteryStats,
        rows = rows(figures(a), figures(b)),
    )
}

private const val DEVICE_KEY = "device"
private const val SMALL_KEY = "small"
private const val HIDDEN_KEY = "hidden"
private const val TENTHS = 10.0

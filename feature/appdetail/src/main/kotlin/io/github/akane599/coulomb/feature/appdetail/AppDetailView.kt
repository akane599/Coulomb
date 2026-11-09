// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import androidx.compose.runtime.Immutable
import io.github.akane599.coulomb.core.data.AppDetailReport
import io.github.akane599.coulomb.core.data.DayUse
import io.github.akane599.coulomb.core.data.WakeEntry
import io.github.akane599.coulomb.core.model.Provenance
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * What App detail draws from one report, built once per report and setting (SPEC 16.4).
 *
 * The header's figure is the Drain row's (ADR-0041 §1): modeled, or scaled to the measured total
 * and Derived while Drain scales its apps.
 */
@Immutable
internal class AppDetailView(
    val detail: AppDetailReport,
    /** 1, or measured over modeled while the apps are scaled. */
    val factor: Double,
    /** What the share divides by: the measured total, or the modeled one (ADR-0036 §17). */
    val denominator: Double,
    val provenance: Provenance,
    /** The app's wakelocks other than jobs, longest first. */
    val wakelocks: List<WakeEntry>,
    /** Its jobs, from their wakelocks, longest first. */
    val jobs: List<JobRow>,
) {
    val mah: Double? get() = detail.app?.mah?.let { it * factor }

    val share: Double? get() = mah?.takeIf { denominator > 0 }?.let { it / denominator }

    val scaled: Boolean get() = factor != 1.0

    companion object {
        fun of(
            detail: AppDetailReport,
            scaled: Boolean,
        ): AppDetailView {
            val drains = detail.report.drains
            val measured = drains.measuredMah?.takeIf { it > 0 && !drains.estimated }
            val scaleTo = measured?.takeIf { scaled && drains.modeledMah > 0 }
            val (jobs, wakelocks) = detail.wakes.partition { JOB_TAG.containsMatchIn(it.tag) }
            return AppDetailView(
                detail = detail,
                factor = scaleTo?.let { it / drains.modeledMah } ?: 1.0,
                denominator = scaleTo ?: drains.total,
                provenance = if (scaleTo != null) Provenance.DERIVED else Provenance.MODELED,
                wakelocks = wakelocks.sortedByDescending { it.totalMs },
                jobs =
                    jobs
                        .map { JobRow(jobName(it.tag, detail.iconPackage), it.count, it.totalMs, it.screenOffShare) }
                        .sortedByDescending { it.totalMs },
            )
        }

        /**
         * A job's wakelock tag: `*job*`, a type letter (e, u or r; always on Android 17, behind a
         * flag on 16), a slash, then the job's battery name (JobStatus.getWakelockTag,
         * android-16.0.0_r4 JobStatus.java 1501-1513, android-17.0.0_r1 1733-1745).
         */
        val JOB_TAG = Regex("""^\*job\*[eur]?/""")

        /**
         * The battery name's optional `#trace tag#` and `@namespace@` before the service
         * (JobStatus constructor, android-16.0.0_r4 691-706, android-17.0.0_r1 821-834).
         */
        private val JOB_DECORATION = Regex("""^(#[^#]*#)?(@[^@]*@)?""")

        /**
         * A job's name as the lab shows it: the service class of the battery name's component
         * (`ComponentName.flattenToShortString`), with the app's own package shortened to a
         * leading dot (".sync.FeedPrefetchJob"); a job named by a source tag keeps that name.
         */
        fun jobName(
            tag: String,
            packageName: String?,
        ): String {
            val battery = JOB_DECORATION.replaceFirst(JOB_TAG.replaceFirst(tag, ""), "")
            val service = if ('/' in battery) battery.substringAfter('/') else battery
            return if (packageName != null && service.startsWith("$packageName.")) service.removePrefix(packageName) else service
        }
    }
}

/** One job of the app: its runs and time, measured, from its wakelock. */
@Immutable
internal data class JobRow(
    val name: String,
    val runs: Long,
    val totalMs: Long,
    val screenOffShare: Double?,
)

/**
 * The trend's scale (DESIGN.md, Charts): gridlines at quarter intervals of a round top, so the
 * labels read as round numbers, and the days drawn under it.
 */
@Immutable
internal class TrendScale(
    val days: List<DayUse>,
    /** The top gridline's value; at least the largest day. */
    val top: Double,
    /** The update's day, when it falls in [days]. */
    val updated: LocalDate?,
) {
    /** The gridlines above the axis, bottom up. */
    val lines: List<Double> get() = (1..QUARTERS).map { top * it / QUARTERS }

    val gaps: Int get() = days.count { it.mah == null }

    companion object {
        const val QUARTERS = 4

        fun of(
            days: List<DayUse>,
            updated: LocalDate?,
        ): TrendScale {
            val largest = days.maxOfOrNull { it.mah ?: 0.0 } ?: 0.0
            return TrendScale(days, niceTop(largest), updated?.takeIf { day -> days.any { it.day == day } })
        }

        /**
         * The smallest top whose quarters are a round step times a power of ten, at least 1 mAh a
         * quarter; the steps are fine enough that the largest day fills most of the plot.
         */
        fun niceTop(largest: Double): Double {
            val quarter = (largest / QUARTERS).coerceAtLeast(1.0)
            val magnitude = 10.0.pow(floor(log10(quarter)))
            return STEPS.first { it * magnitude >= quarter - EPSILON } * magnitude * QUARTERS
        }

        private val STEPS = listOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)
        private const val EPSILON = 1e-9
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.analytics.CapacityEstimate
import io.github.akane599.coulomb.core.data.Cycles
import io.github.akane599.coulomb.core.data.HealthReport
import io.github.akane599.coulomb.core.designsystem.chart.AxisLabel
import io.github.akane599.coulomb.core.designsystem.chart.IntervalDay
import io.github.akane599.coulomb.core.designsystem.chart.IntervalScale
import io.github.akane599.coulomb.core.designsystem.chart.IntervalTrendChart
import io.github.akane599.coulomb.core.designsystem.chart.TrendCross
import io.github.akane599.coulomb.core.designsystem.chart.TrendLabels
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigure
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigureStrip
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.BatteryDate
import io.github.akane599.coulomb.core.model.BatteryDateSource
import io.github.akane599.coulomb.core.model.BatteryDates
import io.github.akane599.coulomb.core.model.DesignCapacity
import io.github.akane599.coulomb.core.model.DesignCapacitySource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.FigureText
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.affixed
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.page.InlineTable
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import io.github.akane599.coulomb.core.ui.page.StandIn
import io.github.akane599.coulomb.core.ui.page.TableRow
import io.github.akane599.coulomb.core.ui.page.ViewTableButton
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import io.github.akane599.coulomb.core.ui.R as UiR

/**
 * The estimate (SPEC 10.8 item 1, lab `.health-hero`): the capacity with its ± and the Estimated
 * mark, health % of the design capacity, the interval on its scale, where the figures come from,
 * and the change since the first estimate. Before the first estimate, what it needs.
 */
@Composable
internal fun Estimate(
    report: HealthReport,
    formats: Formats,
    onDesign: () -> Unit,
    onViewTable: () -> Unit,
) {
    val capacity = report.capacity
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8)) {
        if (capacity == null) {
            NoEstimate(report.design, formats, onDesign)
            return@Column
        }
        Hero(capacity, report.design, formats)
        CapacityScale(capacity, report.design, formats, onViewTable, Modifier.padding(top = CoulombSpacing.Space24))
        Note(
            pluralStringResource(R.plurals.health_estimated_from, capacity.windows, formats.integer(capacity.windows.toLong())) +
                report.design?.let { " " + stringResource(designSource(it.source)) }.orEmpty(),
        )
        report.change?.let { change ->
            val size = abs(change.deltaMah)
            val day = formats.day(change.firstDay)
            val text =
                when {
                    size < SAME_MAH -> {
                        stringResource(R.string.health_change_same, day)
                    }

                    else -> {
                        val amount = formats.mah(size).joined()
                        val range = formats.mah(change.firstHalfWidthMah).joined()
                        val within = size <= change.firstHalfWidthMah
                        val lower = change.deltaMah < 0
                        val id =
                            when {
                                lower && within -> R.string.health_change_lower_within
                                lower -> R.string.health_change_lower
                                within -> R.string.health_change_higher_within
                                else -> R.string.health_change_higher
                            }
                        stringResource(id, amount, day, range)
                    }
                }
            Text(
                text,
                Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16),
                color = CoulombTheme.colors.textPrimary,
                style = CoulombTheme.typography.body,
            )
        }
        DesignButton(report.design, onDesign)
    }
}

/** A change under 10 mAh rounds away in a figure rounded to 10 mAh (SPEC 6.3). */
private const val SAME_MAH = 10.0

@Composable
private fun Hero(
    capacity: CapacityEstimate,
    design: DesignCapacity?,
    formats: Formats,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val half = (capacity.ciHighMah - capacity.ciLowMah) / 2
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row {
            FigureText(
                formats.mah(capacity.estimateMah).affixed(),
                numberStyle = type.display,
                unitStyle = type.displayUnit,
                modifier = Modifier.alignByBaseline(),
                rolling = true,
            )
            Text(
                stringResource(R.string.health_plus_minus, formats.integer(half.roundToLong())),
                Modifier.alignByBaseline().padding(start = CoulombSpacing.Space8),
                color = colors.textSecondary,
                style = type.displayUnit,
            )
            Box(Modifier.alignBy { it.measuredHeight }.padding(start = CoulombSpacing.Space4)) { ProvenanceMark(Provenance.ESTIMATED) }
        }
        val health = capacity.healthPct
        Text(
            if (health != null && design != null) {
                stringResource(R.string.health_of_design, formats.percent(health.roundToInt()), formats.mah(design.mah).joined())
            } else {
                stringResource(R.string.health_design_unknown)
            },
            Modifier.fillMaxWidth(),
            color = colors.textSecondary,
            style = type.body,
        )
    }
}

/**
 * The interval on a graduated scale (DESIGN.md's signature). With a design capacity the scale
 * runs in tens of percent of it, from 80% or lower to 100% or higher, each mark with its mAh and
 * percent; without one it runs in round mAh around the interval.
 */
@Composable
private fun CapacityScale(
    capacity: CapacityEstimate,
    design: DesignCapacity?,
    formats: Formats,
    onViewTable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val low = capacity.ciLowMah
    val high = capacity.ciHighMah
    val scale = Scale.of(low, high, design?.mah)
    val labels =
        scale.marks.map { value ->
            AxisLabel(value, formats.integer(value.roundToLong()), design?.let { formats.percent((value / it.mah * PERCENT).roundToInt()) })
        }
    // Left to right like the scale under it, in either layout direction.
    val range = LTR_ISOLATE + formats.range(formats.integer(low.roundToLong()), formats.integer(high.roundToLong())) + POP_ISOLATE
    val estimate = formats.mah(capacity.estimateMah).joined()
    val lowText = formats.mah(low).joined()
    val highText = formats.mah(high).joined()
    val health = capacity.healthPct
    val summary =
        if (design != null && health != null) {
            stringResource(
                R.string.health_scale_summary_design,
                estimate,
                lowText,
                highText,
                formats.percent(health.roundToInt()),
                formats.mah(design.mah).joined(),
            )
        } else {
            stringResource(R.string.health_scale_summary, estimate, lowText, highText)
        }
    IntervalScale(
        from = scale.from,
        to = scale.to,
        low = low,
        high = high,
        point = capacity.estimateMah,
        rangeLabel = range,
        endLabel = stringResource(R.string.health_scale_design).takeIf { design != null && scale.to == design.mah },
        labels = labels,
        minorStep = scale.minor,
        summary = summary,
        viewTableLabel = stringResource(UiR.string.page_view_table),
        onViewTable = onViewTable,
        modifier = modifier,
    )
}

/** The scale's ends, its labelled marks and its minor step. */
internal class Scale(
    val from: Double,
    val to: Double,
    val marks: List<Double>,
    val minor: Double,
) {
    companion object {
        private const val LOW_PCT = 80.0
        private const val HIGH_PCT = 100.0
        private const val MARK_PCT = 10.0
        private const val MINOR_PCT = 2.0
        private val STEPS = listOf(50.0, 100.0, 200.0, 250.0, 500.0, 1_000.0)
        private const val MAX_MARKS = 4

        fun of(
            low: Double,
            high: Double,
            design: Double?,
        ): Scale {
            if (design != null && design > 0) {
                val fromPct = floor(minOf(LOW_PCT, low / design * PERCENT) / MARK_PCT) * MARK_PCT
                val toPct = ceil(maxOf(HIGH_PCT, high / design * PERCENT) / MARK_PCT) * MARK_PCT
                val marks = generateSequence(fromPct) { it + MARK_PCT }.takeWhile { it <= toPct + TOLERANCE }.map { design * it / PERCENT }
                return Scale(design * fromPct / PERCENT, design * toPct / PERCENT, marks.toList(), design * MINOR_PCT / PERCENT)
            }
            // Without a design capacity: the interval with as much again on each side, in round steps.
            val pad = maxOf(high - low, STEPS.first())
            val step = STEPS.firstOrNull { (high - low + pad * 2) / it <= MAX_MARKS } ?: STEPS.last()
            val from = floor((low - pad) / step).coerceAtLeast(0.0) * step
            val to = ceil((high + pad) / step) * step
            val marks = generateSequence(from) { it + step }.takeWhile { it <= to + TOLERANCE }.toList()
            return Scale(from, to, marks, step / MINOR_PER_STEP)
        }

        private const val TOLERANCE = 1e-6
        private const val MINOR_PER_STEP = 5
    }
}

private const val PERCENT = 100.0
private const val LTR_ISOLATE = "\u2066"
private const val POP_ISOLATE = "\u2069"

/** SPEC 14: the charge that gives the first estimate, from this level or lower to [HIGH_END] or higher. */
private const val LOW_START = 30
private const val HIGH_END = 80

/** Before the first estimate: what it needs, and the design capacity where one is known. */
@Composable
private fun NoEstimate(
    design: DesignCapacity?,
    formats: Formats,
    onDesign: () -> Unit,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.health_no_estimate_title),
            Modifier.fillMaxWidth().semantics { heading() },
            color = colors.textPrimary,
            style = type.title,
        )
        Text(
            stringResource(R.string.health_no_estimate, formats.percent(LOW_START), formats.percent(HIGH_END)),
            Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8),
            color = colors.textSecondary,
            style = type.body,
        )
        design?.let {
            Note(stringResource(R.string.health_design_known, formats.mah(it.mah).joined()) + " " + stringResource(designSource(it.source)))
        }
        DesignButton(design, onDesign)
    }
}

@Composable
private fun DesignButton(
    design: DesignCapacity?,
    onDesign: () -> Unit,
) {
    CoulombTextButton(
        text = stringResource(if (design == null) R.string.health_design_set else R.string.health_design_change),
        onClick = onDesign,
        modifier = Modifier.padding(top = CoulombSpacing.Space8),
        edge = ButtonEdge.Start,
    )
}

private fun designSource(source: DesignCapacitySource): Int =
    when (source) {
        DesignCapacitySource.POWER_PROFILE -> R.string.health_design_from_power_profile
        DesignCapacitySource.SYSFS -> R.string.health_design_from_sysfs
        DesignCapacitySource.USER -> R.string.health_design_from_user
    }

/** SPEC 6.3: points apart beyond which the values are shown as differing, with why. */
private const val AGREE_POINTS = 5

/**
 * The cross-check (SPEC 6.3, 10.8 item 1): Coulomb's health % beside Android's and each the OEM
 * prints, each with its source and mark, then whether they agree. Never one picked silently.
 */
@Composable
internal fun CrossCheck(
    report: HealthReport,
    formats: Formats,
) {
    val coulomb = report.capacity?.healthPct?.roundToInt()
    val windows = report.capacity?.windows ?: 0
    val rows =
        buildList {
            coulomb?.let {
                add(
                    Value(
                        stringResource(R.string.health_source_coulomb),
                        pluralStringResource(R.plurals.health_source_coulomb_how, windows, formats.integer(windows.toLong())),
                        it,
                        Provenance.ESTIMATED,
                    ),
                )
            }
            report.crossCheck.systemSohPct?.let {
                add(
                    Value(
                        stringResource(R.string.health_source_android),
                        stringResource(R.string.health_source_android_how),
                        it,
                        Provenance.MODELED,
                    ),
                )
            }
            report.crossCheck.oemHealth.forEach {
                add(Value(stringResource(R.string.health_source_oem), it.key, it.percent, Provenance.MODELED, mono = true))
            }
        }
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.health_cross_check), provenance = null)
        if (rows.isEmpty()) {
            StandIn(stringResource(R.string.health_no_values), top = 0.dp)
            return@Column
        }
        InlineTable(
            columns = listOf(stringResource(R.string.health_column_source), stringResource(R.string.health_column_health)),
            rows = rows.map { TableRow(it.name, listOf(formats.percent(it.percent)), note = it.how, mark = it.mark, mono = false) },
        )
        val spread = rows.maxOf { it.percent } - rows.minOf { it.percent }
        val note =
            when {
                rows.size == 1 && coulomb != null -> {
                    stringResource(R.string.health_only_coulomb)
                }

                rows.size == 1 -> {
                    stringResource(R.string.health_coulomb_needs_design).takeIf { report.capacity != null }
                }

                spread <= AGREE_POINTS -> {
                    stringResource(
                        R.string.health_agree,
                        formats.integer(rows.size.toLong()),
                        formats.integer(spread.toLong()),
                    )
                }

                else -> {
                    stringResource(R.string.health_differ, formats.integer(rows.size.toLong()), formats.integer(spread.toLong()))
                }
            }
        note?.let { Note(it) }
    }
}

private class Value(
    val name: String,
    val how: String,
    val percent: Int,
    val mark: Provenance,
    val mono: Boolean = false,
)

/**
 * Cycles and age (SPEC 10.8 items 2 and 3, lab `.figures`): the platform's count, Coulomb's sum
 * with the platform's over the same days, and when the battery was first used and made.
 */
@Composable
internal fun CyclesAge(
    cycles: Cycles,
    dates: BatteryDates,
    formats: Formats,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.health_cycles_age), provenance = null)
        val counted = cycles.platformCount != null || cycles.coulombCount > 0 || cycles.since != null
        if (!counted && dates.firstUsed == null && dates.manufactured == null) {
            StandIn(stringResource(R.string.health_no_cycles), top = 0.dp)
            return@Column
        }
        CoulombFigureStrip {
            cycles.platformCount?.let { count ->
                CoulombFigure(stringResource(R.string.health_cycle_count), stringResource(R.string.health_cycle_count_note)) {
                    Text(formats.integer(count.toLong()), color = colors.textPrimary, style = type.title)
                    ProvenanceMark(Provenance.MEASURED)
                }
            }
            if (cycles.coulombCount > 0 || cycles.since != null) CountedFigure(cycles, formats)
            (dates.firstUsed ?: dates.manufactured)?.let { DateFigure(it, dates, formats) }
        }
        if (!counted) StandIn(stringResource(R.string.health_no_cycles))
    }
}

/** Coulomb's running sum, with the platform's count over the same days where there is one. */
@Composable
private fun CountedFigure(
    cycles: Cycles,
    formats: Formats,
) {
    val since = cycles.since?.let { formats.day(it) }
    val platform = cycles.platformCounted
    val caption =
        when {
            since != null && platform != null -> {
                stringResource(
                    R.string.health_counted_since_android,
                    since,
                    formats.integer(platform.toLong()),
                )
            }

            since != null -> {
                stringResource(R.string.health_counted_since, since)
            }

            else -> {
                null
            }
        }
    CoulombFigure(stringResource(R.string.health_counted), caption) {
        Text(formats.decimal(cycles.coulombCount), color = CoulombTheme.colors.textPrimary, style = CoulombTheme.typography.title)
        ProvenanceMark(Provenance.DERIVED)
    }
}

/** The first-use date with the manufacture date under it, or the manufacture date alone. */
@Composable
private fun DateFigure(
    date: BatteryDate,
    dates: BatteryDates,
    formats: Formats,
) {
    val first = date == dates.firstUsed
    val made = dates.manufactured?.takeIf { first }
    val caption = made?.let { stringResource(R.string.health_made_in, formats.monthYear(it.date)) } ?: stringResource(sourceNote(date))
    CoulombFigure(stringResource(if (first) R.string.health_first_used else R.string.health_made), caption) {
        Text(formats.monthYear(date.date), color = CoulombTheme.colors.textPrimary, style = CoulombTheme.typography.title)
        ProvenanceMark(Provenance.MEASURED)
    }
}

private fun sourceNote(date: BatteryDate): Int =
    when (date.source) {
        BatteryDateSource.SYSTEM -> R.string.health_by_battery
        BatteryDateSource.OEM -> R.string.health_by_manufacturer
    }

/**
 * Capacity over time (SPEC 10.8 item 4): each window a cross, the day's estimate a line in its
 * band, the design capacity a dashed line; the caption defines a qualifying window.
 */
@Composable
internal fun Trend(
    report: HealthReport,
    formats: Formats,
    zone: ZoneId,
    onViewTable: () -> Unit,
) {
    val trend = report.trend
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.health_over_time), Provenance.ESTIMATED.takeIf { trend.days.isNotEmpty() })
        if (trend.days.isEmpty()) {
            StandIn(stringResource(R.string.health_trend_empty), top = 0.dp)
            return@Column
        }
        val first = trend.days.first()
        val last = trend.days.last()
        IntervalTrendChart(
            days = trend.days.map { IntervalDay(it.day, it.mah, it.lowMah, it.highMah) },
            crosses = trend.points.map { TrendCross(it.wallMs, it.mah) },
            reference = report.design?.mah,
            referenceLabel = stringResource(R.string.health_design_line).takeIf { report.design != null },
            labels =
                TrendLabels(
                    value = { formats.integer(it.roundToLong()) },
                    day = formats::day,
                    month = formats::month,
                ),
            summary =
                stringResource(
                    R.string.health_trend_summary,
                    formats.integer(trend.points.size.toLong()),
                    formats.day(first.day),
                    formats.day(last.day),
                    formats.mah(last.mah).joined(),
                ),
            viewTableLabel = stringResource(UiR.string.page_view_table),
            onViewTable = onViewTable,
            zone = zone,
        )
        Note(stringResource(R.string.health_trend_caption))
        ViewTableButton(onViewTable)
    }
}

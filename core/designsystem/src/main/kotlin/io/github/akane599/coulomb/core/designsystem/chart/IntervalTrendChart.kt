// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor

/** One day's estimate with its interval, at the day's middle. */
@Immutable
public data class IntervalDay(
    val day: LocalDate,
    val value: Double,
    val low: Double,
    val high: Double,
)

/** One reading drawn as a cross. */
@Immutable
public data class TrendCross(
    val wallMs: Long,
    val value: Double,
)

/** The words an [IntervalTrendChart] draws. */
@Immutable
public data class TrendLabels(
    /** A value on the axis, such as "4,600". */
    val value: (Double) -> String,
    /** The first day, in full: "12 Aug". */
    val day: (LocalDate) -> String,
    /** The first of a month: "Sep". */
    val month: (LocalDate) -> String,
)

private val PlotHeight = 120.dp
private val LabelGap = 4.dp
private val ValueGap = 8.dp
private val InkWidth = 1.5.dp
private val EdgeWidth = 1.dp
private val Cross = 3.dp
private val BracketWidth = 3.dp
private val BracketStop = 5.dp
private val Dash = 2.dp
private val DashGap = 3.dp
private val Tick = 3.dp
private val LabelSpacing = 8.dp
private const val TINT = 0.14f
private const val HALF_DAY_MS = 43_200_000L
private const val MONTH_TICKS_FROM_DAYS = 45
private const val WEEK_TICKS_FROM_DAYS = 10
private val NICE_STEPS = listOf(10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 250.0, 500.0, 1_000.0, 2_000.0)
private const val MAX_GRIDLINES = 4

/**
 * Health's capacity over time (lab `.cap-chart`; DESIGN.md, Charts): each reading a cross in
 * `text-secondary`, the daily estimate a line in ink over its interval as a 14% copper band with a
 * solid 1 dp copper edge, and the latest interval as a copper bracket at the right end. A dashed
 * hairline marks [reference] (the design capacity) with [referenceLabel] over it. Values on the
 * right in Caption, days under the axis (the first day in full, then months, weeks or days as the
 * span allows). Time runs left to right in either layout direction. Draws in once; TalkBack reads
 * [summary] and offers "View as table".
 */
@Composable
@Suppress("LongParameterList") // One chart's data, labels and accessibility.
public fun IntervalTrendChart(
    days: List<IntervalDay>,
    crosses: List<TrendCross>,
    reference: Double?,
    referenceLabel: String?,
    labels: TrendLabels,
    summary: String,
    viewTableLabel: String,
    onViewTable: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val style = remember(type, colors) { type.caption.copy(color = colors.textSecondary) }
    val measurer = rememberTextMeasurer()
    val labelHeight = remember(measurer, style) { measurer.measure("0", style).size.height }
    val height = with(LocalDensity.current) { labelHeight.toDp() * 2 + LabelGap * 2 + PlotHeight }
    val ink = ChartInk(hairline = colors.hairline, axis = colors.textSecondary, line = colors.textPrimary, accent = colors.accent)
    val drawIn = rememberDrawIn()
    Canvas(
        with(drawIn) { modifier.fade() }
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics {
                contentDescription = summary
                customActions = listOf(CustomAccessibilityAction(viewTableLabel) { onViewTable().let { true } })
            },
    ) {
        if (days.isEmpty() && crosses.isEmpty()) return@Canvas
        val text = ChartText(measurer, style)
        val frame = TrendFrame.of(days, crosses, reference, zone)
        val grid = frame.gridValues()
        val valueWidth = grid.maxOf { text.measure(labels.value(it)).size.width } + ValueGap.toPx()
        val top = labelHeight + LabelGap.toPx()
        val plot = TrendPlot(frame, size.width - valueWidth - BracketWidth.toPx() * 2, top, top + PlotHeight.toPx())
        grid.forEach { value ->
            val y = plot.y(value)
            drawLine(ink.hairline, Offset(0f, y), Offset(plot.width, y), 1.dp.toPx())
            val label = text.measure(labels.value(value))
            drawText(label, topLeft = Offset(size.width - label.size.width, y - label.size.height / 2))
        }
        reference?.let { value ->
            val y = plot.y(value)
            val dashed = PathEffect.dashPathEffect(floatArrayOf(Dash.toPx(), DashGap.toPx()))
            drawLine(ink.axis, Offset(0f, y), Offset(plot.width, y), 1.dp.toPx(), pathEffect = dashed)
            referenceLabel?.let { drawText(text.measure(it), topLeft = Offset(0f, y - labelHeight - LabelGap.toPx() / 2)) }
        }
        drawLine(ink.axis, Offset(0f, plot.bottom), Offset(plot.width, plot.bottom), 1.dp.toPx())
        dayAxis(plot, ink, text, labels, zone)
        with(drawIn) {
            revealed {
                band(plot, ink, days, zone)
                crosses.forEach { cross(plot, ink, it) }
                line(plot, ink, days, zone)
                days.lastOrNull()?.let { latestBracket(plot, ink, it) }
            }
        }
    }
}

/** The data's time and value ranges. */
private class TrendFrame(
    val startMs: Long,
    val endMs: Long,
    val min: Double,
    val max: Double,
    val step: Double,
) {
    fun gridValues(): List<Double> {
        val first = ceil(min / step).toLong()
        val last = floor(max / step).toLong()
        return (first..last).map { it * step }
    }

    companion object {
        fun of(
            days: List<IntervalDay>,
            crosses: List<TrendCross>,
            reference: Double?,
            zone: ZoneId,
        ): TrendFrame {
            val times = days.map { middle(it.day, zone) } + crosses.map { it.wallMs }
            val values = days.flatMap { listOf(it.low, it.high) } + crosses.map { it.value } + listOfNotNull(reference)
            val low = values.min()
            val high = values.max()
            val step = NICE_STEPS.firstOrNull { (high - low) / it <= MAX_GRIDLINES } ?: NICE_STEPS.last()
            val start = times.min() - HALF_DAY_MS
            val end = maxOf(times.max() + HALF_DAY_MS, start + 2 * HALF_DAY_MS)
            return TrendFrame(start, end, floor(low / step) * step, ceil(high / step) * step + if (high == low) step else 0.0, step)
        }
    }
}

private class TrendPlot(
    val frame: TrendFrame,
    val width: Float,
    val top: Float,
    val bottom: Float,
) {
    fun x(ms: Long): Float = ((ms - frame.startMs).toFloat() / (frame.endMs - frame.startMs)) * width

    fun y(value: Double): Float = (bottom - (value - frame.min) / (frame.max - frame.min) * (bottom - top)).toFloat()
}

private fun middle(
    day: LocalDate,
    zone: ZoneId,
): Long = day.atStartOfDay(zone).toInstant().toEpochMilli() + HALF_DAY_MS

private fun DrawScope.band(
    plot: TrendPlot,
    ink: ChartInk,
    days: List<IntervalDay>,
    zone: ZoneId,
) {
    if (days.size < 2) return
    val upper = days.map { Offset(plot.x(middle(it.day, zone)), plot.y(it.high)) }
    val lower = days.map { Offset(plot.x(middle(it.day, zone)), plot.y(it.low)) }
    val area =
        Path().apply {
            moveTo(upper.first().x, upper.first().y)
            upper.drop(1).forEach { lineTo(it.x, it.y) }
            lower.asReversed().forEach { lineTo(it.x, it.y) }
            close()
        }
    drawPath(area, ink.accent.copy(alpha = TINT))
    listOf(upper, lower).forEach { edge -> drawPath(polyline(edge), ink.accent, style = Stroke(EdgeWidth.toPx())) }
}

private fun DrawScope.line(
    plot: TrendPlot,
    ink: ChartInk,
    days: List<IntervalDay>,
    zone: ZoneId,
) {
    if (days.size < 2) return
    val points = days.map { Offset(plot.x(middle(it.day, zone)), plot.y(it.value)) }
    drawPath(polyline(points), ink.line, style = Stroke(InkWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun polyline(points: List<Offset>): Path =
    Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }

/** A cross is drawn half again as heavy as the band's edge, so it reads over the band. */
private const val CROSS_WEIGHT = 1.5f

private fun DrawScope.cross(
    plot: TrendPlot,
    ink: ChartInk,
    reading: TrendCross,
) {
    val c = Offset(plot.x(reading.wallMs), plot.y(reading.value))
    val r = Cross.toPx()
    val w = EdgeWidth.toPx() * CROSS_WEIGHT
    drawLine(ink.axis, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), w, cap = StrokeCap.Round)
    drawLine(ink.axis, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), w, cap = StrokeCap.Round)
}

/** The latest interval as a vertical copper bracket just right of the plot, with its tick. */
private fun DrawScope.latestBracket(
    plot: TrendPlot,
    ink: ChartInk,
    latest: IntervalDay,
) {
    val x = plot.width + BracketWidth.toPx()
    val stop = BracketStop.toPx()
    val bracket =
        Path().apply {
            moveTo(x + stop, plot.y(latest.high))
            lineTo(x, plot.y(latest.high))
            lineTo(x, plot.y(latest.low))
            lineTo(x + stop, plot.y(latest.low))
        }
    drawPath(bracket, ink.accent, style = Stroke(BracketWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The first day in full, then the first of each month, each Monday or each day as the span allows. */
private fun DrawScope.dayAxis(
    plot: TrendPlot,
    ink: ChartInk,
    text: ChartText,
    labels: TrendLabels,
    zone: ZoneId,
) {
    val first = Instant.ofEpochMilli(plot.frame.startMs + HALF_DAY_MS).atZone(zone).toLocalDate()
    val last = Instant.ofEpochMilli(plot.frame.endMs - HALF_DAY_MS).atZone(zone).toLocalDate()
    val spanDays = last.toEpochDay() - first.toEpochDay()
    val ticks =
        generateSequence(first) { it.plusDays(1) }
            .takeWhile { !it.isAfter(last) }
            .filter { day ->
                when {
                    spanDays >= MONTH_TICKS_FROM_DAYS -> day.dayOfMonth == 1
                    spanDays >= WEEK_TICKS_FROM_DAYS -> day.dayOfWeek == DayOfWeek.MONDAY
                    else -> true
                }
            }.toList()
    val placed = mutableListOf<ClosedFloatingPointRange<Float>>()
    val labelTop = plot.bottom + Tick.toPx() + LabelGap.toPx() / 2

    fun label(
        day: LocalDate,
        words: String,
    ) {
        val x = plot.x(middle(day, zone))
        val layout = text.measure(words)
        val span = placeLabel(layout.size.width.toFloat(), x)
        if (span.endInclusive <= plot.width && placed.none { it.overlaps(span, LabelSpacing.toPx()) }) {
            drawText(layout, topLeft = Offset(span.start, labelTop))
            placed += span
        }
    }
    label(first, labels.day(first))
    ticks.forEach { day ->
        val x = plot.x(middle(day, zone))
        drawLine(ink.axis, Offset(x, plot.bottom), Offset(x, plot.bottom + Tick.toPx()), 1.dp.toPx())
        label(day, if (spanDays >= MONTH_TICKS_FROM_DAYS) labels.month(day) else labels.day(day))
    }
}

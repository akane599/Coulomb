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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/** One current reading on a [LiveCurrentChart]; positive while charging. */
@Immutable
public data class CurrentMark(
    val wallMs: Long,
    val currentMa: Double,
)

/** The words a [LiveCurrentChart] draws: the time axis ends and middle, and the value labels. */
@Immutable
public data class CurrentLabels(
    val start: String,
    val middle: String,
    val end: String,
    /** A gridline value; the zero line's label carries the unit. */
    val value: (Double) -> String,
)

private val PlotHeight = 90.dp
private val ValueGap = 8.dp
private val TimeGap = 10.dp
private val InkWidth = 1.5.dp

private const val TINT = 0.14f

/** DESIGN.md's charge current: 45% of `accent` (lab `.cur-in`). */
private const val CHARGE_TINT = 0.45f
private const val INTERVALS = 4

/** Readings further apart than this are a gap, not a line. */
private const val GAP_MS = 60_000L
private val NICE_STEPS = listOf(1.0, 2.0, 2.5, 5.0, 10.0)

/**
 * SPEC 10.2's live current: the last [windowMs] ending at [nowMs], as DESIGN.md's charts draw it.
 * Discharge plots below the zero axis and charging above, so direction reads without colour. The
 * area is a 14% `text-secondary` tint below zero and 45% copper above it (SPEC 11.4: charging uses
 * the accent), edged by the ink line; gridlines are hairlines at quarter
 * intervals, and the values sit at the end in Caption. The line meets the "now" edge with no dot.
 * It draws in once on first appearance (SPEC 11.6), then draws what it is given without motion;
 * the caller updates it at most once a second.
 */
@Composable
@Suppress("LongParameterList") // One chart's data, labels and accessibility; grouping them would only rename them.
public fun LiveCurrentChart(
    marks: List<CurrentMark>,
    nowMs: Long,
    windowMs: Long,
    labels: CurrentLabels,
    summary: String,
    viewTableLabel: String,
    onViewTable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    val caption = CoulombTheme.typography.caption
    val style = remember(caption, colors) { caption.copy(color = colors.textSecondary) }
    val measurer = rememberTextMeasurer()
    val text = ChartText(measurer, style)
    val labelHeight = remember(measurer, style) { measurer.measure("0", style).size.height }
    val ink = ChartInk(hairline = colors.hairline, axis = colors.textSecondary, line = colors.textPrimary, accent = colors.accent)
    val range = remember(marks) { NiceRange.of(marks.map { it.currentMa }) }
    val height = with(LocalDensity.current) { labelHeight.toDp() * 2 + PlotHeight + TimeGap }
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
        // Every other gridline counted from zero, so zero, which carries the unit, is always labelled.
        val labelsAt = range.gridlines().filter { (abs(it / range.step).roundToLong() % 2) == 0L }
        val valueWidth = labelsAt.maxOf { text.measure(labels.value(it)).size.width } + ValueGap.toPx()
        val plot = Plot(width = size.width - valueWidth, top = labelHeight / 2f, bottom = labelHeight / 2f + PlotHeight.toPx(), range)

        range.gridlines().forEach { value ->
            val y = plot.y(value)
            drawLine(if (value == 0.0) ink.axis else ink.hairline, Offset(0f, y), Offset(plot.width, y), 1.dp.toPx())
        }
        labelsAt.forEach { value ->
            val label = text.measure(labels.value(value))
            drawText(label, topLeft = Offset(size.width - label.size.width, plot.y(value) - label.size.height / 2f))
        }
        with(drawIn) { revealed { series(plot, ink, marks.filter { it.wallMs in (nowMs - windowMs)..nowMs }, nowMs, windowMs) } }
        val timeTop = plot.bottom + TimeGap.toPx()
        val start = text.measure(labels.start)
        val middle = text.measure(labels.middle)
        val end = text.measure(labels.end)
        drawText(start, topLeft = Offset(0f, timeTop))
        drawText(middle, topLeft = Offset((plot.width - middle.size.width) / 2f, timeTop))
        drawText(end, topLeft = Offset(plot.width - end.size.width, timeTop))
    }
}

private class Plot(
    val width: Float,
    val top: Float,
    val bottom: Float,
    val range: NiceRange,
) {
    fun y(value: Double): Float = (top + (range.max - value) / (range.max - range.min) * (bottom - top)).toFloat()
}

private fun DrawScope.series(
    plot: Plot,
    ink: ChartInk,
    marks: List<CurrentMark>,
    nowMs: Long,
    windowMs: Long,
) {
    if (marks.isEmpty()) return

    fun x(ms: Long) = ((ms - (nowMs - windowMs)).toFloat() / windowMs) * plot.width
    val zero = plot.y(0.0)
    val runs = mutableListOf(mutableListOf(marks.first()))
    marks.zipWithNext().forEach { (a, b) -> if (b.wallMs - a.wallMs > GAP_MS) runs += mutableListOf(b) else runs.last() += b }
    for (run in runs) {
        val area = Path().apply { moveTo(x(run.first().wallMs), zero) }
        val line = Path()
        run.forEachIndexed { index, mark ->
            val point = Offset(x(mark.wallMs), plot.y(mark.currentMa))
            area.lineTo(point.x, point.y)
            if (index == 0) line.moveTo(point.x, point.y) else line.lineTo(point.x, point.y)
        }
        area.lineTo(x(run.last().wallMs), zero)
        area.close()
        // One area, split at the zero line: charge above in copper, discharge below in neutral ink.
        clipRect(bottom = zero) { drawPath(area, ink.accent.copy(alpha = CHARGE_TINT)) }
        clipRect(top = zero) { drawPath(area, ink.axis.copy(alpha = TINT)) }
        drawPath(line, ink.line, style = Stroke(InkWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A value range that includes zero, cut into [INTERVALS] or so steps of 1, 2, 2.5 or 5 × 10ⁿ. */
internal data class NiceRange(
    val min: Double,
    val max: Double,
    val step: Double,
) {
    fun gridlines(): List<Double> {
        val count = ((max - min) / step).roundToLong().toInt()
        return (0..count).map { max - it * step }.map { if (abs(it) < step / 2) 0.0 else it }
    }

    companion object {
        private const val DEFAULT_SPAN = 100.0
        private const val TOLERANCE = 1e-9

        fun of(values: List<Double>): NiceRange {
            val high = maxOf(0.0, values.maxOrNull() ?: 0.0)
            // Nothing to show yet: an empty range below zero, the usual direction.
            val low = minOf(0.0, values.minOrNull() ?: 0.0).takeIf { high - it > 0 } ?: -DEFAULT_SPAN
            val rough = (high - low) / INTERVALS
            val magnitude = 10.0.pow(floor(log10(rough)))
            val step = NICE_STEPS.first { it * magnitude >= rough * (1 - TOLERANCE) } * magnitude
            return NiceRange(min = floor(low / step) * step, max = ceil(high / step) * step, step = step)
        }
    }
}

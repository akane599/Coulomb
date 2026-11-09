// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import kotlin.math.ceil
import kotlin.math.floor

private val StopHeight = 7.dp
private val BracketWidth = 3.dp
private val PointTick = 9.dp
private val MinorTick = 3.dp
private val MajorTick = 5.dp
private val LabelGap = 4.dp
private val LabelSpacing = 8.dp

/**
 * DESIGN.md's signature on Health's hero (lab `.cap-scale`): an estimate's interval as a copper
 * bracket ([low] to [high]) on a graduated axis from [from] to [to], its point estimate a tick in
 * ink (a point estimate is a tick, never a dot). [rangeLabel] sits over the bracket in Caption 600,
 * [endLabel] over the axis's end; [labels] under the axis, each with its optional second line.
 * Minor ticks every [minorStep]. Values run left to right in either layout direction. TalkBack
 * reads [summary] and offers "View as table".
 */
@Composable
@Suppress("LongParameterList") // One scale's values, words and accessibility.
public fun IntervalScale(
    from: Double,
    to: Double,
    low: Double,
    high: Double,
    point: Double,
    rangeLabel: String,
    endLabel: String?,
    labels: List<AxisLabel>,
    minorStep: Double,
    summary: String,
    viewTableLabel: String,
    onViewTable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val style = remember(type, colors) { type.caption.copy(color = colors.textSecondary) }
    val strong = remember(type, colors) { type.captionEmphasis.copy(color = colors.textPrimary) }
    val measurer = rememberTextMeasurer()
    val labelHeight = remember(measurer, style) { measurer.measure("0", style).size.height }
    val lines = if (labels.any { it.sub != null }) 2 else 1
    val height = with(LocalDensity.current) { labelHeight.toDp() * (1 + lines) + LabelGap * 2 + StopHeight + MajorTick }
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
        val text = ChartText(measurer, style)
        val emphasis = ChartText(measurer, strong)
        val span = (to - from).takeIf { it > 0 } ?: 1.0

        fun x(value: Double): Float = ((value.coerceIn(from, to) - from) / span * size.width).toFloat()
        val axis = labelHeight + LabelGap.toPx() + StopHeight.toPx()
        val stroke = 1.dp.toPx()
        drawLine(colors.textSecondary, Offset(0f, axis), Offset(size.width, axis), stroke)
        minorTicks(from, to, minorStep, axis, colors.textSecondary, ::x)
        axisLabels(labels, axis, text, labelHeight, colors.textSecondary, ::x)
        val reserved = mutableListOf<ClosedFloatingPointRange<Float>>()
        with(drawIn) {
            revealed {
                val start = x(low)
                val end = x(high)
                val bracket =
                    Path().apply {
                        moveTo(start, axis - StopHeight.toPx())
                        lineTo(start, axis)
                        lineTo(end, axis)
                        lineTo(end, axis - StopHeight.toPx())
                    }
                drawPath(bracket, colors.accent, style = Stroke(BracketWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val tick = x(point)
                drawLine(colors.textPrimary, Offset(tick, axis - PointTick.toPx()), Offset(tick, axis), BracketWidth.toPx() / 2)
            }
        }
        val range = emphasis.measure(rangeLabel)
        val rangeSpan = placeLabel(range.size.width.toFloat(), (x(low) + x(high)) / 2)
        drawText(range, topLeft = Offset(rangeSpan.start, 0f))
        reserved += rangeSpan
        endLabel?.let { label ->
            val end = text.measure(label)
            val endSpan = (size.width - end.size.width)..size.width
            if (reserved.none { it.overlaps(endSpan, LabelSpacing.toPx()) }) drawText(end, topLeft = Offset(endSpan.start, 0f))
        }
    }
}

/** A short tick every [step] along the axis. */
@Suppress("LongParameterList") // One axis's range, step and ink.
private fun DrawScope.minorTicks(
    from: Double,
    to: Double,
    step: Double,
    axis: Float,
    color: Color,
    x: (Double) -> Float,
) {
    if (step <= 0) return
    val stroke = 1.dp.toPx()
    for (index in ceil(from / step).toLong()..floor(to / step).toLong()) {
        val px = x(index * step)
        drawLine(color, Offset(px, axis), Offset(px, axis + MinorTick.toPx()), stroke)
    }
}

/**
 * Each label's tick, then its one or two lines under the axis where they do not run into a label
 * already placed. The two ends go first, so a crowded axis drops an interior label, never its range.
 */
@Suppress("LongParameterList") // The labels, where they go and how they look.
private fun DrawScope.axisLabels(
    labels: List<AxisLabel>,
    axis: Float,
    text: ChartText,
    labelHeight: Int,
    color: Color,
    x: (Double) -> Float,
) {
    val stroke = 1.dp.toPx()
    val placed = mutableListOf<ClosedFloatingPointRange<Float>>()
    val ends = if (labels.size > 2) listOf(labels.first(), labels.last()) else labels
    (
        ends +
            labels
                .drop(1)
                .dropLast(1)
                .takeIf { labels.size > 2 }
                .orEmpty()
    ).forEach { label ->
        val px = x(label.value)
        drawLine(color, Offset(px, axis), Offset(px, axis + MajorTick.toPx()), stroke)
        val main = text.measure(label.text)
        val sub = label.sub?.let(text::measure)
        val place = placeLabel(maxOf(main.size.width, sub?.size?.width ?: 0).toFloat(), px)
        if (placed.none { it.overlaps(place, LabelSpacing.toPx()) }) {
            val top = axis + MajorTick.toPx() + LabelGap.toPx()
            drawText(main, topLeft = Offset(alignIn(place, main.size.width.toFloat(), px), top))
            sub?.let { drawText(it, topLeft = Offset(alignIn(place, it.size.width.toFloat(), px), top + labelHeight)) }
            placed += place
        }
    }
}

/** A line of [width] inside a label block [place] whose anchor is [anchor]: centred unless the block was moved to an edge. */
private fun alignIn(
    place: ClosedFloatingPointRange<Float>,
    width: Float,
    anchor: Float,
): Float {
    val centred = anchor - width / 2
    return centred.coerceIn(place.start, (place.endInclusive - width).coerceAtLeast(place.start))
}

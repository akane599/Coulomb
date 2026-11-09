// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme

/**
 * One bar a day, oldest first (DESIGN.md, Charts; lab App detail trend): bars in `text-secondary`
 * and the last day, so far, in `text-primary`, on hairline gridlines at quarter intervals of [top]
 * with every other one labelled at the end in Caption. A day with no record ([days] null) has no
 * bar and breaks the axis under it, so it never reads as zero. A marker, such as an update, is a
 * dashed `text-primary` rule before the day [markerAt], labelled in Caption 600 (markers are
 * rules, never provenance shapes). Time runs left to right in either layout direction, as on Now.
 * The bars draw in once on first appearance (SPEC 11.6). TalkBack reads [summary] and offers
 * "View as table".
 */
@Composable
@Suppress("LongParameterList") // One chart's data, labels and accessibility.
public fun DailyBarChart(
    days: List<Double?>,
    top: Double,
    labels: DailyBarLabels,
    summary: String,
    viewTableLabel: String,
    onViewTable: () -> Unit,
    modifier: Modifier = Modifier,
    markerAt: Int? = null,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val style = remember(type, colors) { type.caption.copy(color = colors.textSecondary) }
    val strong = remember(type, colors) { type.captionEmphasis.copy(color = colors.textPrimary) }
    val measurer = rememberTextMeasurer()
    val text = ChartText(measurer, style)
    val emphasis = ChartText(measurer, strong)
    val labelHeight = remember(measurer, style) { measurer.measure("0", style).size.height }
    val height = with(LocalDensity.current) { labelHeight.toDp() * 2 + UnitGap + PlotHeight + TimeGap }
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
        val stroke = 1.dp.toPx()
        val quarters = (1..QUARTERS).map { top * it / QUARTERS }
        val labelled = quarters.filterIndexed { index, _ -> index % 2 == 1 }
        val valueWidth = labelled.maxOf { text.measure(labels.value(it)).size.width } + ValueGap.toPx()
        val width = size.width - valueWidth
        val plotTop = labelHeight + UnitGap.toPx()
        val axis = plotTop + PlotHeight.toPx()

        fun y(value: Double) = (axis - value / top * (axis - plotTop)).toFloat()
        val slot = width / days.size

        quarters.forEach { drawLine(colors.hairline, Offset(0f, y(it)), Offset(width, y(it)), stroke) }
        labelled.forEach { value ->
            val label = text.measure(labels.value(value))
            drawText(label, topLeft = Offset(size.width - label.size.width, y(value) - label.size.height / 2f))
        }
        // The axis, broken under each day without a record.
        days.forEachIndexed { index, mah ->
            if (mah != null) drawLine(colors.textSecondary, Offset(index * slot, axis), Offset((index + 1) * slot, axis), stroke)
        }
        drawText(text.measure(labels.unit), topLeft = Offset.Zero)
        val unitWidth = text.measure(labels.unit).size.width
        with(drawIn) {
            revealed {
                val bar = slot * BAR_SHARE
                val corner = CornerRadius(BarEnd.toPx())
                days.forEachIndexed { index, mah ->
                    if (mah == null || mah <= 0.0) return@forEachIndexed
                    val barTop = minOf(y(mah), axis - MinBar.toPx())
                    drawRoundRect(
                        if (index == days.lastIndex) colors.textPrimary else colors.textSecondary,
                        Offset(index * slot + (slot - bar) / 2, barTop),
                        Size(bar, axis - barTop),
                        corner,
                    )
                }
            }
        }
        val marker = labels.marker
        if (markerAt != null && marker != null) {
            drawMarker(emphasis.measure(marker), markerAt * slot, axis, unitWidth, width, colors.textPrimary)
        }
        val timeTop = axis + TimeGap.toPx()
        val start = text.measure(labels.start)
        val middle = text.measure(labels.middle)
        val end = text.measure(labels.end)
        val middleCenter = (days.size / 2 + HALF) * slot
        drawText(start, topLeft = Offset(0f, timeTop))
        drawText(middle, topLeft = Offset(middleCenter - middle.size.width / 2f, timeTop))
        drawText(end, topLeft = Offset(width - end.size.width, timeTop))
    }
}

private const val QUARTERS = 4
private const val HALF = 0.5f

/**
 * The marker's dashed rule at [x], from the top to the [axis], and its label before the rule, as
 * in the lab, unless that would run into the unit line ([unitWidth]); then after it.
 */
@Suppress("LongParameterList") // One rule's geometry.
private fun DrawScope.drawMarker(
    label: TextLayoutResult,
    x: Float,
    axis: Float,
    unitWidth: Int,
    width: Float,
    color: Color,
) {
    val dash = MarkerDash.toPx()
    drawLine(color, Offset(x, 0f), Offset(x, axis), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)))
    val gap = MarkerGap.toPx()
    val before = x - gap - label.size.width
    val left = if (before >= unitWidth + gap) before else (x + gap).coerceAtMost(width - label.size.width)
    drawText(label, topLeft = Offset(left, 0f))
}

/** Of each day's slot, the bar's width; the rest is the space between bars (lab: 18.6 of 24.6). */
private const val BAR_SHARE = 0.76f
private val PlotHeight = 96.dp
private val UnitGap = 6.dp
private val ValueGap = 8.dp
private val TimeGap = 10.dp
private val BarEnd = 2.dp

/** A day above zero stays visible however small it is beside the largest. */
private val MinBar = 2.dp
private val MarkerDash = 3.dp
private val MarkerGap = 6.dp

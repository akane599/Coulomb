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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import kotlin.math.ceil

/** One reading of a charge: level, and power into the battery where it is known. */
@Immutable
public data class CurveReading(
    val wallMs: Long,
    val levelPct: Float,
    val watts: Float?,
)

/** The words a [ChargeCurveChart] draws. */
@Immutable
public data class CurveLabels(
    val percent: (Int) -> String,
    /** A power on the axis, with its unit: "12 W". */
    val watts: (Double) -> String,
    val time: (Long) -> String,
    /** Over the level panel's dashed rule at the taper, such as "Slowing down". */
    val taper: String?,
    /** Over the power panel's dashed rule at full speed, such as "Full speed". */
    val fullSpeed: String?,
)

private val LevelHeight = 96.dp
private val PowerHeight = 72.dp
private val PanelGap = 16.dp
private val LabelGap = 4.dp
private val ValueGap = 8.dp
private val InkWidth = 1.5.dp
private val Dash = 2.dp
private val DashGap = 3.dp
private val LabelSpacing = 8.dp
private const val FULL = 100
private const val HALF = 50
private val WATT_STEPS = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0)
private const val POWER_LINES = 2

/**
 * A charge session's curve (SPEC 10.8 item 6): the level from 0 to 100% over the session, and
 * under it the power into the battery, sharing the time axis. Where the current tapered, the
 * level panel has a dashed rule at [taperLevel]; the power panel one at [fullSpeedW]. Readings
 * without power leave the power line broken there, so a gap never reads as zero. Values on the
 * right in Caption, the session's start and end clock under the axis. Draws in once; TalkBack
 * reads [summary] and offers "View as table".
 */
@Composable
@Suppress("LongParameterList") // One chart's data, labels and accessibility.
public fun ChargeCurveChart(
    readings: List<CurveReading>,
    taperLevel: Int?,
    fullSpeedW: Double?,
    labels: CurveLabels,
    summary: String,
    viewTableLabel: String,
    onViewTable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val style = remember(type, colors) { type.caption.copy(color = colors.textSecondary) }
    val measurer = rememberTextMeasurer()
    val labelHeight = remember(measurer, style) { measurer.measure("0", style).size.height }
    val hasPower = readings.any { it.watts != null }
    val height = with(LocalDensity.current) { chartHeight(labelHeight.toDp(), hasPower) }
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
        if (readings.size < 2) return@Canvas
        val text = ChartText(measurer, style)
        val wattTop = wattTop(readings)
        val valueWidth =
            maxOf(
                text.measure(labels.percent(FULL)).size.width,
                if (hasPower) text.measure(labels.watts(wattTop)).size.width else 0,
            ) + ValueGap.toPx()
        val width = size.width - valueWidth
        val start = readings.first().wallMs
        val end = readings.last().wallMs.coerceAtLeast(start + 1)

        fun x(ms: Long) = (ms - start).toFloat() / (end - start) * width
        val levelTop = labelHeight + LabelGap.toPx()
        val levelBottom = levelTop + LevelHeight.toPx()
        val level = Panel(levelTop, levelBottom, FULL.toDouble())
        listOf(0, HALF, FULL).forEach { pct ->
            gridLine(level.y(pct.toDouble()), width, ink, text, labels.percent(pct))
        }
        taperLevel?.let { rule(level.y(it.toDouble()), width, ink, text, labels.taper) }
        var axis = levelBottom
        val power =
            if (hasPower) {
                val top = levelBottom + PanelGap.toPx() + labelHeight
                Panel(top, top + PowerHeight.toPx(), wattTop).also { panel ->
                    powerGrid(panel, fullSpeedW, width, ink, text, labels)
                    axis = panel.bottom
                }
            } else {
                null
            }
        drawLine(ink.axis, Offset(0f, axis), Offset(width, axis), 1.dp.toPx())
        timeLabels(text.measure(labels.time(start)), text.measure(labels.time(end)), axis + LabelGap.toPx(), width)
        with(drawIn) {
            revealed {
                series(readings.map { Offset(x(it.wallMs), level.y(it.levelPct.toDouble())) }, ink)
                power?.let { panel -> powerRuns(readings, panel, ::x).forEach { series(it, ink) } }
            }
        }
    }
}

/** Two label lines and the level panel, and under them the power panel with its top label where there is power. */
private fun chartHeight(
    label: Dp,
    hasPower: Boolean,
): Dp = label * 2 + LabelGap * 2 + LevelHeight + if (hasPower) PanelGap + label + PowerHeight else 0.dp

/** The power axis's top: a round step over the highest reading, at least one step. */
private fun wattTop(readings: List<CurveReading>): Double {
    val maxWatts = readings.mapNotNull { it.watts?.toDouble() }.maxOrNull() ?: 0.0
    val step = WATT_STEPS.firstOrNull { it * POWER_LINES >= maxWatts } ?: WATT_STEPS.last()
    return ceil(maxWatts / step).coerceAtLeast(1.0) * step
}

/** The power panel's grid lines with their values, and the dashed rule at full speed. */
@Suppress("LongParameterList") // One panel's scale, rule and ink.
private fun DrawScope.powerGrid(
    panel: Panel,
    fullSpeedW: Double?,
    width: Float,
    ink: ChartInk,
    text: ChartText,
    labels: CurveLabels,
) {
    for (line in 0..POWER_LINES) {
        val w = panel.max * line / POWER_LINES
        gridLine(panel.y(w), width, ink, text, labels.watts(w))
    }
    fullSpeedW?.let { rule(panel.y(it), width, ink, text, labels.fullSpeed) }
}

/** The power line as runs between readings without power, so a gap never reads as zero. */
private fun powerRuns(
    readings: List<CurveReading>,
    panel: Panel,
    x: (Long) -> Float,
): List<List<Offset>> {
    val runs = mutableListOf(mutableListOf<Offset>())
    readings.forEach { r ->
        val w = r.watts
        when {
            w != null -> runs.last() += Offset(x(r.wallMs), panel.y(w.toDouble()))
            runs.last().isNotEmpty() -> runs += mutableListOf<Offset>()
        }
    }
    return runs.filter { it.size >= 2 }
}

/** The session's start clock under the axis's start and, where both fit, its end clock under the end. */
private fun DrawScope.timeLabels(
    first: TextLayoutResult,
    last: TextLayoutResult,
    top: Float,
    width: Float,
) {
    drawText(first, topLeft = Offset(0f, top))
    if (first.size.width + last.size.width + LabelSpacing.toPx() <= width) {
        drawText(last, topLeft = Offset(width - last.size.width, top))
    }
}

private class Panel(
    val top: Float,
    val bottom: Float,
    val max: Double,
) {
    fun y(value: Double): Float = (bottom - value.coerceIn(0.0, max) / max * (bottom - top)).toFloat()
}

private fun DrawScope.gridLine(
    y: Float,
    width: Float,
    ink: ChartInk,
    text: ChartText,
    label: String,
) {
    drawLine(ink.hairline, Offset(0f, y), Offset(width, y), 1.dp.toPx())
    val layout = text.measure(label)
    drawText(layout, topLeft = Offset(size.width - layout.size.width, y - layout.size.height / 2))
}

/** A dashed rule in `text-secondary` with its words over its start. */
private fun DrawScope.rule(
    y: Float,
    width: Float,
    ink: ChartInk,
    text: ChartText,
    label: String?,
) {
    val dashed = PathEffect.dashPathEffect(floatArrayOf(Dash.toPx(), DashGap.toPx()))
    drawLine(ink.axis, Offset(0f, y), Offset(width, y), 1.dp.toPx(), pathEffect = dashed)
    label?.let {
        val layout = text.measure(it)
        drawText(layout, topLeft = Offset(0f, y - layout.size.height - LabelGap.toPx() / 2))
    }
}

private fun DrawScope.series(
    points: List<Offset>,
    ink: ChartInk,
) {
    if (points.size < 2) return
    val path =
        Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
    drawPath(path, ink.line, style = Stroke(InkWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
import kotlin.math.abs
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

/**
 * Where a charge in progress reaches [targetPct] (SPEC 10.9, ADR-0046 §6): between [earliestMs] and
 * [latestMs], drawn as the copper cone and bracket of DESIGN.md's signature, labelled [label]
 * ("19:06–19:10"). The target is a marker rule with its value at the end, as [CurveLabels.percent].
 */
@Immutable
public data class CurveProjection(
    val targetPct: Int,
    val earliestMs: Long,
    val latestMs: Long,
    val label: String,
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
private const val MAX_POINTS = 800
private val BracketWidth = 3.dp
private val MarkerClearance = 2.dp
private val BracketStop = 7.dp
private val EdgeWidth = 1.dp
private const val TINT = 0.14f

/**
 * A charge session's curve (SPEC 10.8 item 6): the level from 0 to 100% over the session, and
 * under it the power into the battery, sharing the time axis. Where the current tapered, the
 * level panel has a dashed rule at [taperLevel]; the power panel one at [fullSpeedW]. Readings
 * without power leave the power line broken there, so a gap never reads as zero. Values on the
 * right in Caption, the session's start and end clock under the axis. A [projection] runs the time
 * axis on to its latest end and draws the cone from the present reading to the target; [endMs], when
 * later, holds the axis's end still between readings. Draws in once; TalkBack reads [summary] and
 * offers "View as table".
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
    projection: CurveProjection? = null,
    endMs: Long? = null,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val style = remember(type, colors) { type.caption.copy(color = colors.textSecondary) }
    val strongStyle = remember(type, colors) { type.captionEmphasis.copy(color = colors.textPrimary) }
    // Room for every label the chart draws, so a draw-in frame lays out none again.
    val measurer = rememberTextMeasurer(cacheSize = LABEL_CACHE)
    val labelHeight = remember(measurer, style) { measurer.measure("0", style).size.height }
    // A long charge at 1 Hz has thousands of readings; a phone's width shows a few hundred.
    val shown = remember(readings) { thinned(readings) }
    val hasPower = shown.any { it.watts != null }
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
        val readings = shown
        if (readings.size < 2) return@Canvas
        val text = ChartText(measurer, style)
        val wattTop = wattTop(readings)
        val width = size.width - valueWidth(text, labels, wattTop.takeIf { hasPower })
        val start = readings.first().wallMs
        val end = axisEnd(readings, projection, endMs)

        fun x(ms: Long) = (ms - start).toFloat() / (end - start) * width
        val levelTop = labelHeight + LabelGap.toPx()
        val levelBottom = levelTop + LevelHeight.toPx()
        val level = Panel(levelTop, levelBottom, FULL.toDouble())
        val strong = ChartText(measurer, strongStyle)
        // A grid line under the bracket's words breaks around them rather than striking them through.
        val gap = labelBox(projection, strong, ::x, level, width)
        levelGrid(level, width, ink, Values(text, strong, labels), projection?.targetPct, gap)
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
                // The line meets the cone with no dot (DESIGN.md, as `ProjectionScale`).
                projection?.let { p ->
                    val last = readings.last()
                    val apex = Offset(x(last.wallMs), level.y(last.levelPct.toDouble()))
                    drawProjection(p, apex, x(p.earliestMs) to x(p.latestMs), level, ink, strong, width)
                }
            }
        }
    }
}

/**
 * At most about [MAX_POINTS] readings: in each run of readings, the ones with the lowest and the
 * highest power, plus the first without power so a gap stays a gap. Ends kept as they are.
 */
internal fun thinned(readings: List<CurveReading>): List<CurveReading> {
    if (readings.size <= MAX_POINTS) return readings
    val run = ceil(readings.size / (MAX_POINTS / 2.0)).toInt()
    val inner =
        readings.drop(1).dropLast(1).chunked(run).flatMap { chunk ->
            val powered = chunk.filter { it.watts != null }
            val picks =
                listOfNotNull(
                    powered.minByOrNull { checkNotNull(it.watts) },
                    powered.maxByOrNull { checkNotNull(it.watts) },
                    chunk.firstOrNull { it.watts == null },
                ).ifEmpty { listOf(chunk.first()) }
            picks.distinct().sortedBy { it.wallMs }
        }
    return listOf(readings.first()) + inner + readings.last()
}

/** The time axis's end: the last reading, or later for a projection's latest end or a given end. */
private fun axisEnd(
    readings: List<CurveReading>,
    projection: CurveProjection?,
    endMs: Long?,
): Long = maxOf(readings.last().wallMs, projection?.latestMs ?: 0L, endMs ?: 0L).coerceAtLeast(readings.first().wallMs + 1)

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

/** The values column's width: the widest of 100% and, with power, the power axis's top. */
private fun DrawScope.valueWidth(
    text: ChartText,
    labels: CurveLabels,
    wattTop: Double?,
): Float =
    maxOf(text.measure(labels.percent(FULL)).size.width, wattTop?.let { text.measure(labels.watts(it)).size.width } ?: 0) + ValueGap.toPx()

/** The value column's two text styles and the words they set. */
private class Values(
    val text: ChartText,
    val strong: ChartText,
    val labels: CurveLabels,
)

/**
 * The level panel's 0, 50 and 100% lines, and the target's marker in place of the line it falls on.
 * A line too close to the marker keeps its hairline but not its value, so the two never overlap.
 */
private fun DrawScope.levelGrid(
    level: Panel,
    width: Float,
    ink: ChartInk,
    values: Values,
    targetPct: Int?,
    gap: Rect?,
) {
    val targetY = targetPct?.let { level.y(it.toDouble()) }
    val clearance =
        values.text
            .measure(values.labels.percent(FULL))
            .size.height + MarkerClearance.toPx()
    listOf(0, HALF, FULL).filter { it != targetPct }.forEach { pct ->
        val y = level.y(pct.toDouble())
        hairline(y, width, ink, gap)
        if (targetY == null || abs(y - targetY) >= clearance) {
            val layout = values.text.measure(values.labels.percent(pct))
            drawText(layout, topLeft = Offset(size.width - layout.size.width, y - layout.size.height / 2))
        }
    }
    targetPct?.let { marker(level.y(it.toDouble()), width, ink, values.strong, values.labels.percent(it)) }
}

/** A grid hairline at [y], broken around [gap] where it crosses it. */
private fun DrawScope.hairline(
    y: Float,
    width: Float,
    ink: ChartInk,
    gap: Rect?,
) {
    val stroke = 1.dp.toPx()
    if (gap == null || y < gap.top || y > gap.bottom) {
        drawLine(ink.hairline, Offset(0f, y), Offset(width, y), stroke)
        return
    }
    val margin = LabelGap.toPx()
    if (gap.left - margin > 0f) drawLine(ink.hairline, Offset(0f, y), Offset(gap.left - margin, y), stroke)
    if (gap.right + margin < width) drawLine(ink.hairline, Offset(gap.right + margin, y), Offset(width, y), stroke)
}

/** Where [bracketLabel] sets a projection's words, or null without one. */
private fun DrawScope.labelBox(
    projection: CurveProjection?,
    strong: ChartText,
    x: (Long) -> Float,
    level: Panel,
    width: Float,
): Rect? {
    val p = projection ?: return null
    val label = strong.measure(p.label).size
    val topLeft = bracketLabelTopLeft(label.width, label.height, x(p.earliestMs) to x(p.latestMs), level.y(p.targetPct.toDouble()), width)
    return Rect(topLeft, Size(label.width.toFloat(), label.height.toFloat()))
}

/** The cone from the present reading at [apex] to the target across [span], with its bracket and words. */
@Suppress("LongParameterList") // The estimate, where it sits and how it is drawn.
private fun DrawScope.drawProjection(
    projection: CurveProjection,
    apex: Offset,
    span: Pair<Float, Float>,
    level: Panel,
    ink: ChartInk,
    strong: ChartText,
    width: Float,
) {
    val (from, to) = span
    val y = level.y(projection.targetPct.toDouble())
    cone(apex, from, to, y, ink)
    bracketLabel(strong.measure(projection.label), from, to, y, width)
}

/** The target's marker: a solid rule in `text-secondary` with its value, emphasised, at the end. */
private fun DrawScope.marker(
    y: Float,
    width: Float,
    ink: ChartInk,
    strong: ChartText,
    label: String,
) {
    drawLine(ink.axis, Offset(0f, y), Offset(width, y), 1.dp.toPx())
    val layout = strong.measure(label)
    drawText(layout, topLeft = Offset(size.width - layout.size.width, y - layout.size.height / 2))
}

/** A 14% copper cone with dashed 1 dp edges from [apex] to the target, and the bracket over its span. */
@Suppress("LongParameterList") // The apex, the span and the rule it closes on.
private fun DrawScope.cone(
    apex: Offset,
    from: Float,
    to: Float,
    y: Float,
    ink: ChartInk,
) {
    val cone =
        Path().apply {
            moveTo(apex.x, apex.y)
            lineTo(from, y)
            lineTo(to, y)
            close()
        }
    drawPath(cone, ink.accent.copy(alpha = TINT))
    val dashed = PathEffect.dashPathEffect(floatArrayOf(Dash.toPx(), DashGap.toPx()))
    drawLine(ink.accent, apex, Offset(from, y), EdgeWidth.toPx(), pathEffect = dashed)
    drawLine(ink.accent, apex, Offset(to, y), EdgeWidth.toPx(), pathEffect = dashed)
    val stop = BracketStop.toPx()
    val bracket =
        Path().apply {
            moveTo(from, y - stop)
            lineTo(from, y)
            lineTo(to, y)
            lineTo(to, y - stop)
        }
    drawPath(bracket, ink.accent, style = Stroke(BracketWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The bracket's words centred over it, kept inside the plot. */
private fun DrawScope.bracketLabel(
    layout: TextLayoutResult,
    from: Float,
    to: Float,
    y: Float,
    width: Float,
) {
    drawText(layout, topLeft = bracketLabelTopLeft(layout.size.width, layout.size.height, from to to, y, width))
}

/** The bracket's words centred over [span], kept inside the plot, above the bracket's stops at [y]. */
private fun DrawScope.bracketLabelTopLeft(
    labelWidth: Int,
    labelHeight: Int,
    span: Pair<Float, Float>,
    y: Float,
    width: Float,
): Offset {
    val left = ((span.first + span.second) / 2 - labelWidth / 2f).coerceIn(0f, (width - labelWidth).coerceAtLeast(0f))
    return Offset(left, y - BracketStop.toPx() - LabelGap.toPx() - labelHeight)
}

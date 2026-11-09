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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/** One battery level reading on a [ProjectionScale]. */
@Immutable
public data class LevelMark(
    val wallMs: Long,
    val levelPct: Float,
    /** The reading follows a gap, such as a reboot: the line breaks before it. */
    val startsSegment: Boolean = false,
)

/** When the battery reaches empty, as Coulomb's estimate: a range of clock times and its label. */
@Immutable
public data class EmptyRange(
    val fromMs: Long,
    val toMs: Long,
    /** The range as text, such as "20:10–21:40". */
    val label: String,
)

/** The words a [ProjectionScale] draws: "now", percentages and clock times. */
@Immutable
public data class ScaleLabels(
    val now: String,
    val percent: (Int) -> String,
    val time: (Long) -> String,
)

private val PlotHeight = 104.dp
private val LabelGap = 2.dp
private val AxisLabelGap = 5.dp
private val Tick = 3.dp
private val InkWidth = 1.5.dp
private val BracketWidth = 3.dp
private val BracketStop = 7.dp
private val EdgeWidth = 1.dp
private val Dash = 2.dp
private val DashGap = 3.dp
private val LabelSpacing = 8.dp
private val MinTickSpacing = 12.dp

/** The bracket, or "now" without one, ends this far across the plot (the lab's 78%). */
private const val RIGHT_END = 0.78f
private const val TINT = 0.14f
private const val FULL = 100f
private const val QUARTER = 25
private const val MS_PER_HOUR = 3_600_000L
private val TICK_HOURS = listOf(1, 2, 3, 4, 6, 12, 24)

/**
 * DESIGN.md's signature: the bracket on a graduated scale, as Now draws it (SPEC 10.2).
 * - The level line runs from the unplug ([startMs]) to "now", in ink. It ends at the "now" rule
 *   with no dot, and a reading that does not reach back to the unplug leaves a gap.
 * - From the present reading a 14% copper cone with dashed 1 dp edges narrows into the copper
 *   bracket on the time axis: [empty], the estimate of when the battery reaches 0%.
 * - Hairline gridlines at quarter levels, percentages and clock times in Caption, and the range
 *   label in Caption at weight 600. Labels keep their size; the plot follows the width.
 *
 * TalkBack reads [summary], and the "View as table" action ([viewTableLabel]) opens the table.
 */
@Composable
@Suppress("LongParameterList") // One chart's data, labels and accessibility; grouping them would only rename them.
public fun ProjectionScale(
    levels: List<LevelMark>,
    startMs: Long,
    nowMs: Long,
    levelPct: Float,
    empty: EmptyRange?,
    labels: ScaleLabels,
    summary: String,
    viewTableLabel: String,
    onViewTable: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val colors = CoulombTheme.colors
    val caption = CoulombTheme.typography.caption
    val style = remember(caption, colors) { caption.copy(color = colors.textSecondary) }
    val strong = remember(caption, colors) { caption.copy(color = colors.textPrimary, fontWeight = FontWeight.SemiBold) }
    // Up to 13 labels draw each frame; the default cache of 8 would re-measure them.
    val measurer = rememberTextMeasurer(cacheSize = LABEL_CACHE)
    val labelHeight = remember(measurer, style) { measurer.measure("100%", style).size.height }
    val ink = ChartInk(hairline = colors.hairline, axis = colors.textSecondary, line = colors.textPrimary, accent = colors.accent)
    val density = LocalDensity.current
    val height = with(density) { labelHeight.toDp() * 2 + LabelGap * 2 + PlotHeight + AxisLabelGap }
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
        val top = labelHeight + LabelGap.toPx()
        val axis = top + PlotHeight.toPx()
        val start = minOf(startMs, nowMs - MS_PER_HOUR)
        val end = start + (maxOf(empty?.toMs ?: nowMs, nowMs) - start) / RIGHT_END
        val scale = Scale(start, end.toLong(), size.width, top, axis)

        val text = ChartText(measurer, style)
        grid(scale, ink, labels, text, nowMs)
        val reserved = mutableListOf<ClosedFloatingPointRange<Float>>()
        with(drawIn) {
            if (empty != null) revealed { projection(scale, ink, nowMs, levelPct, empty, text.copy(style = strong), reserved) }
            nowLabel(scale, nowMs, labels.now, text, reserved)
            timeAxis(scale, ink, labels, text, zone, reserved)
            revealed { levelLine(scale, ink, levels, nowMs, levelPct) }
        }
    }
}

private class Scale(
    val startMs: Long,
    val endMs: Long,
    val width: Float,
    val top: Float,
    val axis: Float,
) {
    fun x(ms: Long): Float = ((ms - startMs).toFloat() / (endMs - startMs)) * width

    fun y(pct: Float): Float = axis - (pct.coerceIn(0f, FULL) / FULL) * (axis - top)
}

private fun DrawScope.grid(
    scale: Scale,
    ink: ChartInk,
    labels: ScaleLabels,
    text: ChartText,
    nowMs: Long,
) {
    for (pct in QUARTER..FULL.toInt() step QUARTER) {
        val y = scale.y(pct.toFloat())
        drawLine(ink.hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
    }
    // At large text the quarter labels would touch; then only 0, 50 and 100% keep theirs.
    val quarterGap = scale.y(0f) - scale.y(QUARTER.toFloat())
    val every = if (text.measure(labels.percent(FULL.toInt())).size.height + LabelSpacing.toPx() > quarterGap) 2 * QUARTER else QUARTER
    for (pct in 0..FULL.toInt() step every) {
        val label = text.measure(labels.percent(pct))
        val y = scale.y(pct.toFloat())
        drawText(label, topLeft = Offset(size.width - label.size.width, y - label.size.height - LabelGap.toPx()))
    }
    // The "now" rule: a dashed hairline across the plot.
    val now = scale.x(nowMs)
    drawLine(
        ink.hairline,
        Offset(now, scale.top),
        Offset(now, scale.axis),
        1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(Dash.toPx(), DashGap.toPx())),
    )
    drawLine(ink.axis, Offset(0f, scale.axis), Offset(size.width, scale.axis), 1.dp.toPx())
}

@Suppress("LongParameterList") // The scale, its colours and text, and the estimate to draw on it.
private fun DrawScope.projection(
    scale: Scale,
    ink: ChartInk,
    nowMs: Long,
    levelPct: Float,
    empty: EmptyRange,
    strong: ChartText,
    reserved: MutableList<ClosedFloatingPointRange<Float>>,
) {
    val accent = ink.accent
    val apex = Offset(scale.x(nowMs), scale.y(levelPct))
    val from = scale.x(empty.fromMs)
    val to = scale.x(empty.toMs)
    val cone =
        Path().apply {
            moveTo(apex.x, apex.y)
            lineTo(from, scale.axis)
            lineTo(to, scale.axis)
            close()
        }
    drawPath(cone, accent.copy(alpha = TINT))
    val dashed = PathEffect.dashPathEffect(floatArrayOf(Dash.toPx(), DashGap.toPx()))
    drawLine(accent, apex, Offset(from, scale.axis), EdgeWidth.toPx(), pathEffect = dashed)
    drawLine(accent, apex, Offset(to, scale.axis), EdgeWidth.toPx(), pathEffect = dashed)
    val stop = BracketStop.toPx()
    val bracket =
        Path().apply {
            moveTo(from, scale.axis - stop)
            lineTo(from, scale.axis)
            lineTo(to, scale.axis)
            lineTo(to, scale.axis - stop)
        }
    drawPath(bracket, accent, style = Stroke(BracketWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    val label = strong.measure(empty.label)
    reserved +=
        placeLabel(label.size.width.toFloat(), (from + to) / 2).also { span ->
            drawText(label, topLeft = Offset(span.start, scale.axis + AxisLabelGap.toPx()))
        }
}

private fun DrawScope.nowLabel(
    scale: Scale,
    nowMs: Long,
    now: String,
    text: ChartText,
    reserved: MutableList<ClosedFloatingPointRange<Float>>,
) {
    val label = text.measure(now)
    val span = placeLabel(label.size.width.toFloat(), scale.x(nowMs))
    if (reserved.none { it.overlaps(span, LabelSpacing.toPx()) }) {
        drawText(label, topLeft = Offset(span.start, scale.axis + AxisLabelGap.toPx()))
        reserved += span
    }
}

/** Hour ticks on the axis, and clock labels wherever they fit beside "now" and the range. */
@Suppress("LongParameterList") // The scale, its colours, words and text, the clock and the labels already placed.
private fun DrawScope.timeAxis(
    scale: Scale,
    ink: ChartInk,
    labels: ScaleLabels,
    text: ChartText,
    zone: ZoneId,
    reserved: MutableList<ClosedFloatingPointRange<Float>>,
) {
    val hourPx = scale.width * MS_PER_HOUR / (scale.endMs - scale.startMs).toFloat()
    val tickHours = TICK_HOURS.firstOrNull { it * hourPx >= MinTickSpacing.toPx() } ?: TICK_HOURS.last()
    val labelWidth = text.measure(labels.time(scale.startMs)).size.width + LabelSpacing.toPx()
    val labelHours = TICK_HOURS.filter { it % tickHours == 0 }.firstOrNull { it * hourPx >= labelWidth } ?: TICK_HOURS.last()
    val first =
        Instant
            .ofEpochMilli(scale.startMs)
            .atZone(zone)
            .truncatedTo(ChronoUnit.HOURS)
            .plusHours(1)
    val count = ceil((scale.endMs - scale.startMs).toDouble() / MS_PER_HOUR).toInt()
    List(count + 1) { first.plusHours(it.toLong()) }
        .filter { it.hour % tickHours == 0 }
        .takeWhile { scale.x(it.toInstant().toEpochMilli()) <= scale.width }
        .forEach { time ->
            val ms = time.toInstant().toEpochMilli()
            val x = scale.x(ms)
            drawLine(ink.axis, Offset(x, scale.axis), Offset(x, scale.axis + Tick.toPx()), 1.dp.toPx())
            if (time.hour % labelHours == 0) {
                val label = text.measure(labels.time(ms))
                val span = placeLabel(label.size.width.toFloat(), x)
                if (reserved.none { it.overlaps(span, LabelSpacing.toPx()) }) {
                    drawText(label, topLeft = Offset(span.start, scale.axis + AxisLabelGap.toPx()))
                    reserved += span
                }
            }
        }
}

private fun DrawScope.levelLine(
    scale: Scale,
    ink: ChartInk,
    levels: List<LevelMark>,
    nowMs: Long,
    levelPct: Float,
) {
    val points = levels.filter { it.wallMs in scale.startMs..nowMs }
    if (points.isEmpty()) return
    val tail = if (points.last().wallMs < nowMs) points + LevelMark(nowMs, levelPct) else points
    val path = Path()
    tail.forEachIndexed { index, mark ->
        val point = Offset(scale.x(mark.wallMs), scale.y(mark.levelPct))
        if (index == 0 || mark.startsSegment) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    drawPath(path, ink.line, style = Stroke(InkWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

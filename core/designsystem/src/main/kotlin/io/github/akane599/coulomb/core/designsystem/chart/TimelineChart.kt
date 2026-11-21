// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombColors
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import kotlin.math.abs

/** The colour of a track's marks: a power component's hue, copper for charging, or neutral ink (DESIGN.md, Data palette). */
public enum class TrackInk { SCREEN, CHARGE, NEUTRAL, CPU, RADIO, WIFI, GNSS }

/** One stretch of a track. A [light] state, such as light Doze, is a tint with an outline. */
@Immutable
public data class TrackMark(
    val fromMs: Long,
    val toMs: Long,
    val light: Boolean = false,
)

/** A track's value at a time, as a share of the band's height from 0 (bottom) to 1. */
@Immutable
public data class TrackValue(
    val atMs: Long,
    val share: Float,
)

/**
 * One band under the level: [marks] for a state that is on or off, or [line] for a value such as
 * the signal ([stepped]) or the temperature. [spaced] starts a new group of bands.
 */
@Immutable
public data class TimelineBand(
    val label: String,
    val ink: TrackInk,
    val marks: List<TrackMark> = emptyList(),
    val line: List<TrackValue> = emptyList(),
    val stepped: Boolean = false,
    val spaced: Boolean = false,
)

/** Everything a [TimelineChart] can draw; the chart shows the part inside its window. */
@Immutable
public data class TimelineSeries(
    val level: List<LevelMark>,
    /** Empty leaves the current panel out. */
    val current: List<CurrentMark>,
    val bands: List<TimelineBand>,
    /** Stretches without history: the bands are blank there, which is not "off". */
    val unknown: List<TrackMark> = emptyList(),
)

/** A labelled time on the axis. */
@Immutable
public data class TimeTick(
    val atMs: Long,
    val text: String,
)

/** The words a [TimelineChart] draws beside its panels. */
@Immutable
public data class TimelineLabels(
    val level: String,
    val current: String,
    val percent: (Int) -> String,
    /** A current on the axis with its sign and unit, "+1.5 A"; zero is "0". */
    val amps: (Double) -> String,
)

/**
 * How a [TimelineChart] is moved (SPEC 10.6). [onTransform]: two fingers zoom by a factor about
 * [focus], a share of the plot's width, and pan by a share of it. [onScrub]: one finger dragged
 * sideways moves the cursor to a time. [onTap]: a tap on a band's index, or null above the bands.
 */
@Immutable
public class TimelineGestures(
    public val onTransform: (zoom: Float, focus: Float, pan: Float) -> Unit,
    public val onScrub: (atMs: Long) -> Unit,
    public val onTap: (band: Int?, atMs: Long) -> Unit,
)

private val TopPad = 8.dp
private val LevelHeight = 72.dp
private val PanelGap = 24.dp
private val CurrentHeight = 56.dp
private val BandHeight = 12.dp
private val BandGap = 10.dp
private val GroupGap = 6.dp
private val BandRadius = 3.dp
private val LabelGap = 8.dp
private val StackGap = 2.dp
private val AxisTick = 4.dp
private val AxisGap = 4.dp
private val InkWidth = 2.dp
private val LineWidth = 1.25.dp
private val TickWidth = 1.5.dp
private val Outline = 1.dp
private val MinSpan = 3.dp
private val Merge = 3.dp
private val LineInset = 2.dp

/** The label column may take this share of the width; past it each band's label sits above it. */
private const val MAX_LABEL_SHARE = 0.34f
private const val FULL = 100

/** A panel's title sits a quarter of the way down it. */
private const val TITLE_AT = 0.25f
private const val HALF = 50
private const val TRACK_BG = 0.08f
private const val LIGHT = 0.20f
private const val DISCHARGE_TINT = 0.30f
private const val CHARGE_TINT = 0.45f

/** Current readings further apart than this share of the window are a gap, not a line. */
private const val CURRENT_GAP_SHARE = 0.02
private const val MIN_CURRENT_GAP_MS = 90_000L

/**
 * SPEC 10.6's timeline, drawn as the design lab's: the level as a line over quarter gridlines, the
 * current as an area (discharge below the axis in neutral ink, charge above in copper, each on a
 * solid edge), then one 12 dp band per track with its label beside it, and the time axis. A track
 * mark too short to have a width is a tick; ticks closer than 3 dp merge into a density span, a
 * 20% tint with a 1 dp outline, which zooming in splits again (ADR-0023). Light states are the
 * same tint with an outline, strong ones solid. Bands are blank where the history is unknown.
 *
 * The chart shows [fromMs] to [toMs] of [series]; the caller owns that window and moves it from
 * [gestures]. At large text the band labels move above their bands, so the plot keeps its width.
 * [cursorMs] draws the scrub rule. TalkBack reads [summary] and offers "View as table".
 */
@Composable
@Suppress("LongParameterList") // One chart's data, window, labels, cursor, gestures and accessibility.
public fun TimelineChart(
    series: TimelineSeries,
    fromMs: Long,
    toMs: Long,
    ticks: List<TimeTick>,
    labels: TimelineLabels,
    summary: String,
    viewTableLabel: String,
    onViewTable: () -> Unit,
    modifier: Modifier = Modifier,
    cursorMs: Long? = null,
    gestures: TimelineGestures? = null,
) {
    val colors = CoulombTheme.colors
    val caption = CoulombTheme.typography.caption
    val emphasis = CoulombTheme.typography.captionEmphasis
    val measurer = rememberTextMeasurer(LABEL_CACHE * 2)
    val text = remember(measurer, caption, colors) { ChartText(measurer, caption.copy(color = colors.textSecondary)) }
    val strong = remember(measurer, emphasis, colors) { ChartText(measurer, emphasis.copy(color = colors.textPrimary)) }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val range = remember(series.current) { NiceRange.of(series.current.map { it.currentMa }) }
    val drawIn = rememberDrawIn()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.toFloat()
        val layout =
            remember(series.bands.map { it.label to it.spaced }, series.current.isEmpty(), labels, range, width, density, text, strong) {
                TimelineLayout.of(series, labels, range, width, density, text, strong)
            }
        val window = rememberUpdatedState(fromMs..toMs)
        val moves = rememberUpdatedState(gestures)
        Canvas(
            with(drawIn) { Modifier.fade() }
                .fillMaxWidth()
                .height(with(density) { layout.height.toDp() })
                .clearAndSetSemantics {
                    contentDescription = summary
                    customActions = listOf(CustomAccessibilityAction(viewTableLabel) { onViewTable().let { true } })
                }.then(
                    if (gestures ==
                        null
                    ) {
                        Modifier
                    } else {
                        Modifier.pointerInput(layout, rtl) { timelineGestures(layout, rtl, { window.value }) { moves.value } }
                    },
                ),
        ) {
            val scale = TimeScale(fromMs, toMs, layout.plotLeft, layout.plotRight, rtl, size.width)
            val ink = TimelineInk(colors)
            ticks.forEach { tick ->
                val x = scale.x(tick.atMs)
                if (x in scale.left..scale.right) drawLine(colors.hairline, Offset(x, 0f), Offset(x, layout.axisY), Outline.toPx())
            }
            levelPanel(layout, scale, series.level, labels, text, strong, ink, drawIn)
            if (layout.currentTop != null) currentPanel(layout, scale, series.current, range, labels, text, strong, ink, drawIn)
            series.bands.forEachIndexed { index, band -> band(layout, layout.bands[index], scale, band, series.unknown, text, ink, drawIn) }
            axis(layout, scale, ticks, text, ink)
            cursorMs?.let { at ->
                val x = scale.x(at)
                if (x in scale.left..scale.right) drawLine(colors.textPrimary, Offset(x, 0f), Offset(x, layout.axisY), Outline.toPx())
            }
        }
    }
}

/** Where each part of the chart sits, in pixels; shared by drawing and hit-testing. */
@Suppress("LongParameterList") // One place per part of the chart.
internal class TimelineLayout(
    val plotLeft: Float,
    val plotRight: Float,
    val levelTop: Float,
    val levelBottom: Float,
    val currentTop: Float?,
    val currentBottom: Float?,
    val bands: List<BandPlace>,
    val axisY: Float,
    val height: Float,
    /** Band labels sit above their bands. */
    val stacked: Boolean,
) {
    class BandPlace(
        val labelTop: Float,
        val top: Float,
        val bottom: Float,
    )

    /** The band at [y], or null above the first. */
    fun bandAt(y: Float): Int? = bands.indexOfLast { y >= it.labelTop }.takeIf { it >= 0 && y <= axisY }

    companion object {
        @Suppress("LongParameterList") // The chart's content, words, scale, width and text.
        fun of(
            series: TimelineSeries,
            labels: TimelineLabels,
            range: NiceRange,
            width: Float,
            density: Density,
            text: ChartText,
            strong: ChartText,
        ): TimelineLayout =
            with(density) {
                val line =
                    text
                        .measure("0")
                        .size.height
                        .toFloat()
                val hasCurrent = series.current.isNotEmpty()
                val values =
                    listOf(labels.percent(FULL), labels.percent(HALF), labels.percent(0)) +
                        if (hasCurrent) listOf(labels.amps(range.max), labels.amps(range.min)) else emptyList()
                val valueWidth = values.maxOf { text.measure(it).size.width }
                val titles = listOf(labels.level) + if (hasCurrent) listOf(labels.current) else emptyList()
                val titleWidth = titles.maxOf { strong.measure(it).size.width }
                val bandWidth = series.bands.maxOfOrNull { text.measure(it.label).size.width } ?: 0
                val beside = maxOf(valueWidth, titleWidth, bandWidth) + LabelGap.toPx()
                val stacked = beside > width * MAX_LABEL_SHARE
                // Stacked, the panels' titles sit above them too, and only the values keep the column.
                val left = if (stacked) valueWidth + LabelGap.toPx() else beside
                var y = TopPad.toPx() + if (stacked) line + StackGap.toPx() else 0f
                val levelTop = y
                y += LevelHeight.toPx()
                val levelBottom = y
                var currentTop: Float? = null
                var currentBottom: Float? = null
                if (hasCurrent) {
                    y += PanelGap.toPx() + if (stacked) line + StackGap.toPx() else 0f
                    currentTop = y
                    y += CurrentHeight.toPx()
                    currentBottom = y
                }
                y += PanelGap.toPx() - BandGap.toPx()
                val pitch = maxOf(BandHeight.toPx(), if (stacked) 0f else line)
                val places =
                    series.bands.map { band ->
                        y += BandGap.toPx() + if (band.spaced) GroupGap.toPx() else 0f
                        val labelTop = y
                        if (stacked) y += line + StackGap.toPx()
                        val top = y + (pitch - BandHeight.toPx()) / 2
                        y += pitch
                        BandPlace(labelTop, top, top + BandHeight.toPx())
                    }
                val axisY = y + BandGap.toPx()
                TimelineLayout(
                    left,
                    width,
                    levelTop,
                    levelBottom,
                    currentTop,
                    currentBottom,
                    places,
                    axisY,
                    axisY + AxisTick.toPx() + AxisGap.toPx() + line,
                    stacked,
                )
            }
    }
}

/** Time to pixels across the plot; mirrored in a right-to-left layout, where time still runs from the start edge. */
internal class TimeScale(
    private val fromMs: Long,
    private val toMs: Long,
    plotStart: Float,
    plotEnd: Float,
    private val rtl: Boolean,
    private val width: Float,
) {
    val left: Float = if (rtl) width - plotEnd else plotStart
    val right: Float = if (rtl) width - plotStart else plotEnd
    private val span = (toMs - fromMs).coerceAtLeast(1).toDouble()

    fun x(ms: Long): Float {
        val share = ((ms - fromMs) / span).toFloat()
        return if (rtl) right - share * (right - left) else left + share * (right - left)
    }

    /**
     * The part of [marks], in time order and not overlapping, that touches the window: a week of
     * wakeups is not placed to draw fifteen minutes of them (SPEC 10.6: virtualized by range).
     */
    fun inWindow(marks: List<TrackMark>): List<TrackMark> {
        val first = marks.binarySearch { if (it.toMs < fromMs) -1 else 1 }.let { -it - 1 }
        val end = marks.binarySearch { if (it.fromMs <= toMs) -1 else 1 }.let { -it - 1 }
        return if (first < end) marks.subList(first, end) else emptyList()
    }

    /** The time at pixel [x], kept inside the window. */
    fun at(x: Float): Long {
        val share = ((if (rtl) right - x else x - left) / (right - left)).coerceIn(0f, 1f)
        return fromMs + (share * span).toLong()
    }

    /** [x] as a share of the plot's width from the start of time. */
    fun share(x: Float): Float = ((if (rtl) right - x else x - left) / (right - left)).coerceIn(0f, 1f)

    /** The x where a label of [labelWidth] beside the plot starts, in the label column. */
    fun columnStart(labelWidth: Int): Float = if (rtl) width - labelWidth else 0f

    /** The x where a value label of [labelWidth] ends against the plot. */
    fun valueStart(
        labelWidth: Int,
        gap: Float,
    ): Float = if (rtl) right + gap else left - gap - labelWidth
}

private class TimelineInk(
    val colors: CoulombColors,
) {
    fun of(ink: TrackInk): Color =
        when (ink) {
            TrackInk.SCREEN -> colors.dataScreen
            TrackInk.CHARGE -> colors.accent
            TrackInk.NEUTRAL -> colors.textSecondary
            TrackInk.CPU -> colors.dataCpu
            TrackInk.RADIO -> colors.dataRadio
            TrackInk.WIFI -> colors.dataWifi
            TrackInk.GNSS -> colors.dataGnss
        }
}

@Suppress("LongParameterList") // A panel's place, scale, data, words and inks.
private fun DrawScope.levelPanel(
    layout: TimelineLayout,
    scale: TimeScale,
    level: List<LevelMark>,
    labels: TimelineLabels,
    text: ChartText,
    strong: ChartText,
    ink: TimelineInk,
    drawIn: DrawIn,
) {
    val top = layout.levelTop
    val bottom = layout.levelBottom

    fun y(percent: Int) = bottom - percent / FULL.toFloat() * (bottom - top)
    listOf(FULL, HALF, 0).forEach { percent ->
        drawLine(ink.colors.hairline, Offset(scale.left, y(percent)), Offset(scale.right, y(percent)), Outline.toPx())
        if (!layout.stacked || percent != FULL) value(scale, text.measure(labels.percent(percent)), y(percent), top, bottom)
    }
    title(layout, scale, strong.measure(labels.level), top, bottom)
    if (level.isEmpty()) return
    val path = Path()
    level.forEachIndexed { index, mark ->
        val x = scale.x(mark.wallMs)
        val at = bottom - mark.levelPct / FULL * (bottom - top)
        if (index == 0 || mark.startsSegment) path.moveTo(x, at) else path.lineTo(x, at)
    }
    with(drawIn) {
        revealed {
            clipRect(left = scale.left, right = scale.right, top = top - InkWidth.toPx(), bottom = bottom + InkWidth.toPx()) {
                drawPath(path, ink.colors.textPrimary, style = Stroke(InkWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Suppress("LongParameterList") // A panel's place, scale, data, words and inks.
private fun DrawScope.currentPanel(
    layout: TimelineLayout,
    scale: TimeScale,
    current: List<CurrentMark>,
    range: NiceRange,
    labels: TimelineLabels,
    text: ChartText,
    strong: ChartText,
    ink: TimelineInk,
    drawIn: DrawIn,
) {
    val top = layout.currentTop ?: return
    val bottom = layout.currentBottom ?: return

    fun y(ma: Double) = (top + (range.max - ma) / (range.max - range.min) * (bottom - top)).toFloat()
    val zero = y(0.0)
    drawLine(ink.colors.textSecondary, Offset(scale.left, zero), Offset(scale.right, zero), Outline.toPx())
    // Stacked, the panel's title sits right above it: the end values stay inside the panel.
    if (range.max > 0) value(scale, text.measure(labels.amps(range.max)), top, top, bottom, inside = layout.stacked)
    if (range.min < 0) value(scale, text.measure(labels.amps(range.min)), bottom, top, bottom, inside = layout.stacked)
    title(layout, scale, strong.measure(labels.current), top, bottom)
    val gap = maxOf(MIN_CURRENT_GAP_MS, ((scale.at(scale.right) - scale.at(scale.left)).let { abs(it) } * CURRENT_GAP_SHARE).toLong())
    val runs = ArrayList<MutableList<CurrentMark>>()
    current.forEach { mark ->
        if (runs.isEmpty() ||
            mark.wallMs - runs.last().last().wallMs > gap
        ) {
            runs += mutableListOf(mark)
        } else {
            runs.last() += mark
        }
    }
    with(drawIn) {
        revealed {
            clipRect(left = scale.left, right = scale.right) {
                for (run in runs) {
                    val area = Path().apply { moveTo(scale.x(run.first().wallMs), zero) }
                    val edge = Path()
                    run.forEachIndexed { index, mark ->
                        val point = Offset(scale.x(mark.wallMs), y(mark.currentMa))
                        area.lineTo(point.x, point.y)
                        if (index == 0) edge.moveTo(point.x, point.y) else edge.lineTo(point.x, point.y)
                    }
                    area.lineTo(scale.x(run.last().wallMs), zero)
                    area.close()
                    val stroke = Stroke(Outline.toPx(), join = StrokeJoin.Round)
                    // One area, split at the zero line: charge above in copper, discharge below in neutral ink.
                    clipRect(left = scale.left, right = scale.right, bottom = zero) {
                        drawPath(area, ink.colors.accent.copy(alpha = CHARGE_TINT))
                        drawPath(edge, ink.colors.accent, style = stroke)
                    }
                    clipRect(left = scale.left, right = scale.right, top = zero) {
                        drawPath(area, ink.colors.textSecondary.copy(alpha = DISCHARGE_TINT))
                        drawPath(edge, ink.colors.textSecondary, style = stroke)
                    }
                }
            }
        }
    }
}

/** A value label against the plot, centred on [y] but kept by its panel: wholly [inside] it, or half a line past its ends. */
@Suppress("LongParameterList") // A label, its place and its bounds.
private fun DrawScope.value(
    scale: TimeScale,
    label: TextLayoutResult,
    y: Float,
    top: Float,
    bottom: Float,
    inside: Boolean = false,
) {
    val height = label.size.height
    val reach = if (inside) 0f else height / 2f
    val at = (y - height / 2f).coerceIn(top - reach, (bottom - height + reach).coerceAtLeast(top - reach))
    drawText(label, topLeft = Offset(scale.valueStart(label.size.width, LabelGap.toPx()), at))
}

/** A panel's name: in the label column beside it, or above it when labels are stacked. */
private fun DrawScope.title(
    layout: TimelineLayout,
    scale: TimeScale,
    label: TextLayoutResult,
    top: Float,
    bottom: Float,
) {
    if (layout.stacked) {
        drawText(label, topLeft = Offset(scale.columnStart(label.size.width), top - StackGap.toPx() - label.size.height))
    } else {
        // Between the top and the middle gridline's values, as the lab sets it.
        val y = top + (bottom - top) * TITLE_AT - label.size.height / 2f
        drawText(label, topLeft = Offset(scale.columnStart(label.size.width), y))
    }
}

@Suppress("LongParameterList") // A band's place, scale, data, words and inks.
private fun DrawScope.band(
    layout: TimelineLayout,
    place: TimelineLayout.BandPlace,
    scale: TimeScale,
    band: TimelineBand,
    unknown: List<TrackMark>,
    text: ChartText,
    ink: TimelineInk,
    drawIn: DrawIn,
) {
    val label = text.measure(band.label)
    val labelY = if (layout.stacked) place.labelTop else (place.top + place.bottom - label.size.height) / 2f
    drawText(label, topLeft = Offset(scale.columnStart(label.size.width), labelY))
    val height = place.bottom - place.top
    val color = ink.of(band.ink)
    // The band's ground only where the history is known.
    known(unknown, scale).forEach { (from, to) ->
        drawRoundRect(
            ink.colors.textSecondary.copy(alpha = TRACK_BG),
            Offset(from, place.top),
            Size(to - from, height),
            CornerRadius(BandRadius.toPx()),
        )
    }
    with(drawIn) {
        revealed {
            clipRect(left = scale.left, right = scale.right, top = place.top - Outline.toPx(), bottom = place.bottom + Outline.toPx()) {
                if (band.line.isNotEmpty()) valueLine(place, scale, band, ink.colors.textPrimary)
                marks(place, scale, band, color)
            }
        }
    }
}

private fun DrawScope.marks(
    place: TimelineLayout.BandPlace,
    scale: TimeScale,
    band: TimelineBand,
    color: Color,
) {
    if (band.marks.isEmpty()) return
    val height = place.bottom - place.top
    val origin = minOf(scale.left, scale.right)
    val placed =
        scale.inWindow(band.marks).map { mark ->
            val a = scale.x(mark.fromMs) - origin
            val b = scale.x(mark.toMs) - origin
            Triple(minOf(a, b), maxOf(a, b), mark.light)
        }
    val ordered = if (placed.size > 1 && placed.first().first > placed.last().first) placed.asReversed() else placed
    val outline = Stroke(Outline.toPx())
    val inset = Outline.toPx() / 2
    for (run in TrackRuns.of(ordered, scale.right - scale.left, MinSpan.toPx(), Merge.toPx())) {
        val from = origin + run.from
        val width = run.to - run.from
        when (run) {
            is TrackRun.Tick -> {
                drawLine(color, Offset(from, place.top), Offset(from, place.bottom), TickWidth.toPx())
            }

            is TrackRun.Span -> {
                if (run.light) {
                    drawRect(color.copy(alpha = LIGHT), Offset(from, place.top), Size(width, height))
                    drawRect(color, Offset(from + inset, place.top + inset), Size(width - 2 * inset, height - 2 * inset), style = outline)
                } else {
                    drawRect(color, Offset(from, place.top), Size(width, height))
                }
            }

            is TrackRun.Dense -> {
                drawRect(color.copy(alpha = LIGHT), Offset(from, place.top), Size(width, height))
                drawRect(color, Offset(from + inset, place.top + inset), Size(width - 2 * inset, height - 2 * inset), style = outline)
            }
        }
    }
}

/** A value as a line inside the band: steps for a state such as the signal, a polyline otherwise. */
private fun DrawScope.valueLine(
    place: TimelineLayout.BandPlace,
    scale: TimeScale,
    band: TimelineBand,
    color: Color,
) {
    val top = place.top + LineInset.toPx()
    val bottom = place.bottom - LineInset.toPx()
    val path = Path()
    var last: Float? = null
    band.line.forEach { value ->
        val x = scale.x(value.atMs)
        val y = bottom - value.share.coerceIn(0f, 1f) * (bottom - top)
        val before = last
        if (before == null) {
            path.moveTo(x, y)
        } else {
            if (band.stepped) path.lineTo(x, before)
            path.lineTo(x, y)
        }
        last = y
    }
    drawPath(path, color, style = Stroke(LineWidth.toPx(), join = StrokeJoin.Round))
}

/** The pixel stretches of the plot that are not in [unknown], left to right. */
private fun known(
    unknown: List<TrackMark>,
    scale: TimeScale,
): List<Pair<Float, Float>> {
    val holes =
        unknown
            .map { minOf(scale.x(it.fromMs), scale.x(it.toMs)) to maxOf(scale.x(it.fromMs), scale.x(it.toMs)) }
            .sortedBy { it.first }
    val out = ArrayList<Pair<Float, Float>>()
    var at = scale.left
    for ((from, to) in holes) {
        if (from > at) out += at to minOf(from, scale.right)
        at = maxOf(at, to)
        if (at >= scale.right) break
    }
    if (at < scale.right) out += at to scale.right
    return out.filter { it.second > it.first }
}

private fun DrawScope.axis(
    layout: TimelineLayout,
    scale: TimeScale,
    ticks: List<TimeTick>,
    text: ChartText,
    ink: TimelineInk,
) {
    var taken: ClosedFloatingPointRange<Float>? = null
    ticks.forEach { tick ->
        val x = scale.x(tick.atMs)
        if (x !in scale.left..scale.right) return@forEach
        drawLine(ink.colors.textSecondary, Offset(x, layout.axisY), Offset(x, layout.axisY + AxisTick.toPx()), Outline.toPx())
        val label = text.measure(tick.text)
        val start = (x - label.size.width / 2f).coerceIn(scale.left, (scale.right - label.size.width).coerceAtLeast(scale.left))
        val place = start..(start + label.size.width)
        if (taken?.overlaps(place, LabelGap.toPx()) != true) {
            drawText(label, topLeft = Offset(start, layout.axisY + AxisTick.toPx() + AxisGap.toPx()))
            taken = place
        }
    }
}

/**
 * Two fingers zoom and pan; one finger dragged sideways scrubs; a tap picks a band. A drag that
 * starts up or down is left to the page, which scrolls.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.timelineGestures(
    layout: TimelineLayout,
    rtl: Boolean,
    window: () -> LongRange,
    gestures: () -> TimelineGestures?,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var mode = GestureMode.UNDECIDED
        do {
            val event = awaitPointerEvent()
            val scale = TimeScale(window().first, window().last, layout.plotLeft, layout.plotRight, rtl, size.width.toFloat())
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                mode = GestureMode.TRANSFORM
                val pan = event.calculatePan().x / (scale.right - scale.left) * if (rtl) -1 else 1
                gestures()?.onTransform?.invoke(event.calculateZoom(), scale.share(event.calculateCentroid(useCurrent = false).x), pan)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (pressed.size == 1 && mode != GestureMode.TRANSFORM && mode != GestureMode.PAGE) {
                val change = pressed.first()
                val moved = change.position - down.position
                if (mode == GestureMode.UNDECIDED && moved.getDistance() > viewConfiguration.touchSlop) {
                    mode = if (abs(moved.x) > abs(moved.y)) GestureMode.SCRUB else GestureMode.PAGE
                }
                if (mode == GestureMode.SCRUB) {
                    gestures()?.onScrub?.invoke(scale.at(change.position.x))
                    change.consume()
                }
            }
        } while (event.changes.any { it.pressed })
        if (mode == GestureMode.UNDECIDED) {
            val scale = TimeScale(window().first, window().last, layout.plotLeft, layout.plotRight, rtl, size.width.toFloat())
            gestures()?.onTap?.invoke(layout.bandAt(down.position.y), scale.at(down.position.x))
        }
    }
}

private enum class GestureMode { UNDECIDED, SCRUB, TRANSFORM, PAGE }

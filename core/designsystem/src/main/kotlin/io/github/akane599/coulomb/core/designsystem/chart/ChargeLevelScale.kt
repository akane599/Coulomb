// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

private val TrackHeight = 24.dp
private val TrackStroke = 4.dp
private val WaveStroke = 3.dp
private val WaveAmplitude = 3.dp
private val WaveLength = 16.dp
private val TickWidth = 2.dp
private val TickHeight = 20.dp
private val LabelGap = 4.dp
private const val FULL = 100f
private const val WAVE_STEPS_PER_LENGTH = 16

/**
 * The level from 0 to 100% while plugged in (SPEC 10.9; DESIGN.md: the wavy level only on Charging):
 * a still copper wave up to [levelPct], a hairline track on to 100%, and a tick at [targetPct] with
 * [targetLabel] over it, such as the time the target is reached with its Estimated mark. The label
 * centres on the tick and stays inside the scale. Nothing moves: the wave is drawn once per level.
 * TalkBack reads [description].
 */
@Composable
public fun ChargeLevelScale(
    levelPct: Int,
    targetPct: Int?,
    description: String,
    modifier: Modifier = Modifier,
    targetLabel: (@Composable () -> Unit)? = null,
) {
    val colors = CoulombTheme.colors
    val track: @Composable () -> Unit = {
        Canvas(Modifier.fillMaxWidth().height(TrackHeight)) {
            drawScale(levelPct, targetPct, colors.hairline, colors.accent, colors.textPrimary)
        }
    }
    Layout(
        content = {
            targetLabel?.invoke()
            track()
        },
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val label = if (measurables.size > 1) measurables.first().measure(loose) else null
        val bar = measurables.last().measure(loose.copy(minWidth = constraints.maxWidth))
        val gap = if (label != null) LabelGap.roundToPx() else 0
        val labelHeight = label?.height ?: 0
        layout(bar.width, labelHeight + gap + bar.height) {
            if (label != null && targetPct != null) {
                val share = targetPct.coerceIn(0, FULL.toInt()) / FULL
                val center = (if (layoutDirection == LayoutDirection.Rtl) 1 - share else share) * bar.width
                val left = (center - label.width / 2f).roundToInt().coerceIn(0, (bar.width - label.width).coerceAtLeast(0))
                // Absolute: the centre is already mirrored for a right-to-left layout.
                label.place(left, 0)
            }
            bar.placeRelative(0, labelHeight + gap)
        }
    }
}

/** The track to 100%, the wave to [levelPct] and the tick at [targetPct], mirrored by hand right to left. */
@Suppress("LongParameterList") // A scale and its three colours.
private fun DrawScope.drawScale(
    levelPct: Int,
    targetPct: Int?,
    track: Color,
    wave: Color,
    tick: Color,
) {
    val rtl = layoutDirection == LayoutDirection.Rtl

    fun x(pct: Float) = (pct / FULL * size.width).let { if (rtl) size.width - it else it }
    val mid = size.height / 2
    val level = levelPct.coerceIn(0, FULL.toInt()).toFloat()
    drawLine(track, Offset(x(level), mid), Offset(x(FULL), mid), TrackStroke.toPx(), StrokeCap.Round)
    if (level > 0f) {
        val path = Path()
        val end = level / FULL * size.width
        val step = WaveLength.toPx() / WAVE_STEPS_PER_LENGTH
        val steps = ceil(end / step).toInt()
        for (i in 0..steps) {
            val along = minOf(i * step, end)
            val y = mid + WaveAmplitude.toPx() * sin(2 * PI * along / WaveLength.toPx()).toFloat()
            val px = if (rtl) size.width - along else along
            if (i == 0) path.moveTo(px, y) else path.lineTo(px, y)
        }
        drawPath(path, wave, style = Stroke(WaveStroke.toPx(), cap = StrokeCap.Round))
    }
    targetPct?.let { target ->
        val tx = x(target.coerceIn(0, FULL.toInt()).toFloat())
        drawLine(tick, Offset(tx, mid - TickHeight.toPx() / 2), Offset(tx, mid + TickHeight.toPx() / 2), TickWidth.toPx())
    }
}

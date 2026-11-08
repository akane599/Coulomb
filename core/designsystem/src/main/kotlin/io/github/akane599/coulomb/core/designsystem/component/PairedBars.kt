// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme

/**
 * Paired bars (DESIGN.md, Rows with share bars; ADR-0038 §8): two figures on one scale, each a bar
 * from the start edge with its figure in Caption at the end. The first is hollow, a 1 dp
 * `text-secondary` outline; the second is filled, as a share bar is. There is no track, which
 * would read as an empty hollow bar. Both figures share one end column, so the bars start and end
 * on the same lines. They are decoration: the row states both figures, and TalkBack skips them.
 *
 * @param first the first figure as a fraction of the scale, 0 to 1.
 * @param second the second figure as a fraction of the scale.
 */
@Composable
public fun PairedBars(
    first: Float,
    second: Float,
    firstText: String,
    secondText: String,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    val style = CoulombTheme.typography.caption
    Layout(
        contents =
            listOf(
                // The two bars, then their two labels.
                { Canvas(Modifier) { bar(first, hollow = true, colors.textSecondary) } },
                { Canvas(Modifier) { bar(second, hollow = false, colors.textSecondary) } },
                { Text(firstText, color = colors.textSecondary, style = style, textAlign = TextAlign.End, maxLines = 1) },
                { Text(secondText, color = colors.textSecondary, style = style, textAlign = TextAlign.End, maxLines = 1) },
            ),
        modifier = modifier.clearAndSetSemantics {},
    ) { slots, constraints ->
        val (barSlots, labelSlots) = slots.chunked(2)
        val width = constraints.maxWidth
        val gap = CoulombSpacing.Space8.roundToPx()
        val lineGap = CoulombSpacing.Space4.roundToPx()
        val loose = Constraints(maxWidth = width / 2)
        val labels = labelSlots.map { it.single().measure(loose) }
        val column = labels.maxOf { it.width }
        val barWidth = (width - column - gap).coerceAtLeast(0)
        val barHeight = PairHeight.roundToPx()
        val bars = barSlots.map { it.single().measure(Constraints.fixed(barWidth, barHeight)) }
        val lines = labels.map { maxOf(it.height, barHeight) }
        layout(width, lines.sum() + lineGap) {
            var y = 0
            labels.forEachIndexed { at, label ->
                bars[at].placeRelative(0, y + (lines[at] - barHeight) / 2)
                label.placeRelative(width - label.width, y + (lines[at] - label.height) / 2)
                y += lines[at] + lineGap
            }
        }
    }
}

/**
 * The key to [PairedBars]: a hollow swatch before [firstText] and a filled one before [secondText],
 * in Caption. The pair wraps onto two lines when it does not fit one.
 */
@Composable
public fun PairedBarsKey(
    firstText: String,
    secondText: String,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16),
        verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space4),
    ) {
        KeyItem(firstText, hollow = true)
        KeyItem(secondText, hollow = false)
    }
}

@Composable
private fun KeyItem(
    text: String,
    hollow: Boolean,
) {
    val colors = CoulombTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8)) {
        Canvas(Modifier.size(SwatchWidth, PairHeight)) { bar(1f, hollow, colors.textSecondary) }
        Text(text, color = colors.textSecondary, style = CoulombTheme.typography.caption)
    }
}

/**
 * One bar of the pair, [fraction] of the width from the start edge. A figure above zero shows at
 * least as a round end, so it never vanishes beside a large one; zero draws nothing.
 */
private fun DrawScope.bar(
    fraction: Float,
    hollow: Boolean,
    color: Color,
) {
    if (fraction <= 0f) return
    val width = maxOf(size.width * fraction.coerceAtMost(1f), size.height)
    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
    val corner = CornerRadius(size.height / 2)
    if (hollow) {
        val stroke = PairOutline.toPx()
        drawRoundRect(
            color,
            topLeft = Offset(left + stroke / 2, stroke / 2),
            size = Size(width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(corner.x - stroke / 2),
            style = Stroke(stroke),
        )
    } else {
        drawRoundRect(color, topLeft = Offset(left, 0f), size = Size(width, size.height), cornerRadius = corner)
    }
}

/** Taller than a share bar's 4 dp, so the hollow bar keeps an inside. */
private val PairHeight = 6.dp
private val PairOutline = 1.dp
private val SwatchWidth = 16.dp

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme

/** DESIGN.md: columns are at least 7.5 em of the 16 sp body size. */
private const val MIN_COLUMN_EM = 7.5f
private val Em = 16.sp
private val Hairline = 1.dp

/**
 * The figure strip of DESIGN.md: timetable columns divided by hairline rules, with hairlines above
 * and below. Columns share the width, are at least 7.5 em wide and wrap onto more rows at large
 * text. The first column's text starts at the content edge; the rule before it falls in the
 * gutter and is not drawn (lab `.figures`).
 *
 * @param content [CoulombFigure]s.
 */
@Composable
public fun CoulombFigureStrip(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val hairline = CoulombTheme.colors.hairline
    val rules = remember { StripRules() }
    Layout(
        content = content,
        modifier =
            modifier.drawWithContent {
                drawContent()
                val stroke = Hairline.toPx()
                val rtl = layoutDirection == LayoutDirection.Rtl
                rules.rows.forEach { y -> drawLine(hairline, Offset(0f, y), Offset(size.width, y), stroke) }
                drawLine(hairline, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
                rules.columns.forEach { (x, span) ->
                    val at = if (rtl) size.width - x else x
                    drawLine(hairline, Offset(at, span.start), Offset(at, span.endInclusive), stroke)
                }
            },
    ) { measurables, constraints ->
        val pad = CoulombSpacing.Space12.roundToPx()
        val total = constraints.maxWidth + pad
        // Scale the em first: Android's non-linear font scaling barely grows a 120 sp size (API 34+).
        val minColumn = (MIN_COLUMN_EM * Em.toPx()).toInt()
        val perRow = (total / minColumn).coerceIn(1, measurables.size.coerceAtLeast(1))
        val rows = measurables.chunked(perRow)
        val top = CoulombSpacing.Space12.roundToPx()
        val bottom = CoulombSpacing.Space16.roundToPx()
        var y = 0
        val placed = mutableListOf<Triple<androidx.compose.ui.layout.Placeable, Int, Int>>()
        val rowLines = mutableListOf<Float>()
        val columnLines = mutableListOf<Pair<Float, ClosedFloatingPointRange<Float>>>()
        for (row in rows) {
            val width = total / row.size
            val placeables = row.map { it.measure(Constraints.fixedWidth((width - 2 * pad).coerceAtLeast(0))) }
            val height = top + placeables.maxOf { it.height } + bottom
            rowLines += y + Hairline.toPx() / 2
            placeables.forEachIndexed { index, placeable ->
                val left = index * width - pad
                placed += Triple(placeable, left + pad, y + top)
                if (index > 0) columnLines += left.toFloat() to y.toFloat()..(y + height).toFloat()
            }
            y += height
        }
        rules.rows = rowLines
        rules.columns = columnLines
        layout(constraints.maxWidth, y) { placed.forEach { (placeable, x, top) -> placeable.placeRelative(x, top) } }
    }
}

/** Where the strip's rules fall, set by its layout and read when it draws. */
private class StripRules {
    var rows: List<Float> = emptyList()
    var columns: List<Pair<Float, ClosedFloatingPointRange<Float>>> = emptyList()
}

/**
 * One column of a [CoulombFigureStrip]: [label] in Label, `text-secondary`; the figure (Title 600,
 * its unit in Label and its provenance mark) in [value]; and [caption] in Caption under it. The
 * column reads as one item to TalkBack.
 */
@Composable
public fun CoulombFigure(
    label: String,
    caption: String?,
    modifier: Modifier = Modifier,
    value: @Composable RowScope.() -> Unit,
) {
    CoulombFigure(
        label = { Text(text = label, color = CoulombTheme.colors.textSecondary, style = CoulombTheme.typography.label) },
        caption = caption,
        modifier = modifier,
        value = value,
    )
}

/** A [CoulombFigure] whose label is its own composable, such as a [CoulombTerm]. */
@Composable
public fun CoulombFigure(
    label: @Composable () -> Unit,
    caption: String?,
    modifier: Modifier = Modifier,
    value: @Composable RowScope.() -> Unit,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Column(modifier.semantics(mergeDescendants = true) {}) {
        label()
        Row(Modifier.padding(top = CoulombSpacing.Space4), verticalAlignment = Alignment.CenterVertically, content = value)
        if (caption != null) Text(text = caption, color = colors.textSecondary, style = type.caption)
    }
}

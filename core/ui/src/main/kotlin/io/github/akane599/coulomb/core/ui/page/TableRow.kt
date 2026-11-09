// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombTerm
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.R
import io.github.akane599.coulomb.core.ui.breakable
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.label

/** One row of an [InlineTable]: a name, its figures, and an optional note and definition. */
@Immutable
public data class TableRow(
    val name: String,
    val values: List<String>,
    /** A record's tag: set in the mono face, breaking only at [breakable]'s points. */
    val mono: Boolean = false,
    /** A line under the name, in Caption (lab `.note`). */
    val note: String? = null,
    /** The name is a term with this definition (DESIGN.md, Terms). */
    val term: String? = null,
    /** The provenance of the row's figures, marked after the last one, where rows differ in it. */
    val mark: Provenance? = null,
)

/**
 * A table on the page (lab `.data-table`): headers in Label, `text-secondary`, over a hairline; each
 * row's name first, its figures at the end in tabular numerals, a hairline under each row. Where
 * the name would get less than [NAME_MIN_EM] beside the figures, as at 200% text, each name takes
 * its own line and the figures sit under it, each with its column's name (lab's large-text
 * layout). TalkBack reads each row as one item with its column names and any definition. A
 * table longer than [shown] rows ends with "Show all".
 */
@Composable
public fun InlineTable(
    columns: List<String>,
    rows: List<TableRow>,
    modifier: Modifier = Modifier,
    termAction: String? = null,
    shown: Int = Int.MAX_VALUE,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    var open by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // One em converted, then multiplied: Android's non-linear text scaling barely grows a size as
        // large as 120 sp, so converting the product would keep the threshold near 120 dp at any scale.
        val nameMin = with(LocalDensity.current) { EM.toDp() * NAME_MIN_EM }
        val valueWidth = ValueColumn * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val stacked = maxWidth - (valueWidth + CoulombSpacing.Space12) * (columns.size - 1) < nameMin
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .hairline(colors.hairline)
                    .padding(vertical = CoulombSpacing.Space8)
                    .clearAndSetSemantics {},
            ) {
                // In a box, so the header starts where the names do in either direction.
                Box(Modifier.weight(1f)) { Text(columns.first(), color = colors.textSecondary, style = type.label) }
                if (!stacked) {
                    columns.drop(1).forEach { FigureCell(it, valueWidth, colors.textSecondary, type.label) }
                }
            }
            val visible = if (open || rows.size <= shown) rows else rows.take(shown)
            visible.forEach { row -> TableLine(row, columns, stacked, valueWidth, termAction) }
            if (rows.size > shown) {
                val state = stringResource(if (open) R.string.page_state_expanded else R.string.page_state_collapsed)
                CoulombTextButton(
                    text =
                        if (open) {
                            stringResource(R.string.page_show_fewer)
                        } else {
                            stringResource(R.string.page_show_all, rememberFormats().integer(rows.size.toLong()))
                        },
                    onClick = { open = !open },
                    modifier = Modifier.padding(top = CoulombSpacing.Space4).semantics { stateDescription = state },
                    edge = ButtonEdge.Start,
                )
            }
        }
    }
}

/**
 * A figure at the end of its column. The box, not the text, takes the end, so a figure whose
 * digits read left to right still sits at the end of an RTL row.
 */
@Composable
private fun FigureCell(
    text: String,
    width: Dp,
    color: Color,
    style: TextStyle,
    mark: Provenance? = null,
) {
    Box(Modifier.padding(start = CoulombSpacing.Space12).width(width), contentAlignment = Alignment.TopEnd) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = color, style = style)
            mark?.let { ProvenanceMark(it) }
        }
    }
}

@Composable
private fun TableLine(
    row: TableRow,
    columns: List<String>,
    stacked: Boolean,
    valueWidth: Dp,
    termAction: String?,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val labels = columns.drop(1)
    val spoken =
        listOfNotNull(row.name, row.note).joinToString(". ") + ". " +
            labels.zip(row.values).joinToString(", ") { (column, value) -> "$column $value" } +
            row.term?.let { ". $it" }.orEmpty() +
            row.mark?.let { ". " + stringResource(it.label) }.orEmpty()
    val rowModifier = Modifier.fillMaxWidth().hairline(colors.hairline).padding(vertical = CoulombSpacing.Space12)
    val name: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            if (row.term != null && termAction != null) {
                CoulombTerm(row.name, row.term, termAction, type.data, colors.textPrimary)
            } else if (row.mono) {
                // Aligned to the row's start, so a wrapped Latin tag sits with its note in RTL too.
                val start = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left
                Text(breakable(row.name), color = colors.textPrimary, style = type.mono.copy(textAlign = start))
            } else {
                Text(row.name, color = colors.textPrimary, style = type.data)
            }
            row.note?.let { Text(it, Modifier.padding(top = 2.dp), color = colors.textSecondary, style = type.caption) }
        }
    }
    // TalkBack reads the row as one item, a term's definition included, so it needs no second stop.
    val semantics = Modifier.clearAndSetSemantics { contentDescription = spoken }
    if (stacked) {
        Column(rowModifier.then(semantics)) {
            name(Modifier.fillMaxWidth())
            // Each figure keeps its column's name, since the header shows only the first.
            FlowRow(
                Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space4),
                horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16),
            ) {
                labels.zip(row.values).forEachIndexed { index, (column, value) ->
                    MarkedFigure(row.mark.takeIf { index == labels.lastIndex }) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = colors.textSecondary)) { append(column) }
                                append(' ')
                                append(value)
                            },
                            color = colors.textPrimary,
                            style = type.data,
                        )
                    }
                }
            }
        }
    } else {
        Row(rowModifier.then(semantics)) {
            name(Modifier.weight(1f))
            row.values.forEachIndexed { index, value ->
                FigureCell(value, valueWidth, colors.textPrimary, type.data, row.mark.takeIf { index == row.values.lastIndex })
            }
        }
    }
}

/** [content], then [mark] after it where there is one. */
@Composable
private fun MarkedFigure(
    mark: Provenance?,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        content()
        mark?.let { ProvenanceMark(it) }
    }
}

/** A hairline across the full width under the content. */
private fun Modifier.hairline(color: Color): Modifier =
    drawBehind {
        val stroke = 1.dp.toPx()
        drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
    }

/** About eleven characters of a name beside the figures, as on Drain's rows. */
private const val NAME_MIN_EM = 7.5f
private val EM = 16.sp

/** Room a figure column takes at 100% text: "1 h 10 min" or "123,456". */
private val ValueColumn = 84.dp

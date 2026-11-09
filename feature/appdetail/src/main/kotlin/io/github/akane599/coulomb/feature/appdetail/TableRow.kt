// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akane599.coulomb.core.designsystem.component.CoulombTerm
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme

/** One row of an [InlineTable]: a name, its figures, and an optional note and definition. */
@Immutable
internal data class TableRow(
    val name: String,
    val values: List<String>,
    /** A record's tag: set in the mono face, breaking anywhere. */
    val mono: Boolean = false,
    /** A line under the name, in Caption (lab `.note`). */
    val note: String? = null,
    /** The name is a term with this definition (DESIGN.md, Terms). */
    val term: String? = null,
)

/**
 * A table on the page (lab `.data-table`): headers in Label, `text-secondary`, over a hairline; each
 * row's name first, its figures right-aligned in tabular numerals, a hairline under each row.
 * Where the name would get less than [NAME_MIN_EM] beside the figures, as at 200% text, each name
 * takes its own line and the figures sit under it (lab's large-text layout). TalkBack reads each
 * row as one item with its column names; a term keeps its own focus for its definition.
 */
@Composable
internal fun InlineTable(
    columns: List<String>,
    rows: List<TableRow>,
    termAction: String? = null,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val nameMin = with(LocalDensity.current) { (EM * NAME_MIN_EM).toDp() }
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
                    columns.drop(1).forEach {
                        Text(
                            it,
                            Modifier.padding(start = CoulombSpacing.Space12).width(valueWidth),
                            color = colors.textSecondary,
                            style = type.label,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
            rows.forEach { row -> TableLine(row, columns, stacked, valueWidth, termAction) }
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
    val spoken =
        listOfNotNull(row.name, row.note).joinToString(". ") + ". " +
            columns.drop(1).zip(row.values).joinToString(", ") { (column, value) -> "$column $value" }
    val rowModifier = Modifier.fillMaxWidth().hairline(colors.hairline).padding(vertical = CoulombSpacing.Space12)
    val name: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            if (row.term != null && termAction != null) {
                CoulombTerm(row.name, row.term, termAction, type.data, colors.textPrimary)
            } else {
                Text(row.name, color = colors.textPrimary, style = if (row.mono) type.mono else type.data)
            }
            row.note?.let { Text(it, Modifier.padding(top = 2.dp), color = colors.textSecondary, style = type.caption) }
        }
    }
    // A term keeps its own focus, so the row is not merged then; otherwise TalkBack reads one item.
    val semantics = if (row.term == null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier.semantics { }
    if (stacked) {
        Column(rowModifier) {
            Column(semantics) {
                name(Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space4),
                    horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space12),
                ) {
                    row.values.forEach { Text(it, Modifier.weight(1f), color = colors.textPrimary, style = type.data) }
                }
            }
        }
    } else {
        Row(rowModifier.then(semantics)) {
            name(Modifier.weight(1f))
            row.values.forEach {
                Text(
                    it,
                    Modifier.padding(start = CoulombSpacing.Space12).width(valueWidth),
                    color = colors.textPrimary,
                    style = type.data,
                    textAlign = TextAlign.End,
                )
            }
        }
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

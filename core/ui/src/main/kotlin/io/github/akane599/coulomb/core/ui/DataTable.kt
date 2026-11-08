// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.model.Provenance

/** A chart's data as rows of text; the first column is the label, the others are figures. */
@Immutable
public data class DataTable(
    val title: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    /** The single source of every figure, marked once on the header (SPEC 10.1). */
    val provenance: Provenance,
)

private val TableRowHeight = 48.dp
private val Hairline = 1.dp

/**
 * "View as table" for a chart (DESIGN.md, Charts): the same data as a table in a bottom sheet, the
 * label column first and the figures right-aligned in tabular numerals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun DataTableSheet(
    table: DataTable,
    onDismiss: () -> Unit,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val gutter = currentGutter()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = CoulombRadius.Sheet, topEnd = CoulombRadius.Sheet),
        containerColor = colors.surfaceRaised,
    ) {
        // One mark on the header covers every figure (SPEC 10.1).
        MarkedText(
            text = table.title,
            provenance = table.provenance,
            style = type.title,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = gutter).semantics { heading() },
        )
        LazyColumn(Modifier.padding(top = CoulombSpacing.Space12, bottom = CoulombSpacing.Space24)) {
            item { TableRow(table.columns, header = true, gutter = gutter) }
            // The table is a snapshot taken when the sheet opens, so a row's index is its key (SPEC 15.5).
            itemsIndexed(table.rows, key = { index, _ -> index }) { _, row -> TableRow(row, header = false, gutter = gutter) }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    header: Boolean,
    gutter: Dp,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TableRowHeight)
            .drawBehind {
                val stroke = Hairline.toPx()
                drawLine(colors.hairline, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            }.padding(horizontal = gutter)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { index, cell ->
            Text(
                text = cell,
                modifier = Modifier.weight(1f),
                color = if (header) colors.textSecondary else colors.textPrimary,
                style = if (header) type.label else type.data,
                textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
            )
        }
    }
}

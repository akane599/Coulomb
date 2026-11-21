// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.component.ShareBar
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.MarkedText
import io.github.akane599.coulomb.core.ui.NameBesideValue
import io.github.akane599.coulomb.core.ui.ProvenanceMark

/**
 * A row with a share bar without an icon (DESIGN.md, Rows with share bars; lab `.data-row`): the
 * name in Body 500 with [detail] in Caption under it, the figure at the end with its mark, an
 * optional [action] under the figure, the 4 dp bar across where there is a [share]. Hairlines divide rows; the [first] row
 * draws one above itself too. A row without an [action] is one TalkBack item, and a button when it has [onClick];
 * a row with one reads its name and its figure as two items beside the action.
 */
@Composable
@Suppress("LongParameterList") // One row's content.
public fun DataRow(
    name: String,
    detail: String?,
    value: String,
    mark: Provenance?,
    share: Double?,
    first: Boolean,
    modifier: Modifier = Modifier,
    detailMark: Provenance? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                if (selected) {
                    val bleed = CoulombSpacing.Space8.toPx()
                    drawRoundRect(
                        colors.surfaceRaised,
                        topLeft = Offset(-bleed, 0f),
                        size = Size(size.width + bleed * 2, size.height),
                        cornerRadius = CornerRadius(SelectedCorner.toPx()),
                    )
                }
                val stroke = 1.dp.toPx()
                if (first) drawLine(colors.hairline, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
                drawLine(colors.hairline, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            }.then(clickable)
            .semantics(mergeDescendants = action == null) { if (selected) this.selected = true }
            .heightIn(min = RowMinHeight)
            .padding(vertical = CoulombSpacing.Space12),
    ) {
        // A merging child is not merged into its parent, so the parts merge alone only beside an action.
        val part = if (action != null) Modifier.semantics(mergeDescendants = true) {} else Modifier
        NameBesideValue(
            name = {
                Column(part) {
                    Text(name, color = colors.textPrimary, style = type.bodyName)
                    detail?.let {
                        if (detailMark != null) {
                            MarkedText(it, detailMark, type.caption, colors.textSecondary)
                        } else {
                            Text(it, color = colors.textSecondary, style = type.caption)
                        }
                    }
                }
            },
            value = {
                Column(horizontalAlignment = Alignment.End) {
                    Row(
                        part,
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(value, color = colors.textPrimary, style = type.data, textAlign = TextAlign.End)
                        mark?.let { ProvenanceMark(it) }
                    }
                    action?.invoke()
                }
            },
        )
        share?.let { ShareBar(it.toFloat(), Modifier.padding(top = CoulombSpacing.Space8)) }
    }
}

private val RowMinHeight = 64.dp
private val SelectedCorner = 12.dp

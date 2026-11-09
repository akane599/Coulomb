// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.AppBreakdown
import io.github.akane599.coulomb.core.designsystem.component.CoulombIconButton
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.ui.breakdown.Swatch
import io.github.akane599.coulomb.core.ui.breakdown.hue
import io.github.akane599.coulomb.core.ui.breakdown.label
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.R as UiR

/**
 * A row in a list where some rows open to their components (lab `.drain-row`): [row] (drawn
 * without its own hairlines) with a 48 dp slot at its end, so every figure lines up. With
 * [expand] the slot holds the expand button, which turns over when open; otherwise it is empty,
 * or, without [slot], left out, for a list where no row opens.
 * The row's tap still opens App detail (SPEC 10.3); only the button opens the breakdown, which
 * follows as its own list item ([AppBreakdownList]), so it fades in and the rows under it slide.
 * The hairline under an open row moves under the breakdown.
 */
@Composable
internal fun SlotRow(
    first: Boolean,
    modifier: Modifier = Modifier,
    expand: RowExpand? = null,
    slot: Boolean = true,
    row: @Composable (Modifier) -> Unit,
) {
    val colors = CoulombTheme.colors
    Row(
        modifier.fillMaxWidth().hairlines(colors.hairline, top = first, bottom = expand?.open != true),
        horizontalArrangement = Arrangement.spacedBy(ButtonGap),
    ) {
        row(Modifier.weight(1f))
        when {
            // The same width either way, so every row's figure lines up.
            expand != null -> {
                Box(Modifier.width(SlotWidth)) {
                    // Lab `.icon-btn`: a quiet control beside the figure.
                    CompositionLocalProvider(LocalContentColor provides colors.textSecondary) { ExpandButton(expand) }
                }
            }

            slot -> {
                Box(Modifier.width(SlotWidth))
            }
        }
    }
}

@Composable
private fun ExpandButton(expand: RowExpand) {
    val open = expand.open
    val state = stringResource(if (open) R.string.drain_state_expanded else R.string.drain_state_collapsed)
    CoulombIconButton(
        icon = CoulombIcons.ExpandMore,
        contentDescription = stringResource(if (open) R.string.drain_hide_components else R.string.drain_show_components, expand.name),
        onClick = expand.toggle,
        modifier =
            Modifier
                // Level with the row's first line. The 48 dp target overhangs the slot at the end,
                // so the glyph, not the target, meets the edge.
                .padding(top = CoulombSpacing.Space12)
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .rotate(if (open) HALF_TURN else 0f)
                .semantics { stateDescription = state },
    )
}

/**
 * An app's components (DESIGN.md, Breakdown): each a 10 dp swatch in its data hue, the name in
 * Label and the mAh in `text-secondary`, wrapping, with no container and no tap target; then what
 * S4 did not break down, with a hollow swatch. Figures are multiplied by [factor], as the row's.
 */
@Composable
internal fun AppBreakdownList(
    breakdown: AppBreakdown,
    factor: Double,
    formats: Formats,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    FlowRow(
        modifier
            .fillMaxWidth()
            .hairlines(colors.hairline, top = false, bottom = true)
            .padding(start = BreakdownIndent, bottom = CoulombSpacing.Space16),
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16),
        verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space4),
    ) {
        breakdown.parts.forEach { part ->
            Chip(stringResource(part.component.label), formats.mah(part.mah * factor).joined(), colors.hue(part.component))
        }
        breakdown.notBrokenDownMah?.let { rest ->
            Chip(stringResource(UiR.string.component_not_broken_down), formats.mah(rest * factor).joined(), hue = null)
        }
    }
}

/** One component of an app: TalkBack reads its name and figure as one item. */
@Composable
private fun Chip(
    name: String,
    value: String,
    hue: Color?,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Row(
        Modifier.heightIn(min = ChipHeight).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(hue)
        Box(Modifier.width(SwatchGap))
        Text(name, color = colors.textPrimary, style = type.label)
        Box(Modifier.width(SwatchGap))
        Text(value, color = colors.textSecondary, style = type.label.copy(fontWeight = type.body.fontWeight))
    }
}

/** List hairlines across the full width: above the first row, and under each row. */
internal fun Modifier.hairlines(
    color: Color,
    top: Boolean,
    bottom: Boolean,
): Modifier =
    drawBehind {
        val stroke = Outline.toPx()
        if (top) drawLine(color, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
        if (bottom) drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
    }

private val ButtonGap = 4.dp

/** The button's 48 dp target reaches 12 dp past the text column, so its 24 dp glyph ends at the edge. */
private val ButtonOverhang = 12.dp

/** What the button takes from the row: its 48 dp less the overhang. */
private val SlotWidth = 48.dp - ButtonOverhang

/** The 40 dp icon and the 16 dp after it: the breakdown starts under the name. */
private val BreakdownIndent = 56.dp
private val ChipHeight = 24.dp
private val SwatchGap = 6.dp
private val Outline = 1.dp
private const val HALF_TURN = 180f

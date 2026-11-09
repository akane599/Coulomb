// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme

private val ButtonPadding = 12.dp
private val ButtonIcon = 18.dp
private val ButtonIconGap = 4.dp
private val ShareHeight = 4.dp
private val ShareEnd = 2.dp
private val HeadActionHang = 11.dp
private val OutlinedHeight = 40.dp
private val OutlinedPadding = 20.dp
private val OutlinedBorder = 1.dp
private val HoverLift = 1.dp

/**
 * A section's head (DESIGN.md, lab `.section-head`): the title in Title as a heading, and an
 * optional action at the end, such as "See all". The caller leaves 12 dp to the first row.
 */
@Composable
public fun CoulombSectionHead(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            // The text takes its own width at the layout's start: a Latin title in a right-to-left
            // layout resolves left-to-right and would otherwise sit at the left, beside the action.
            modifier = Modifier.weight(1f).wrapContentWidth(Alignment.Start).semantics { heading() },
            color = CoulombTheme.colors.textPrimary,
            style = CoulombTheme.typography.title,
        )
        if (action != null) {
            Spacer(Modifier.width(CoulombSpacing.Space16))
            // The 48 dp target reaches past the 26 dp title line instead of setting the head's height.
            Box(Modifier.hangVertically(HeadActionHang)) { action() }
        }
    }
}

/** Which edge of a [CoulombTextButton] lines its label up with the content beside it. */
public enum class ButtonEdge {
    /** Laid out as is. */
    None,

    /** The label starts at the content's start edge, as "View as table" under a chart. */
    Start,

    /** The label ends at the content's end edge, as "See all" in a section head. */
    End,
}

/**
 * The text button of DESIGN.md: Label button in `text-primary`, at least 48 dp tall, 12 dp side
 * padding and a fully round state layer. [edge] lets the padding hang into the gutter, so the
 * label lines up with the text beside it while the touch target stays 48 dp (lab `.text-btn`).
 * A button that is not [enabled] yet stays in place in `text-primary` at Material's 38%, so a
 * dialog's main action shows before it can be taken.
 */
@Composable
public fun CoulombTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes trailingIcon: Int? = null,
    edge: ButtonEdge = ButtonEdge.None,
) {
    val colors = CoulombTheme.colors
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier
                .hang(
                    start = if (edge == ButtonEdge.Start) ButtonPadding else 0.dp,
                    end = if (edge == ButtonEdge.End) ButtonPadding else 0.dp,
                ).heightIn(min = CoulombSpacing.TouchTarget),
        contentPadding = PaddingValues(horizontal = ButtonPadding),
        colors =
            ButtonDefaults.textButtonColors(
                contentColor = colors.textPrimary,
                disabledContentColor = colors.textPrimary.copy(alpha = DISABLED_ALPHA),
            ),
    ) {
        ButtonLabel(text, trailingIcon)
    }
}

/**
 * The outlined button of DESIGN.md, the secondary action: a 1 dp `hairline` border, Label button
 * in `text-primary`, 40 dp tall with 20 dp side padding and a fully round shape. Material extends
 * its touch target to 48 dp.
 */
@Composable
public fun CoulombOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes trailingIcon: Int? = null,
) {
    val colors = CoulombTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = OutlinedHeight),
        shape = RoundedCornerShape(percent = 50),
        border = BorderStroke(OutlinedBorder, colors.hairline),
        contentPadding = PaddingValues(horizontal = OutlinedPadding),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
    ) {
        ButtonLabel(text, trailingIcon)
    }
}

/**
 * The filled button of DESIGN.md, one per region: `accent-fill` with `on-accent-fill`, Label
 * button, 40 dp tall with 20 dp side padding, fully round. A pointer over it lifts it a little
 * (the hover lift). Material extends its touch target to 48 dp.
 */
@Composable
public fun CoulombFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes trailingIcon: Int? = null,
) {
    val colors = CoulombTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = OutlinedHeight),
        shape = RoundedCornerShape(percent = 50),
        contentPadding = PaddingValues(horizontal = OutlinedPadding),
        colors = ButtonDefaults.buttonColors(containerColor = colors.accentFill, contentColor = colors.onAccentFill),
        elevation =
            ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = HoverLift,
                disabledElevation = 0.dp,
            ),
    ) {
        ButtonLabel(text, trailingIcon)
    }
}

@Composable
private fun RowScope.ButtonLabel(
    text: String,
    @DrawableRes trailingIcon: Int?,
) {
    Text(text = text, style = CoulombTheme.typography.labelButton)
    if (trailingIcon != null) {
        Spacer(Modifier.width(ButtonIconGap))
        Icon(painter = painterResource(trailingIcon), contentDescription = null, modifier = Modifier.size(ButtonIcon))
    }
}

/** Lets the element reach [by] above and below its slot, as a negative block margin does. */
public fun Modifier.hangVertically(by: Dp): Modifier =
    layout { measurable, constraints ->
        val trim = by.roundToPx()
        val placeable = measurable.measure(constraints)
        val height = (placeable.height - 2 * trim).coerceAtLeast(0)
        layout(placeable.width, height) { placeable.placeRelative(0, -trim) }
    }

/** Lets the element reach [start] and [end] past its slot, as a negative margin does. */
private fun Modifier.hang(
    start: Dp,
    end: Dp,
): Modifier =
    if (start == 0.dp && end == 0.dp) {
        this
    } else {
        layout { measurable, constraints ->
            val before = start.roundToPx()
            val after = end.roundToPx()
            val placeable = measurable.measure(constraints.offset(horizontal = before + after))
            val width = (placeable.width - before - after).coerceIn(constraints.minWidth, constraints.maxWidth)
            layout(width, placeable.height) { placeable.placeRelative(-before, 0) }
        }
    }

/**
 * A share bar (DESIGN.md, Rows with share bars): 4 dp, a `hairline` track and a `text-secondary`
 * fill from the start edge, with 2 dp round ends. Shares are neutral, so it carries no hue. It is
 * decoration: the row states the share in text.
 */
@Composable
public fun ShareBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    Canvas(modifier.fillMaxWidth().height(ShareHeight)) {
        val corner = CornerRadius(ShareEnd.toPx())
        drawRoundRect(colors.hairline, cornerRadius = corner)
        val width = size.width * fraction.coerceIn(0f, 1f)
        if (width > 0f) {
            val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
            drawRoundRect(colors.textSecondary, topLeft = Offset(left, 0f), size = Size(width, size.height), cornerRadius = corner)
        }
    }
}

/** Material's disabled content opacity. */
private const val DISABLED_ALPHA = 0.38f

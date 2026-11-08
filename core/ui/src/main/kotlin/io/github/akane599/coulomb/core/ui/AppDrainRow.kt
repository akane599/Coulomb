// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.component.ShareBar
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.format.Figure

private val IconSize = 40.dp
private val IconCorner = 12.dp
private val RowMinHeight = 72.dp
private val Hairline = 1.dp
private val GlyphSize = 20.dp

/**
 * A ranked row with a share bar (DESIGN.md, Rows with share bars; lab `.app-row`): the 40 dp icon,
 * the name in Body 500 with [detail] in Caption under it, the figure right-aligned with its unit
 * and provenance mark, the share in Caption under that, and the 4 dp share bar across the text
 * columns. Hairlines divide rows; the [first] row also draws one above itself. TalkBack reads the
 * row as one item. Without an icon, [standIn] fills the slot: by default the name's initial on a
 * quiet tile.
 */
@Composable
@Suppress("LongParameterList") // One row's content; the screen builds it from a read model.
public fun AppDrainRow(
    icon: ImageBitmap?,
    name: String,
    detail: String?,
    value: Figure,
    provenance: Provenance,
    share: Double,
    shareText: String,
    first: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    standIn: IconStandIn? = null,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = Hairline.toPx()
                if (first) drawLine(colors.hairline, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
                drawLine(colors.hairline, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            }.then(clickable)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = RowMinHeight)
            .padding(vertical = CoulombSpacing.Space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, standIn ?: IconStandIn.Initial(name.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty()))
        Spacer(Modifier.width(CoulombSpacing.Space16))
        Column(Modifier.weight(1f)) {
            NameBesideValue(
                name = {
                    Column {
                        Text(text = name, color = colors.textPrimary, style = type.bodyName)
                        if (detail != null) {
                            // Never cut: a detail explains the row, and wraps at any text size.
                            Text(text = detail, color = colors.textSecondary, style = type.caption)
                        }
                    }
                },
                value = {
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                            FigureText(
                                value.affixed(),
                                numberStyle = type.data,
                                unitStyle = type.data.copy(fontWeight = type.body.fontWeight),
                            )
                            ProvenanceMark(provenance)
                        }
                        Text(text = shareText, color = colors.textSecondary, style = type.caption, textAlign = TextAlign.End)
                    }
                },
            )
            ShareBar(share.toFloat(), Modifier.padding(top = CoulombSpacing.Space8))
        }
    }
}

/**
 * The name column and the value column side by side, the value at the end. When the value leaves
 * the name less than [NAME_MIN_EM] of its text size, as at 200% text with a six-digit figure, the
 * value moves under the name instead, still at the end so figures line up down the list, and no
 * word breaks mid-word (DESIGN.md: text reflows).
 */
@Composable
private fun NameBesideValue(
    name: @Composable () -> Unit,
    value: @Composable () -> Unit,
) {
    val nameSize = CoulombTheme.typography.bodyName.fontSize
    Layout(contents = listOf(name, value)) { (nameColumn, valueColumn), constraints ->
        val width = constraints.maxWidth
        val gap = CoulombSpacing.Space16.roundToPx()
        val figure = valueColumn.single().measure(Constraints(maxWidth = width))
        val room = width - figure.width - gap
        if (room >= nameSize.toPx() * NAME_MIN_EM) {
            val text = nameColumn.single().measure(Constraints(minWidth = room, maxWidth = room))
            layout(width, maxOf(text.height, figure.height)) {
                text.placeRelative(0, 0)
                figure.placeRelative(width - figure.width, 0)
            }
        } else {
            val text = nameColumn.single().measure(Constraints(minWidth = width, maxWidth = width))
            layout(width, text.height + figure.height) {
                text.placeRelative(0, 0)
                figure.placeRelative(width - figure.width, text.height)
            }
        }
    }
}

/** About eleven characters of a name: room for a long word such as "Application" at any text size. */
private const val NAME_MIN_EM = 7.5f

/**
 * [iconPackage]'s icon from [load] (the shared icon cache), at the size [AppDrainRow] draws it, so
 * the bitmap is never scaled. Null until it loads, or when there is no package.
 */
@Composable
public fun rememberAppIcon(
    iconPackage: String?,
    load: suspend (packageName: String, sizePx: Int) -> ImageBitmap?,
): ImageBitmap? {
    val sizePx = with(LocalDensity.current) { IconSize.roundToPx() }
    val current by rememberUpdatedState(load)
    val bitmap by produceState<ImageBitmap?>(null, iconPackage, sizePx) {
        value = iconPackage?.let { current(it, sizePx) }
    }
    return bitmap
}

/** What stands in a row's icon slot while there is no icon. */
public sealed interface IconStandIn {
    /** What the tile says. */
    public val text: String

    /** A quiet `surface-raised` tile: an app's initial, or "#" for a bare UID. */
    public data class Initial(
        override val text: String,
    ) : IconStandIn

    /** The remainder row's hairline-outlined tile ("+22"; DESIGN.md, Remainder and gap). */
    public data class Remainder(
        override val text: String,
    ) : IconStandIn

    /**
     * A group inside an opened remainder that opens nothing ("+3" system apps hidden): the count
     * with no tile, so it does not look like the tappable remainder.
     */
    public data class Group(
        override val text: String,
    ) : IconStandIn

    /** The phone's own row (SPEC 6.5 friendly system rows): the phone glyph on a quiet tile. */
    public data object Device : IconStandIn {
        override val text: String = ""
    }

    /** The unattributed row's dashed tile with the info glyph (DESIGN.md, Remainder and gap). */
    public data object Gap : IconStandIn {
        override val text: String = ""
    }
}

/** The app's icon as the system draws it (DESIGN.md: no radius token applies), or [standIn]. */
@Composable
private fun AppIcon(
    icon: ImageBitmap?,
    standIn: IconStandIn,
) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(IconSize))
        return
    }
    val colors = CoulombTheme.colors
    val shape = RoundedCornerShape(IconCorner)
    val tile =
        when (standIn) {
            is IconStandIn.Initial, IconStandIn.Device -> Modifier.background(colors.surfaceRaised, shape)
            is IconStandIn.Remainder -> Modifier.border(Hairline, colors.hairline, shape)
            is IconStandIn.Group -> Modifier
            IconStandIn.Gap -> Modifier.dashedOutline(colors.textSecondary)
        }
    // At least 40 dp, and wider when a count outgrows it (DESIGN.md: text fits its container). The
    // stand-in is decoration: the row's name already says what it stands for.
    Box(
        Modifier
            .heightIn(min = IconSize)
            .widthIn(min = IconSize)
            .then(tile)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        val glyph =
            when (standIn) {
                // Lab `.unattributed`: the info glyph in the dashed tile.
                IconStandIn.Gap -> CoulombIcons.Info

                IconStandIn.Device -> CoulombIcons.Mobile

                else -> null
            }
        if (glyph != null) {
            Icon(painterResource(glyph), contentDescription = null, Modifier.size(GlyphSize), tint = colors.textSecondary)
        } else {
            Text(
                text = standIn.text,
                modifier = Modifier.padding(horizontal = CoulombSpacing.Space4),
                color = colors.textSecondary,
                style = CoulombTheme.typography.labelButton,
                maxLines = 1,
            )
        }
    }
}

/** A 1 dp dashed outline on the icon tile's 12 dp corners. */
private fun Modifier.dashedOutline(color: Color): Modifier =
    drawBehind {
        val stroke = Hairline.toPx()
        val inset = stroke / 2
        val corner = IconCorner.toPx() - inset
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(corner),
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH * stroke, DASH * stroke))),
        )
    }

/** Dash and gap lengths in hairlines. */
private const val DASH = 3f

/** SPEC 6.7's "low confidence" mark for a figure with quality below 0.5, in Caption beside it. */
@Composable
public fun LowConfidence(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.low_confidence),
        modifier = modifier,
        color = CoulombTheme.colors.textSecondary,
        style = CoulombTheme.typography.caption,
    )
}

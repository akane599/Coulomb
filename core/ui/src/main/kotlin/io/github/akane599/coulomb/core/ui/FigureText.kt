// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import io.github.akane599.coulomb.core.designsystem.component.RollingText
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.ui.format.Affixed
import io.github.akane599.coulomb.core.ui.format.Figure

/** A [Figure] as text around its number: the unit follows after a no-break space (SPEC 6.7). */
public fun Figure.affixed(): Affixed = Affixed(before = "", number = number, after = " " + unit)

/**
 * A number and its unit in one paragraph, the number in [numberStyle] and the affixes in
 * [unitStyle]. One paragraph lets the bidi algorithm keep "123,456 mAh" or Turkish "%74" whole
 * under an RTL layout, where separate texts in a row would be mirrored apart.
 *
 * A [rolling] figure rolls its digits in place when it changes (SPEC 11.6). It lays the affixes and
 * the digits out in the order of its first strong character, so it stays whole under RTL too, and
 * reads as one text.
 */
@Composable
public fun FigureText(
    figure: Affixed,
    numberStyle: TextStyle,
    unitStyle: TextStyle,
    modifier: Modifier = Modifier,
    numberColor: Color = CoulombTheme.colors.textPrimary,
    unitColor: Color = CoulombTheme.colors.textSecondary,
    rolling: Boolean = false,
) {
    if (rolling) {
        RollingFigure(figure, numberStyle, unitStyle, numberColor, unitColor, modifier)
        return
    }
    val text =
        remember(figure, numberStyle, unitStyle, numberColor, unitColor) {
            val unit = unitStyle.toSpanStyle().copy(color = unitColor)
            buildAnnotatedString {
                withStyle(unit) { append(figure.before) }
                withStyle(numberStyle.toSpanStyle().copy(color = numberColor)) { append(figure.number) }
                withStyle(unit) { append(figure.after) }
            }
        }
    Text(text, modifier, style = numberStyle)
}

@Composable
private fun RollingFigure(
    figure: Affixed,
    numberStyle: TextStyle,
    unitStyle: TextStyle,
    numberColor: Color,
    unitColor: Color,
    modifier: Modifier = Modifier,
) {
    val whole = figure.before + figure.number + figure.after
    val direction = remember(whole) { if (startsRightToLeft(whole)) LayoutDirection.Rtl else LayoutDirection.Ltr }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Row(modifier.clearAndSetSemantics { text = AnnotatedString(whole) }) {
            if (figure.before.isNotEmpty()) Text(figure.before, Modifier.alignByBaseline(), color = unitColor, style = unitStyle)
            RollingText(figure.number, numberStyle, numberColor, Modifier.alignByBaseline())
            if (figure.after.isNotEmpty()) Text(figure.after, Modifier.alignByBaseline(), color = unitColor, style = unitStyle)
        }
    }
}

/** True when [text]'s first strong character is right to left; a figure with none runs left to right. */
private fun startsRightToLeft(text: String): Boolean =
    text.firstNotNullOfOrNull {
        when (Character.getDirectionality(it)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> false
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
            else -> null
        }
    } ?: false

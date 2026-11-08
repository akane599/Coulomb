// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import io.github.akane599.coulomb.core.designsystem.theme.CoulombMotion
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.crossfadeTo

/**
 * A number whose digits roll in place when it changes (SPEC 11.6): each changed digit slides out
 * and its successor slides in, up for a larger digit and down for a smaller one. Slots count from
 * the last digit, so 99 to 100 rolls the ones and tens and brings in the hundreds. The first value
 * appears still; under reduced motion a change is a 150 ms crossfade. Tabular figures (SPEC 11.3)
 * keep every slot one width.
 *
 * Digits always run left to right, in any layout direction. Semantics belong to the caller, which
 * reads the whole figure once.
 */
@Composable
public fun RollingText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier) {
            text.forEachIndexed { index, char ->
                key(text.length - index) { RollingChar(char, style, color) }
            }
        }
    }
}

@Composable
private fun RollingChar(
    char: Char,
    style: TextStyle,
    color: Color,
) {
    val reduced = CoulombTheme.reducedMotion
    var shown by remember { mutableStateOf(char) }
    var leaving by remember { mutableStateOf<Char?>(null) }
    var upward by remember { mutableStateOf(true) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(char) {
        if (char == shown) return@LaunchedEffect
        // Set the start before the new character shows, so no frame draws it settled.
        progress.snapTo(0f)
        upward = !(char.isDigit() && shown.isDigit() && char < shown)
        leaving = shown
        shown = char
        if (reduced) progress.crossfadeTo(1f) else progress.animateTo(1f, CoulombMotion.spatial(CoulombMotion.FAST_STIFFNESS))
        leaving = null
    }
    Box(Modifier.clipToBounds()) {
        leaving?.let { old ->
            Text(
                old.toString(),
                Modifier.graphicsLayer {
                    if (reduced) {
                        alpha = 1f - progress.value
                    } else {
                        translationY = (if (upward) -size.height else size.height) * progress.value
                    }
                },
                color = color,
                style = style,
            )
        }
        Text(
            shown.toString(),
            Modifier.graphicsLayer {
                if (reduced) {
                    alpha = progress.value
                } else {
                    translationY = (if (upward) size.height else -size.height) * (1f - progress.value)
                }
            },
            color = color,
            style = style,
        )
    }
}

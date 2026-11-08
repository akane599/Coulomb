// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombMotion
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.crossfadeTo

/**
 * One block of a loading skeleton (SPEC 10.11): a static `hairline` shape where the content will
 * be. No shimmer: the Still Screen Rule allows no idle animation.
 */
@Composable
public fun SkeletonBlock(
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.height(height).background(CoulombTheme.colors.hairline, RoundedCornerShape(CoulombRadius.Chip)))
}

/**
 * Fades [content] in once when it first appears (SPEC 10.11: one fade-in), then holds still. The
 * fade is saved, so a return to the tab, a rotation or a theme change shows the content at once.
 */
@Composable
public fun FadeInOnce(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    val alpha = remember { Animatable(if (shown) 1f else 0f) }
    val reduced = CoulombTheme.reducedMotion
    LaunchedEffect(Unit) {
        if (shown) return@LaunchedEffect
        if (reduced) alpha.crossfadeTo(1f) else alpha.animateTo(1f, CoulombMotion.effects())
        shown = true
    }
    Box(
        modifier.graphicsLayer {
            this.alpha = alpha.value
            // Each child draws with the alpha applied, with no offscreen buffer for the whole screen.
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    ) { content() }
}

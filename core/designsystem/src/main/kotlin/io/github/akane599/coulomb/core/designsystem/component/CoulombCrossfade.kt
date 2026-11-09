// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import io.github.akane599.coulomb.core.designsystem.theme.CoulombMotion
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.crossfadeTo

/**
 * Swaps [content] for each [target] and fades the new content in (SPEC 11.6): on the effects spring,
 * or under reduced motion over 150 ms on the real clock, as [FadeInOnce] does, since Compose's own
 * transitions end at once then. The first content shows at once.
 */
@Composable
public fun <T> CoulombCrossfade(
    target: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val reduced = CoulombTheme.reducedMotion
    var faded by remember { mutableStateOf(target) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (faded != target) {
            alpha.snapTo(0f)
            faded = target
        }
        if (reduced) alpha.crossfadeTo(1f) else alpha.animateTo(1f, CoulombMotion.effects())
    }
    Box(
        modifier.graphicsLayer {
            // Hidden from the first frame of a new target, before the fade starts.
            this.alpha = if (faded == target) alpha.value else 0f
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    ) { key(target) { content(target) } }
}

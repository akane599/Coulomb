// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.chart

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import io.github.akane599.coulomb.core.designsystem.theme.CoulombMotion
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.crossfadeTo

/**
 * SPEC 11.6: a chart draws in once on first appearance, its data ink revealed from the left on the
 * slow spatial spring, then holds still; later data redraws without motion. Under reduced motion
 * the whole chart crossfades in over 150 ms instead. Reads happen in the draw and layer phases,
 * so the animation never recomposes.
 */
@Stable
internal class DrawIn(
    private val reveal: Animatable<Float, AnimationVector1D>,
    private val alpha: Animatable<Float, AnimationVector1D>,
) {
    /** The chart's fade; only moves under reduced motion. */
    fun Modifier.fade(): Modifier = graphicsLayer { this.alpha = this@DrawIn.alpha.value }

    /** Draws [block], the data ink, clipped to the part revealed so far. */
    fun DrawScope.revealed(block: DrawScope.() -> Unit) {
        val fraction = reveal.value
        if (fraction >= 1f) block() else clipRect(right = size.width * fraction, block = block)
    }
}

@Composable
internal fun rememberDrawIn(): DrawIn {
    val reduced = CoulombTheme.reducedMotion
    var drawn by rememberSaveable { mutableStateOf(false) }
    val reveal = remember { Animatable(if (drawn || reduced) 1f else 0f) }
    val alpha = remember { Animatable(if (drawn || !reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (drawn) return@LaunchedEffect
        if (reduced) alpha.crossfadeTo(1f) else reveal.animateTo(1f, CoulombMotion.spatial(CoulombMotion.SLOW_STIFFNESS))
        drawn = true
    }
    return remember(reveal, alpha) { DrawIn(reveal, alpha) }
}

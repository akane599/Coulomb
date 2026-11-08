// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import io.github.akane599.coulomb.core.designsystem.theme.CoulombMotion
import io.github.akane599.coulomb.core.designsystem.theme.LocalReducedMotion
import io.github.akane599.coulomb.core.designsystem.theme.crossfadeTo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * SPEC 11.6: a list reorder animates. When this element's place in its parent changes, it slides
 * from the old place to the new one on the default spatial spring; under reduced motion it
 * crossfades in at the new place over 150 ms instead. Its first placement holds still. Pair it with
 * `key(id)` so a row keeps its identity, and its state, when the list reorders.
 *
 * Only placement reads the animation, so a move re-places the row each frame without recomposing.
 */
public fun Modifier.animatePlacement(): Modifier = this then AnimatePlacementElement

private data object AnimatePlacementElement : ModifierNodeElement<AnimatePlacementNode>() {
    override fun create(): AnimatePlacementNode = AnimatePlacementNode()

    override fun update(node: AnimatePlacementNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "animatePlacement"
    }
}

private class AnimatePlacementNode :
    Modifier.Node(),
    LayoutModifierNode,
    CompositionLocalConsumerModifierNode {
    private var offset: Animatable<IntOffset, AnimationVector2D>? = null
    private val alpha = Animatable(1f)

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            // The parent has placed this node by now, so its place in the parent is known; there are
            // no coordinates while alignment lines are measured.
            val to = coordinates?.positionInParent()?.round()
            val shift = if (to == null) IntOffset.Zero else follow(to)
            placeable.placeWithLayer(shift) { alpha = this@AnimatePlacementNode.alpha.value }
        }
    }

    /** Starts a move toward [to] if it is new, and returns the offset from [to] to draw at now. */
    private fun follow(to: IntOffset): IntOffset {
        val current = offset ?: Animatable(to, IntOffset.VectorConverter).also { offset = it }
        if (current.targetValue == to) return current.value - to
        // Undispatched, so a snap lands before this frame draws.
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (currentValueOf(LocalReducedMotion)) {
                current.snapTo(to)
                alpha.snapTo(0f)
                alpha.crossfadeTo(1f)
            } else {
                current.animateTo(to, CoulombMotion.spatial())
            }
        }
        return current.value - to
    }
}

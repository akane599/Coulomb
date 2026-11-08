// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.material3.MotionScheme

/**
 * SPEC 11.6 springs: spatial damping inside 0.92–1.0 and effects at 1.0, so nothing visibly
 * bounces. At 0.95 the overshoot is below 0.01%. Stiffness starts at fast 1400, default 700 and
 * slow 300; tune by eye, keeping any overshoot invisible.
 *
 * [CoulombMotionScheme] carries the same springs into Material's components (ADR-0030).
 */
public object CoulombMotion {
    public const val SPATIAL_DAMPING: Float = 0.95f
    public const val EFFECTS_DAMPING: Float = 1f
    public const val FAST_STIFFNESS: Float = 1400f
    public const val DEFAULT_STIFFNESS: Float = 700f
    public const val SLOW_STIFFNESS: Float = 300f

    /** Movement: position, size, shape. */
    public fun <T> spatial(stiffness: Float = DEFAULT_STIFFNESS): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, stiffness)

    /** Colour and opacity. */
    public fun <T> effects(stiffness: Float = DEFAULT_STIFFNESS): FiniteAnimationSpec<T> = spring(EFFECTS_DAMPING, stiffness)
}

/** Material's motion slots on [CoulombMotion]'s springs, so Material components move like Coulomb's own. */
internal object CoulombMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = CoulombMotion.spatial()

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = CoulombMotion.spatial(CoulombMotion.FAST_STIFFNESS)

    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = CoulombMotion.spatial(CoulombMotion.SLOW_STIFFNESS)

    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = CoulombMotion.effects()

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = CoulombMotion.effects(CoulombMotion.FAST_STIFFNESS)

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = CoulombMotion.effects(CoulombMotion.SLOW_STIFFNESS)
}

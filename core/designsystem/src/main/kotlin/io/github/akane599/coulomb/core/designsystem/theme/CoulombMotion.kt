// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring

/**
 * SPEC 11.6 springs: spatial damping inside 0.92–1.0 and effects at 1.0, so nothing visibly
 * bounces. At 0.95 the overshoot is below 0.01%. Stiffness starts at fast 1400, default 700 and
 * slow 300; tune by eye, keeping any overshoot invisible.
 *
 * Material's `MotionScheme`, which would carry these into Material components, is internal in
 * material3 1.4.0; the custom scheme waits for the pre-stable pin SPEC 11.7 allows (P4).
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

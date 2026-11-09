// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.theme

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * At the shell: lets [content]'s screens share elements through [transition] (SPEC 11.6), or
 * stops them with null, as while list and detail sit side by side.
 */
@Composable
public fun ProvideSharedTransition(
    transition: SharedTransitionScope?,
    content: @Composable () -> Unit,
) {
    val scope = remember(transition) { transition?.let { SharedBoundsScope(it, null) } }
    CompositionLocalProvider(LocalSharedBounds provides scope, content = content)
}

/** At each screen: its own enter and exit, [visibility], which its shared elements move with. */
@Composable
public fun ProvideSharedVisibility(
    visibility: AnimatedVisibilityScope,
    content: @Composable () -> Unit,
) {
    val outer = LocalSharedBounds.current
    val scope = remember(outer, visibility) { outer?.let { SharedBoundsScope(it.transition, visibility) } }
    CompositionLocalProvider(LocalSharedBounds provides scope, content = content)
}

/**
 * The same element on two screens, such as an app's icon on its Drain row and on App detail,
 * moving from one place to the other on the spatial spring while the screens cross-fade. Without
 * a [key], outside [ProvideSharedTransition] and [ProvideSharedVisibility] or under reduced
 * motion, it stays where each screen puts it.
 */
@SuppressLint("ModifierFactoryExtensionFunction") // Starts a chain, as the screen's scopes are read here.
@Composable
public fun sharedElement(key: Any?): Modifier {
    val scope = LocalSharedBounds.current
    val visibility = scope?.visibility
    if (key == null || visibility == null || CoulombTheme.reducedMotion) return Modifier
    return with(scope.transition) {
        Modifier.sharedElement(rememberSharedContentState(key), visibility, boundsTransform = SpatialBounds)
    }
}

private val SpatialBounds = BoundsTransform { _, _ -> CoulombMotion.spatial() }

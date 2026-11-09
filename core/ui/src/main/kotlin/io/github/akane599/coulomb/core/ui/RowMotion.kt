// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.akane599.coulomb.core.designsystem.theme.CoulombMotion
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme

/**
 * A row's motion in a lazy list: a reorder slides on the default spatial spring, and a row added
 * by a tap or a new report fades in on the effects spring, so it never draws over the rows sliding
 * past it (SPEC 11.6; Drain's animate gate 2026-11-09). Under reduced motion the row takes its place at
 * once. `animateItem` is a member of the item scope, so this starts there rather than from a Modifier.
 */
@SuppressLint("ModifierFactoryExtensionFunction")
@Composable
public fun LazyItemScope.rowMotion(): Modifier {
    val reduced = CoulombTheme.reducedMotion
    return Modifier.animateItem(
        fadeInSpec = if (reduced) null else CoulombMotion.effects(),
        placementSpec = if (reduced) null else CoulombMotion.spatial(),
        fadeOutSpec = null,
    )
}

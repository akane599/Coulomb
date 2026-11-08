// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The window width classes of SPEC 10.10, at Material's breakpoints of 600 and 840 dp. */
public enum class WindowWidth {
    /** Below 600 dp: one pane and the bottom bar. */
    Compact,

    /** 600 to 839 dp: the rail and one pane. */
    Medium,

    /** 840 dp and up: the rail and list-detail panes. */
    Expanded,
}

private val MediumMin = 600.dp
private val ExpandedMin = 840.dp

/**
 * The current window's width class. It reads the window size that Material's adaptive library
 * reads (`LocalWindowInfo.containerSize`, adaptive 1.3.0 `currentWindowAdaptiveInfo`), so the shell
 * and the gutter switch at the same width as Material's own layouts.
 */
@Composable
@ReadOnlyComposable
public fun currentWindowWidth(): WindowWidth {
    val width =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width
                .toDp()
        }
    return when {
        width < MediumMin -> WindowWidth.Compact
        width < ExpandedMin -> WindowWidth.Medium
        else -> WindowWidth.Expanded
    }
}

/** The one gutter every element on a screen shares (SPEC 11.5): 16 dp on compact widths, 24 dp from medium up. */
@Composable
@ReadOnlyComposable
public fun currentGutter(): Dp =
    if (currentWindowWidth() == WindowWidth.Compact) CoulombSpacing.GutterCompact else CoulombSpacing.GutterMedium

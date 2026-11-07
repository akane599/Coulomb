// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The 4 dp spacing scale, gutters and touch target of SPEC 11.5. */
public object CoulombSpacing {
    public val Space4: Dp = 4.dp
    public val Space8: Dp = 8.dp
    public val Space12: Dp = 12.dp
    public val Space16: Dp = 16.dp
    public val Space24: Dp = 24.dp
    public val Space32: Dp = 32.dp
    public val Space48: Dp = 48.dp
    public val Space64: Dp = 64.dp

    /** Screen gutter on compact widths. */
    public val GutterCompact: Dp = 16.dp

    /** Screen gutter on medium widths and up. */
    public val GutterMedium: Dp = 24.dp

    /** The smallest touch target. */
    public val TouchTarget: Dp = 48.dp
}

/** Corner radii of SPEC 11.5. */
public object CoulombRadius {
    public val Chip: Dp = 6.dp
    public val Row: Dp = 12.dp
    public val Sheet: Dp = 20.dp
    public val Large: Dp = 28.dp
}

/**
 * Material 3's shape slots on Coulomb's radii. Bottom sheets take `extraLarge` in Material; Coulomb's
 * sheet wrapper sets [CoulombRadius.Sheet] itself.
 */
internal val CoulombShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(CoulombRadius.Chip),
        small = RoundedCornerShape(CoulombRadius.Chip),
        medium = RoundedCornerShape(CoulombRadius.Row),
        large = RoundedCornerShape(CoulombRadius.Sheet),
        extraLarge = RoundedCornerShape(CoulombRadius.Large),
    )

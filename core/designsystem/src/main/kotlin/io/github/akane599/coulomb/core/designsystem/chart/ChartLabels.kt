// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.chart

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.drawscope.DrawScope

/** A label of [width] centred on [x], moved inside the plot at either end. */
internal fun DrawScope.placeLabel(
    width: Float,
    x: Float,
): ClosedFloatingPointRange<Float> {
    val start = (x - width / 2).coerceIn(0f, (size.width - width).coerceAtLeast(0f))
    return start..(start + width)
}

internal fun ClosedFloatingPointRange<Float>.overlaps(
    other: ClosedFloatingPointRange<Float>,
    gap: Float,
): Boolean = start < other.endInclusive + gap && other.start < endInclusive + gap

/** A labelled value on an [IntervalScale]'s axis: the value, and an optional second line under it. */
@Immutable
public data class AxisLabel(
    val value: Double,
    val text: String,
    val sub: String? = null,
)

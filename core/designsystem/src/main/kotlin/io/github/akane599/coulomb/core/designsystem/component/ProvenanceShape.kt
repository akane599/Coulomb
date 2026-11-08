// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme

/** The four provenance shapes of DESIGN.md (Components, Provenance marks). They mark nothing else. */
public enum class ProvenanceShape {
    /** Measured: a solid dot. */
    Dot,

    /** Modeled: a ring. */
    Ring,

    /** Estimated: an interval bar with end stops. */
    Bracket,

    /** Derived: a half-filled dot. */
    HalfDot,
}

/** The side of a provenance glyph's box (DESIGN.md: 12 dp, not scaled with text). */
public val ProvenanceGlyphSize: Dp = 12.dp
private val GlyphStroke = 1.5.dp

// The lab's `p-*` symbols on their 12-unit grid.
private const val GRID = 12f
private const val CENTRE = 6f
private const val DOT_RADIUS = 4f
private const val RING_RADIUS = 3.5f
private const val BRACKET_START = 2f
private const val BRACKET_END = 10f
private const val STOP_TOP = 3.5f
private const val STOP_BOTTOM = 8.5f
private const val HALF_TURN = 180f
private const val TOP = -90f

/**
 * One provenance shape in a 12 dp box, in `text-secondary` with a 1.5 dp round-capped stroke
 * (DESIGN.md). It has no semantics of its own: the caller names it, as `ProvenanceMark` in
 * `:core:ui` does.
 */
@Composable
public fun ProvenanceGlyph(
    shape: ProvenanceShape,
    modifier: Modifier = Modifier,
) {
    val color = CoulombTheme.colors.textSecondary
    Canvas(modifier.size(ProvenanceGlyphSize)) {
        val u = size.minDimension / GRID
        val stroke = GlyphStroke.toPx()
        val centre = Offset(CENTRE * u, CENTRE * u)
        when (shape) {
            ProvenanceShape.Dot -> {
                drawCircle(color, radius = DOT_RADIUS * u, center = centre)
            }

            ProvenanceShape.Ring -> {
                drawCircle(color, radius = RING_RADIUS * u, center = centre, style = Stroke(stroke))
            }

            ProvenanceShape.Bracket -> {
                listOf(BRACKET_START, BRACKET_END).forEach { x ->
                    drawLine(color, Offset(x * u, STOP_TOP * u), Offset(x * u, STOP_BOTTOM * u), stroke, StrokeCap.Round)
                }
                drawLine(color, Offset(BRACKET_START * u, CENTRE * u), Offset(BRACKET_END * u, CENTRE * u), stroke, StrokeCap.Round)
            }

            ProvenanceShape.HalfDot -> {
                drawCircle(color, radius = RING_RADIUS * u, center = centre, style = Stroke(stroke))
                val corner = (CENTRE - RING_RADIUS) * u
                drawArc(
                    color = color,
                    startAngle = TOP,
                    sweepAngle = HALF_TURN,
                    useCenter = true,
                    topLeft = Offset(corner, corner),
                    size = Size(2 * RING_RADIUS * u, 2 * RING_RADIUS * u),
                )
            }
        }
    }
}

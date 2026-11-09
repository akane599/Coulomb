// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme

private val SegmentHeight = 4.dp
private val SegmentGap = 4.dp
private val SegmentEnd = 2.dp

/**
 * The onboarding stepper of DESIGN.md: [count] equal segments, 4 dp high and 4 dp apart, those up
 * to [current] (1-based) in `text-primary` and the rest in `hairline`, with [label] ("Step 4 of 4")
 * in Label, `text-secondary`, 8 dp below. The segments are decoration; the label carries the step.
 */
@Composable
public fun CoulombStepper(
    current: Int,
    count: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(SegmentHeight)) {
            val gap = SegmentGap.toPx()
            val width = (size.width - gap * (count - 1)) / count
            val end = CornerRadius(SegmentEnd.toPx())
            val rtl = layoutDirection == LayoutDirection.Rtl
            for (index in 0 until count) {
                val start = index * (width + gap)
                val x = if (rtl) size.width - start - width else start
                drawRoundRect(
                    color = if (index < current) colors.textPrimary else colors.hairline,
                    topLeft = Offset(x, 0f),
                    size = Size(width, size.height),
                    cornerRadius = end,
                )
            }
        }
        Spacer(Modifier.height(CoulombSpacing.Space8))
        Text(text = label, style = CoulombTheme.typography.label, color = colors.textSecondary)
    }
}

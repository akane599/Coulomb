// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.chart

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/** A chart's colours, read from the theme before drawing (DESIGN.md, Charts). */
internal data class ChartInk(
    /** Gridlines and the "now" rule. */
    val hairline: Color,
    /** Axes and ticks: `text-secondary`. */
    val axis: Color,
    /** The data line: `text-primary`. */
    val line: Color,
    /** The estimate's bracket and cone; never text. */
    val accent: Color,
)

/** Chart text in one style, drawn in sp at native size (ADR-0022). */
internal data class ChartText(
    val measurer: TextMeasurer,
    val style: TextStyle,
) {
    /**
     * One line, never wrapped: a chart label is a few characters, and Android can under-measure a
     * bidi line by a few pixels (Arabic-Indic digits beside Latin text in a right-to-left layout),
     * which would otherwise break it mid-word.
     */
    fun measure(text: String): TextLayoutResult = measurer.measure(text, style, overflow = TextOverflow.Visible, softWrap = false)
}

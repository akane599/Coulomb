// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.chart

import androidx.compose.runtime.Immutable

/** What a [DailyBarChart] writes: its unit line, the days at its ends and middle, and its values. */
@Immutable
public data class DailyBarLabels(
    /** The unit, above the plot ("mAh a day"). */
    val unit: String,
    val start: String,
    val middle: String,
    val end: String,
    /** A gridline's value. */
    val value: (Double) -> String,
    /** The marker's label, beside its rule; null without a marker. */
    val marker: String? = null,
)

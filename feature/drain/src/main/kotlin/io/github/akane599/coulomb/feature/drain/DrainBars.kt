// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp

// The reconcile bar and the component bar share one shape (DESIGN.md, Breakdown).

/** The bar's box: the bar and room above and below it. */
internal val BarBox = 18.dp

internal val BarHeight = 14.dp

/** The radius of a segment's ends. */
internal val BarEnd = 2.dp

/** The space between two segments. */
internal val SegmentGap = 2.dp

/**
 * A bar's semantics (SPEC 11.8): one summary of its figures, and the "View as table" action
 * ([viewTable] is its label), which opens the same figures as a table. The bars sum up figures
 * printed right beneath them, so they carry the action without a visible button (ADR-0036 §25).
 */
internal fun Modifier.chartSemantics(
    description: String,
    viewTable: String,
    onViewTable: () -> Unit,
): Modifier =
    clearAndSetSemantics {
        contentDescription = description
        customActions =
            listOf(
                CustomAccessibilityAction(viewTable) {
                    onViewTable()
                    true
                },
            )
    }

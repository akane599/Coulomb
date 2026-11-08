// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.github.akane599.coulomb.core.designsystem.component.ProvenanceGlyph
import io.github.akane599.coulomb.core.designsystem.component.ProvenanceGlyphSize
import io.github.akane599.coulomb.core.designsystem.component.ProvenanceShape
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import kotlinx.coroutines.launch

/** The shape that marks [this] provenance (DESIGN.md, Provenance marks; ADR-0023 §1). */
public val Provenance.shape: ProvenanceShape
    get() =
        when (this) {
            Provenance.MEASURED -> ProvenanceShape.Dot
            Provenance.MODELED -> ProvenanceShape.Ring
            Provenance.ESTIMATED -> ProvenanceShape.Bracket
            Provenance.DERIVED -> ProvenanceShape.HalfDot
        }

/** [this] provenance's name, which TalkBack reads after the figure. */
@get:StringRes
public val Provenance.label: Int
    get() =
        when (this) {
            Provenance.MEASURED -> R.string.provenance_measured
            Provenance.MODELED -> R.string.provenance_modeled
            Provenance.ESTIMATED -> R.string.provenance_estimated
            Provenance.DERIVED -> R.string.provenance_derived
        }

/** One line on what [this] provenance means, shown when the mark is explained. */
@get:StringRes
public val Provenance.explanation: Int
    get() =
        when (this) {
            Provenance.MEASURED -> R.string.provenance_measured_explained
            Provenance.MODELED -> R.string.provenance_modeled_explained
            Provenance.ESTIMATED -> R.string.provenance_estimated_explained
            Provenance.DERIVED -> R.string.provenance_derived_explained
        }

/**
 * The provenance mark after a figure (SPEC 6.7, 10.1). TalkBack reads its name, and its "Explain
 * provenance" action, like a long press on the mark, shows one line on what it means.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ProvenanceMark(
    provenance: Provenance,
    modifier: Modifier = Modifier,
) {
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val name = stringResource(provenance.label)
    val explain = stringResource(R.string.provenance_explain_action)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip(containerColor = CoulombTheme.colors.textPrimary, contentColor = CoulombTheme.colors.bg) {
                Text(stringResource(provenance.explanation), style = CoulombTheme.typography.caption)
            }
        },
        state = tooltip,
        modifier =
            modifier.semantics {
                contentDescription = name
                customActions = listOf(CustomAccessibilityAction(explain) { scope.launch { tooltip.show() }.let { true } })
            },
    ) {
        ProvenanceGlyph(provenance.shape, Modifier.padding(start = CoulombSpacing.Space4))
    }
}

private const val MARK = "provenance"

/**
 * [text] with its provenance mark inline after the last word. A word joiner keeps the mark on the
 * last word's line, so it never wraps alone and never gets squeezed out when the text wraps
 * (DESIGN.md: the mark sits after the figure it qualifies). The mark keeps its 12 dp at any text
 * size.
 */
@Composable
public fun MarkedText(
    text: String,
    provenance: Provenance,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    MarkedText(AnnotatedString(text), provenance, style, color, modifier)
}

/** [MarkedText] for text with spans, such as a figure set larger than the words around it. */
@Composable
public fun MarkedText(
    text: AnnotatedString,
    provenance: Provenance,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val placeholder =
        with(density) {
            Placeholder(
                width = (CoulombSpacing.Space4 + ProvenanceGlyphSize).toSp(),
                height = ProvenanceGlyphSize.toSp(),
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            )
        }
    Text(
        text =
            buildAnnotatedString {
                append(text)
                append(WORD_JOINER)
                appendInlineContent(MARK, alternateText = WORD_JOINER.toString())
            },
        modifier = modifier,
        color = color,
        style = style,
        inlineContent = mapOf(MARK to InlineTextContent(placeholder) { ProvenanceMark(provenance) }),
    )
}

private const val WORD_JOINER = '\u2060'

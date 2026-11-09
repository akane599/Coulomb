// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombTerm
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.component.hangVertically
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentSectionGap
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.MarkedText
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.R

// The text pieces of a detail page (App detail, Health), shared so each page reads alike.

/**
 * A section's heading with the provenance of all its figures (lab `.section-head` with its mark),
 * and either a [caption] on its baseline or an [action] at its end.
 */
@Composable
public fun SectionTitle(
    title: String,
    provenance: Provenance?,
    modifier: Modifier = Modifier,
    first: Boolean = false,
    caption: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    // The first section of the wide layout's second column lines up with the header beside it.
    val top = if (first) CoulombSpacing.Space8 else currentSectionGap()
    val block = modifier.fillMaxWidth().padding(top = top, bottom = CoulombSpacing.Space12)
    val text: @Composable (Modifier) -> Unit = { head ->
        if (provenance != null) {
            MarkedText(title, provenance, type.title, colors.textPrimary, head.semantics { heading() })
        } else {
            Text(title, head.semantics { heading() }, color = colors.textPrimary, style = type.title)
        }
    }
    if (caption != null) {
        // A caption, such as the window a section covers, sits on the title's first baseline.
        Row(block) {
            text(Modifier.weight(1f).wrapContentWidth(Alignment.Start).alignByBaseline())
            Spacer(Modifier.width(CoulombSpacing.Space16))
            Text(caption, Modifier.alignByBaseline(), color = colors.textSecondary, style = type.caption)
        }
        return
    }
    if (action == null) {
        text(block)
        return
    }
    Row(block, verticalAlignment = Alignment.CenterVertically) {
        // At the layout's start in either direction, whatever the title's script.
        text(Modifier.weight(1f).wrapContentWidth(Alignment.Start))
        Spacer(Modifier.width(CoulombSpacing.Space16))
        // A 48 dp target reaches past the title's line instead of setting the head's height.
        Box(Modifier.hangVertically(ActionHang)) { action() }
    }
}

/** Half of a 48 dp target less a 26 dp Title line, as in [io.github.akane599.coulomb.core.designsystem.component.CoulombSectionHead]. */
private val ActionHang = 11.dp

/** A table's or group's sub-heading (lab `.subhead`); a glossary term when it has a [definition]. */
@Composable
public fun Subhead(
    text: String,
    modifier: Modifier = Modifier,
    definition: String? = null,
    provenance: Provenance? = null,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val head = modifier.fillMaxWidth().padding(top = CoulombSpacing.Space24, bottom = CoulombSpacing.Space4).semantics { heading() }
    Row(head, verticalAlignment = Alignment.CenterVertically) {
        if (definition != null) {
            CoulombTerm(text, definition, stringResource(R.string.page_term_action), type.bodySubhead, colors.textPrimary)
        } else {
            Text(text, color = colors.textPrimary, style = type.bodySubhead)
        }
        provenance?.let { ProvenanceMark(it, Modifier.padding(start = CoulombSpacing.Space4)) }
    }
}

/** A line that qualifies a figure or table above it, in Caption, `text-secondary`. */
@Composable
public fun Note(
    text: String,
    modifier: Modifier = Modifier,
    top: Dp = CoulombSpacing.Space8,
) {
    Text(
        text,
        modifier.fillMaxWidth().padding(top = top),
        color = CoulombTheme.colors.textSecondary,
        style = CoulombTheme.typography.caption,
    )
}

/**
 * A line that stands in for a missing table, list or chart, in Body, `text-secondary`, as on Drain
 * (DESIGN.md: a line standing in for content is Body; a note qualifying one is Caption).
 */
@Composable
public fun StandIn(
    text: String,
    modifier: Modifier = Modifier,
    top: Dp = CoulombSpacing.Space8,
) {
    Text(
        text,
        modifier.fillMaxWidth().padding(top = top),
        color = CoulombTheme.colors.textSecondary,
        style = CoulombTheme.typography.body,
    )
}

/** "View as table" under an evidence chart (DESIGN.md, Charts). */
@Composable
public fun ViewTableButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CoulombTextButton(
        text = stringResource(R.string.page_view_table),
        onClick = onClick,
        modifier = modifier,
        edge = ButtonEdge.Start,
    )
}

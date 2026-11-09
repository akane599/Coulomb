// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.component.ProvenanceGlyph
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance

/**
 * The key to the four marks on the last onboarding step (SPEC 14.1, DESIGN.md), one line each: the
 * mark in a 24 dp column, centred on the first line, then its name in bold and what it means, in
 * Body. Each line is one TalkBack item.
 */
@Composable
public fun ProvenanceKey(modifier: Modifier = Modifier) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val lineHeight = with(LocalDensity.current) { type.body.lineHeight.toDp() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8)) {
        Provenance.entries.forEach { provenance ->
            val name = stringResource(provenance.label)
            val line = stringResource(R.string.provenance_key_line, name, stringResource(provenance.keyLine))
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Box(Modifier.width(MarkColumn).height(lineHeight), contentAlignment = Alignment.CenterStart) {
                    ProvenanceGlyph(provenance.shape)
                }
                Text(
                    buildAnnotatedString {
                        val start = line.indexOf(name)
                        append(line)
                        if (start >= 0) addStyle(SpanStyle(fontWeight = type.bodySubhead.fontWeight), start, start + name.length)
                    },
                    Modifier.weight(1f).padding(start = CoulombSpacing.Space16),
                    color = colors.textPrimary,
                    style = type.body,
                )
            }
        }
    }
}

private val MarkColumn = 24.dp

/** The key's one line on what [this] mark means. */
@get:StringRes
private val Provenance.keyLine: Int
    get() =
        when (this) {
            Provenance.MEASURED -> R.string.provenance_key_measured
            Provenance.MODELED -> R.string.provenance_key_modeled
            Provenance.ESTIMATED -> R.string.provenance_key_estimated
            Provenance.DERIVED -> R.string.provenance_key_derived
        }

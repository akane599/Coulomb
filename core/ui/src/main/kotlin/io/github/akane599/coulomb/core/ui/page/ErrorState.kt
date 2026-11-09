// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.component.CoulombOutlinedButton
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme

/**
 * The SPEC 10.11 error state every screen shares: what happened, what to do, and the button that
 * does it. A reading measure, so the message does not run across a tablet.
 */
@Composable
public fun ErrorState(
    title: String,
    body: String,
    action: String,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    Column(modifier.widthIn(max = ReadingWidth).padding(top = CoulombSpacing.Space24)) {
        Text(
            title,
            Modifier.fillMaxWidth().semantics { heading() },
            color = colors.textPrimary,
            style = CoulombTheme.typography.title,
        )
        Text(
            body,
            Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8),
            color = colors.textSecondary,
            style = CoulombTheme.typography.body,
        )
        CoulombOutlinedButton(text = action, onClick = retry, modifier = Modifier.padding(top = CoulombSpacing.Space16))
    }
}

private val ReadingWidth = 560.dp

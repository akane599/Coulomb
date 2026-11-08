// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.privilege

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.ui.R

/**
 * SPEC 10.11 partial: the missing capability, why it matters and the way to Privileges, in the SPEC
 * 4.5 copy. Screens that lose data to the issue show it where that data would be.
 */
@Composable
public fun PrivilegeIssueBlock(
    issue: PrivilegeIssue,
    openPrivileges: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = issue.copy()
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(copy.title), Modifier.semantics { heading() }, color = colors.textPrimary, style = type.bodySubhead)
        Text(stringResource(copy.body), Modifier.padding(top = CoulombSpacing.Space4), color = colors.textSecondary, style = type.body)
        CoulombTextButton(
            text = stringResource(R.string.privilege_open),
            onClick = openPrivileges,
            modifier = Modifier.padding(top = CoulombSpacing.Space4),
            edge = ButtonEdge.Start,
        )
    }
}

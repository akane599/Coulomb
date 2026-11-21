// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.akane599.coulomb.core.designsystem.component.CoulombSwitchRow
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.ui.page.DataRow

/**
 * SPEC 14.4 "Automation API": off until switched on here. The note names everything an allowed
 * app can do and read, the app names among it (ADR-0065 §7); once on, the row below it says which
 * apps the user has allowed, since Android's own permission page is where that is given and taken.
 */
@Composable
internal fun ColumnScope.Automation(
    on: Boolean,
    access: AutomationAccess?,
    onChange: (Boolean) -> Unit,
) {
    CoulombSwitchRow(
        stringResource(R.string.settings_automation),
        on,
        onChange,
        Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8),
        note = stringResource(R.string.settings_automation_note),
    )
    if (!on || access == null) return
    DataRow(
        name = stringResource(R.string.settings_automation_apps),
        detail =
            if (access.apps.isEmpty()) {
                stringResource(R.string.settings_automation_apps_none)
            } else {
                access.apps.reduce { joined, app -> stringResource(R.string.settings_automation_apps_joined, joined, app) }
            },
        value = access.apps.size.toString(),
        mark = null,
        share = null,
        first = true,
    )
}

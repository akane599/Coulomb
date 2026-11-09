// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.privilege

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.ui.R

/** Where one durable grant stands. */
public enum class GrantRowState { GRANTED, MISSING, LATER }

/** One SPEC 4.4 grant as the checklist shows it: its plain name, the permissions behind it and its state. */
public data class GrantRow(
    @param:StringRes val name: Int,
    val permissions: List<String>,
    val state: GrantRowState,
    @param:StringRes val why: Int? = null,
)

/**
 * The checklist rows for [grants], in the bootstrap's order. `WRITE_SECURE_SETTINGS` is asked only
 * with the Action Center (SPEC 13), so until then it reads "asked later" rather than missing.
 */
public fun grantRows(grants: GrantStatus): List<GrantRow> {
    fun state(granted: Boolean) = if (granted) GrantRowState.GRANTED else GrantRowState.MISSING
    return listOf(
        GrantRow(R.string.grant_battery_stats, listOf("BATTERY_STATS"), state(grants.batteryStats)),
        // System reports need DUMP and the PACKAGE_USAGE_STATS permission (DumpUtils); app usage
        // is the op alone, which the Settings screen for usage access turns on.
        GrantRow(R.string.grant_dump, listOf("DUMP", "PACKAGE_USAGE_STATS"), state(grants.dump && grants.usageStatsPermission)),
        GrantRow(R.string.grant_usage, listOf("GET_USAGE_STATS"), state(grants.usageStats)),
        GrantRow(R.string.grant_history, listOf("INTERACT_ACROSS_USERS"), state(grants.interactAcrossUsers)),
        GrantRow(
            R.string.grant_settings,
            listOf("WRITE_SECURE_SETTINGS"),
            if (grants.writeSecureSettings) GrantRowState.GRANTED else GrantRowState.LATER,
            why = R.string.grant_settings_why,
        ),
    )
}

/**
 * The grants checklist of the lab's result step: a hairline-ruled list, each row a 24 dp state icon
 * (a check in `good`, a cross or a lock in `text-secondary`), the grant's name in Data, an optional
 * reason in Caption and its permissions in Mono. TalkBack reads each row as one item with its state.
 */
@Composable
public fun GrantChecklist(
    rows: List<GrantRow>,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    Column(modifier.fillMaxWidth()) {
        rows.forEach { row ->
            HorizontalDivider(thickness = HairlineWidth, color = colors.hairline)
            GrantChecklistRow(row)
        }
        HorizontalDivider(thickness = HairlineWidth, color = colors.hairline)
    }
}

@Composable
private fun GrantChecklistRow(row: GrantRow) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val state = stringResource(row.state.label)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = CoulombSpacing.Space12)
            .semantics(mergeDescendants = true) { stateDescription = state },
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16),
    ) {
        Icon(
            painter = painterResource(row.state.icon),
            contentDescription = null,
            modifier = Modifier.size(IconSize),
            tint = if (row.state == GrantRowState.GRANTED) colors.good else colors.textSecondary,
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(row.name), color = colors.textPrimary, style = type.data)
            row.why?.let { Text(stringResource(it), color = colors.textSecondary, style = type.caption) }
            Text(row.permissions.joinToString(PERMISSION_SEPARATOR), color = colors.textSecondary, style = type.mono)
        }
    }
}

@get:DrawableRes
private val GrantRowState.icon: Int
    get() =
        when (this) {
            GrantRowState.GRANTED -> CoulombIcons.CheckCircle
            GrantRowState.MISSING -> CoulombIcons.Cancel
            GrantRowState.LATER -> CoulombIcons.Lock
        }

@get:StringRes
private val GrantRowState.label: Int
    get() =
        when (this) {
            GrantRowState.GRANTED -> R.string.grant_state_granted
            GrantRowState.MISSING -> R.string.grant_state_missing
            GrantRowState.LATER -> R.string.grant_state_later
        }

private val IconSize = 24.dp
private val HairlineWidth = 1.dp
private const val PERMISSION_SEPARATOR = " · "

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.datastore.UserSettings
import io.github.akane599.coulomb.core.designsystem.component.CoulombChoiceGroup
import io.github.akane599.coulomb.core.designsystem.component.CoulombSwitchRow
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle

/** SPEC 14.4 as built so far: the rest joins as each setting's feature lands (ADR-0047 §8). */
@Composable
public fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saveFailed by viewModel.saveFailed.collectAsStateWithLifecycle()
    val actions =
        remember(viewModel) {
            SettingsActions(viewModel::showSystemApps, viewModel::scaleToMeasured, viewModel::componentBreakdown, viewModel::chargeTarget)
        }
    // Nothing until the stored values are read, then one fade-in (SPEC 10.11).
    if (settings != null) FadeInOnce { SettingsContent(settings, actions, modifier, saveFailed) }
}

@Composable
internal fun SettingsContent(
    settings: UserSettings?,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    saveFailed: Boolean = false,
) {
    val gutter = currentGutter()
    Column(
        modifier
            .fillMaxSize()
            .background(CoulombTheme.colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(start = gutter, end = gutter, bottom = CoulombSpacing.Space48)
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = MaxWidth),
    ) {
        // Read in a moment; until then nothing shows rather than a default that flips.
        settings ?: return@Column
        if (saveFailed) {
            Note(stringResource(R.string.settings_save_failed), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        SectionTitle(stringResource(R.string.settings_drain), provenance = null, first = !saveFailed)
        Setting(stringResource(R.string.settings_show_system), settings.showSystemApps, actions.showSystemApps)
        Setting(
            stringResource(R.string.settings_scale),
            settings.scaleToMeasured,
            actions.scaleToMeasured,
            stringResource(R.string.settings_scale_note),
        )
        Target(settings.chargeTargetPct, actions.chargeTarget)
        SectionTitle(stringResource(R.string.settings_advanced), provenance = null)
        Setting(
            stringResource(R.string.settings_breakdown),
            settings.componentBreakdown,
            actions.componentBreakdown,
            stringResource(R.string.settings_breakdown_note),
        )
    }
}

@Composable
private fun Setting(
    text: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    note: String? = null,
) {
    // The note is part of the row, so TalkBack reads it with the switch it qualifies.
    CoulombSwitchRow(text, checked, onChange, Modifier.fillMaxWidth(), note = note)
}

/** ADR-0046 §4's group; a target set elsewhere in 50–100% joins it, so one choice is selected. */
@Composable
private fun ColumnScope.Target(
    targetPct: Int,
    onSelect: (Int) -> Unit,
) {
    val formats = rememberFormats()
    val targets = remember(targetPct) { (TARGETS + targetPct).distinct().sorted() }
    SectionTitle(stringResource(R.string.settings_target), provenance = null)
    CoulombChoiceGroup(
        options = targets.map(formats::percent),
        selected = targets.indexOf(targetPct),
        onSelect = { onSelect(targets[it]) },
    )
    Note(stringResource(R.string.settings_target_note))
}

private val TARGETS = listOf(80, 85, 90, 100)
private val MaxWidth = 720.dp

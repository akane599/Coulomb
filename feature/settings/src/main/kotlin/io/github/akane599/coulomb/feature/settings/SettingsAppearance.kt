// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.akane599.coulomb.core.datastore.ThemeChoice
import io.github.akane599.coulomb.core.designsystem.component.CoulombChoiceGroup
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.R as UiR

/** SPEC 14.4: Theme, Language and Units. Each is a label over its choices, as the alerts' thresholds are. */
@Composable
internal fun ColumnScope.Appearance(
    theme: ThemeChoice,
    language: AppLanguage,
    fahrenheit: Boolean,
    actions: SettingsActions,
) {
    Choice(
        stringResource(R.string.settings_theme),
        listOf(
            stringResource(R.string.settings_follow_system),
            stringResource(R.string.settings_theme_light),
            stringResource(R.string.settings_theme_dark),
            stringResource(R.string.settings_theme_black),
        ),
        selected = THEMES.indexOf(theme),
        onSelect = { actions.theme(THEMES[it]) },
    )
    Choice(
        stringResource(R.string.settings_language),
        // A language is named in itself, so it can be found by someone who reads no other.
        listOf(
            stringResource(R.string.settings_follow_system),
            stringResource(R.string.settings_language_tr),
            stringResource(R.string.settings_language_en),
        ),
        selected = language.ordinal,
        onSelect = { actions.language(AppLanguage.entries[it]) },
    )
    Choice(
        stringResource(R.string.settings_units),
        listOf(stringResource(UiR.string.unit_celsius), stringResource(UiR.string.unit_fahrenheit)),
        selected = if (fahrenheit) 1 else 0,
        onSelect = { actions.fahrenheit(it == 1) },
        note = stringResource(R.string.settings_units_note),
    )
}

/** A setting with a few values: its name over the choices, as the alerts' thresholds are, and what the choice means under them. */
@Composable
internal fun ColumnScope.Choice(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    note: String? = null,
) {
    Text(
        label,
        Modifier.padding(top = CoulombSpacing.Space16, bottom = CoulombSpacing.Space8),
        color = CoulombTheme.colors.textPrimary,
        style = CoulombTheme.typography.body,
    )
    CoulombChoiceGroup(options = options, selected = selected, onSelect = onSelect)
    if (note != null) Note(note)
}

private val THEMES = listOf(ThemeChoice.SYSTEM, ThemeChoice.LIGHT, ThemeChoice.DARK, ThemeChoice.BLACK)

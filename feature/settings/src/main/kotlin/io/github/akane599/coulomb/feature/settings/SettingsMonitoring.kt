// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.akane599.coulomb.core.data.SelfCost
import io.github.akane599.coulomb.core.datastore.UserSettings
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombSwitchRow
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.format.DASH
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.DataRow
import java.time.ZoneId
import java.time.ZoneOffset

/** Coulomb's own use as read (SPEC 6.9); [cost] is null while no two readings of the last day are stored. */
@Immutable
internal data class OwnUse(
    val cost: SelfCost?,
)

/** What Settings shows that is not a stored setting: the phone's and the build's own. */
@Immutable
internal data class SettingsDevice(
    val language: AppLanguage = AppLanguage.SYSTEM,
    /** Null until it is read. */
    val ownUse: OwnUse? = null,
    val version: String = "",
    val zone: ZoneId = ZoneOffset.UTC,
    val nowMs: Long = 0,
)

/**
 * SPEC 14.4: Monitoring mode, Screen-on sampling and Data retention, then SPEC 6.9's own use. Lite
 * runs no sampler, so it has no sampling rate to set.
 */
@Composable
internal fun ColumnScope.Monitoring(
    settings: UserSettings,
    device: SettingsDevice,
    actions: SettingsActions,
) {
    Choice(
        stringResource(R.string.settings_mode),
        listOf(stringResource(R.string.settings_mode_full), stringResource(R.string.settings_mode_lite)),
        selected = if (settings.liteMode) 1 else 0,
        onSelect = { actions.liteMode(it == 1) },
        note = stringResource(if (settings.liteMode) R.string.settings_mode_lite_note else R.string.settings_mode_full_note),
    )
    if (!settings.liteMode) {
        val formats = rememberFormats()
        val seconds = remember(settings.screenOnSamplingS) { (SAMPLING_S + settings.screenOnSamplingS).distinct().sorted() }
        Choice(
            stringResource(R.string.settings_sampling),
            seconds.map { stringResource(R.string.settings_sampling_seconds, formats.integer(it.toLong())) },
            selected = seconds.indexOf(settings.screenOnSamplingS),
            onSelect = { actions.screenOnSampling(seconds[it]) },
            note = stringResource(R.string.settings_sampling_note),
        )
    }
    CoulombSwitchRow(
        stringResource(R.string.settings_retention),
        settings.longRawRetention,
        actions.longRawRetention,
        Modifier.fillMaxWidth(),
        note = stringResource(R.string.settings_retention_note),
    )
    device.ownUse?.let { OwnUseRow(it, device, rememberFormats()) }
}

/** "Coulomb's own use": the share Android's power model gives Coulomb, marked as a model's figure. */
@Composable
private fun OwnUseRow(
    use: OwnUse,
    device: SettingsDevice,
    formats: Formats,
) {
    val cost = use.cost
    DataRow(
        name = stringResource(R.string.settings_own_use),
        detail =
            if (cost == null) {
                stringResource(R.string.settings_own_use_none)
            } else {
                stringResource(R.string.settings_own_use_since, formats.clock(cost.fromWallMs, device.zone, device.nowMs))
            },
        // Without a capacity there is no share to state; the charge itself is still known.
        value = cost?.let { it.pct?.let(formats::finePercent) ?: formats.mah(it.ownMah).joined() } ?: DASH,
        mark = cost?.let { Provenance.MODELED },
        share = null,
        first = true,
    )
}

/** The build's version; seven taps on it unlock the developer console (SPEC 14.4), which then has its link here. */
@Composable
internal fun About(
    version: String,
    console: Boolean,
    actions: SettingsActions,
) {
    DataRow(
        name = stringResource(R.string.settings_version),
        detail = if (console) stringResource(R.string.settings_console_on) else null,
        value = version,
        mark = null,
        share = null,
        first = true,
        onClick = actions.versionTap,
    )
    if (console) {
        CoulombTextButton(
            stringResource(R.string.settings_console_open),
            actions.openConsole,
            trailingIcon = CoulombIcons.ChevronRight,
            edge = ButtonEdge.Start,
        )
    }
}

private val SAMPLING_S = listOf(2, 5, 10, 30, 60)

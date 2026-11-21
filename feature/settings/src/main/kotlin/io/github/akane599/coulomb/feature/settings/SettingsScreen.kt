// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.datastore.UserSettings
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombChoiceGroup
import io.github.akane599.coulomb.core.designsystem.component.CoulombSwitchRow
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.DataRow
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import java.time.ZoneId
import java.time.ZoneOffset

/** SPEC 14.4 as built so far: the rest joins as each setting's feature lands (ADR-0047 §8). */
@Composable
public fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenActionCenter: () -> Unit = {},
    onOpenData: () -> Unit = {},
    onOpenConsole: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ownUse by viewModel.ownUse.collectAsStateWithLifecycle()
    val saveFailed by viewModel.saveFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // An alarm that is on while Coulomb may not post is not armed: the screen says so and asks (SPEC 14.1 lets the step be skipped).
    var allowed by remember { mutableStateOf(context.notificationsAllowed()) }
    var refused by rememberSaveable { mutableStateOf(false) }
    // Android holds the language; a change recreates the activity, which reads it again here.
    var language by remember { mutableStateOf(AppLanguage.of(context)) }
    val version = remember(context) { context.versionName() }
    // SPEC 14.4: the seventh tap on the version unlocks the developer console.
    var taps by remember { mutableIntStateOf(0) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            allowed = granted
            refused = !granted
        }
    // Who holds the automation permission is Android's to say, and changes on its permission page.
    val automationOn = settings?.automation == true
    var access by remember { mutableStateOf<AutomationAccess?>(null) }
    LifecycleResumeEffect(automationOn) {
        allowed = context.notificationsAllowed()
        access =
            if (automationOn) AutomationAccess(AutomationPermission.holders(context)) else null
        onPauseOrDispose {}
    }
    val actions =
        remember(viewModel, onOpenActionCenter, onOpenData, onOpenConsole, allowed, refused) {
            // Asked once in place; after a refusal the system's page is where it is changed.
            val ask = {
                if (refused) context.openNotificationSettings() else launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            val asking = { on: Boolean -> if (on && !allowed && !refused) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            SettingsActions(
                showSystemApps = viewModel::showSystemApps,
                scaleToMeasured = viewModel::scaleToMeasured,
                componentBreakdown = viewModel::componentBreakdown,
                chargeTarget = viewModel::chargeTarget,
                chargeAlarm = {
                    viewModel.chargeAlarm(it)
                    asking(it)
                },
                temperatureAlarm = {
                    viewModel.temperatureAlarm(it)
                    asking(it != null)
                },
                insightsDigest = {
                    viewModel.insightsDigest(it)
                    asking(it)
                },
                allowNotifications = ask,
                insightsDigestMinute = viewModel::insightsDigestMinute,
                openActionCenter = onOpenActionCenter,
                openData = onOpenData,
                theme = viewModel::theme,
                language = {
                    language = it
                    context.setAppLanguage(it)
                },
                fahrenheit = viewModel::fahrenheit,
                liteMode = viewModel::liteMode,
                screenOnSampling = viewModel::screenOnSampling,
                longRawRetention = viewModel::longRawRetention,
                versionTap = {
                    taps++
                    if (taps == CONSOLE_TAPS) viewModel.developerConsole(true)
                },
                openConsole = onOpenConsole,
                automation = viewModel::automation,
            )
        }
    // Nothing until the stored values are read, then one fade-in (SPEC 10.11).
    if (settings != null) {
        val device = SettingsDevice(language, ownUse, version, ZoneId.systemDefault(), System.currentTimeMillis(), access)
        FadeInOnce { SettingsContent(settings, actions, modifier, saveFailed, notificationsAllowed = allowed, device = device) }
    }
}

private fun Context.versionName(): String =
    try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (_: PackageManager.NameNotFoundException) {
        // Coulomb's own package is always found; the row would only be empty.
        ""
    }

private fun Context.notificationsAllowed(): Boolean = getSystemService(NotificationManager::class.java).areNotificationsEnabled()

private fun Context.openNotificationSettings() {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No such page on this build: the line stays, and the system's settings are still where it is changed.
    }
}

@Composable
internal fun SettingsContent(
    settings: UserSettings?,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    saveFailed: Boolean = false,
    notificationsAllowed: Boolean = true,
    device: SettingsDevice = SettingsDevice(),
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
            // The switch that flipped back may be a page further down: the reason comes to it.
            val requester = remember { BringIntoViewRequester() }
            LaunchedEffect(Unit) { requester.bringIntoView() }
            Note(
                stringResource(R.string.settings_save_failed),
                Modifier.bringIntoViewRequester(requester).semantics { liveRegion = LiveRegionMode.Polite },
            )
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
        SectionTitle(stringResource(R.string.settings_alerts), provenance = null)
        val posts = settings.chargeAlarm || settings.temperatureAlarmDeciC != null || settings.insightsDigest
        if (posts && !notificationsAllowed) NotificationsOff(actions.allowNotifications)
        if (settings.liteMode) Note(stringResource(R.string.settings_alerts_lite))
        Setting(
            stringResource(R.string.settings_charge_alarm),
            settings.chargeAlarm,
            actions.chargeAlarm,
            stringResource(R.string.settings_charge_alarm_note),
        )
        Temperature(settings.temperatureAlarmDeciC, actions.temperatureAlarm)
        Digest(settings.insightsDigest, settings.insightsDigestMinute, actions)
        SectionTitle(stringResource(R.string.settings_appearance), provenance = null)
        Appearance(settings.theme, device.language, settings.fahrenheit, actions)
        SectionTitle(stringResource(R.string.settings_monitoring), provenance = null)
        Monitoring(settings, device, actions)
        SectionTitle(stringResource(R.string.settings_advanced), provenance = null)
        Setting(
            stringResource(R.string.settings_breakdown),
            settings.componentBreakdown,
            actions.componentBreakdown,
            stringResource(R.string.settings_breakdown_note),
        )
        // SPEC 14.4 lists the Action Center here; its switch is on its own screen, with what turning it on grants.
        DataRow(
            name = stringResource(R.string.settings_action_center),
            detail = stringResource(R.string.settings_action_center_note),
            value = stringResource(if (settings.actionCenter) R.string.settings_on else R.string.settings_off),
            mark = null,
            share = null,
            first = true,
            modifier = Modifier.padding(top = CoulombSpacing.Space8),
        )
        // A link looks like one everywhere: a text button with its chevron.
        CoulombTextButton(
            stringResource(R.string.settings_action_center_open),
            actions.openActionCenter,
            trailingIcon = CoulombIcons.ChevronRight,
            edge = ButtonEdge.Start,
        )
        Automation(settings.automation, device.automation, actions.automation)
        // SPEC 14.5 on its own screen: three exports, an import and a deletion are more than rows here.
        SectionTitle(stringResource(R.string.settings_data), provenance = null)
        CoulombTextButton(
            stringResource(R.string.settings_data_open),
            actions.openData,
            trailingIcon = CoulombIcons.ChevronRight,
            edge = ButtonEdge.Start,
        )
        SectionTitle(stringResource(R.string.settings_about), provenance = null)
        About(device.version, settings.developerConsole, actions)
    }
}

/** The alarms and the digest are on and cannot be posted: said where they are set, with the way to allow them. */
@Composable
private fun NotificationsOff(allow: () -> Unit) {
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(
            stringResource(R.string.settings_notifications_off),
            color = CoulombTheme.colors.textPrimary,
            style = CoulombTheme.typography.body,
        )
        // It asks in place: no link icon (a chevron is another screen of Coulomb, open-in-new a page of the system's).
        CoulombTextButton(stringResource(R.string.settings_notifications_allow), allow, edge = ButtonEdge.Start)
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

/** SPEC 14.4: off, or a threshold from 38 to 50 °C. A stored one outside the offered joins them. */
@Composable
private fun ColumnScope.Temperature(
    deciC: Int?,
    onSelect: (Int?) -> Unit,
) {
    val formats = rememberFormats()
    val degrees = remember(deciC) { (TEMPERATURES + listOfNotNull(deciC)).distinct().sorted() }
    // One of the alerts: a label over its choices, not a section of its own.
    Text(
        stringResource(R.string.settings_temperature),
        Modifier.padding(top = CoulombSpacing.Space16, bottom = CoulombSpacing.Space8),
        color = CoulombTheme.colors.textPrimary,
        style = CoulombTheme.typography.body,
    )
    CoulombChoiceGroup(
        // Whole degrees as offered; a stored threshold between them, or any in °F, keeps its tenth.
        options = listOf(stringResource(R.string.settings_off)) + degrees.map { formats.threshold(it).joined() },
        selected = deciC?.let { degrees.indexOf(it) + 1 } ?: 0,
        onSelect = { onSelect(if (it == 0) null else degrees[it - 1]) },
    )
    Note(stringResource(if (deciC == null) R.string.settings_temperature_note_off else R.string.settings_temperature_note))
}

/** SPEC 14.4: on at 09:00, a time, or off. The times show only while the digest is on. */
@Composable
private fun ColumnScope.Digest(
    on: Boolean,
    minute: Int,
    actions: SettingsActions,
) {
    val formats = rememberFormats()
    val minutes = remember(minute) { (DIGEST_MINUTES + minute).distinct().sorted() }
    SectionTitle(stringResource(R.string.settings_digest_title), provenance = null)
    Setting(
        stringResource(R.string.settings_digest),
        on,
        actions.insightsDigest,
        stringResource(if (on) R.string.settings_digest_note else R.string.settings_digest_note_off),
    )
    if (on) {
        CoulombChoiceGroup(
            options = minutes.map { formats.clock(it * MS_PER_MINUTE, ZoneOffset.UTC) },
            selected = minutes.indexOf(minute),
            onSelect = { actions.insightsDigestMinute(minutes[it]) },
        )
    }
}

private const val CONSOLE_TAPS = 7
private val TARGETS = listOf(80, 85, 90, 100)
private val TEMPERATURES = listOf(380, 400, 420, 450, 500)
private val DIGEST_MINUTES = listOf(7 * 60, 9 * 60, 12 * 60, 18 * 60, 21 * 60)
private const val MS_PER_MINUTE = 60_000L
private val MaxWidth = 720.dp

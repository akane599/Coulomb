// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombFilledButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombOutlinedButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.WindowWidth
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.designsystem.theme.currentWindowWidth
import io.github.akane599.coulomb.core.model.Capability
import io.github.akane599.coulomb.core.model.CapabilityState
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.model.PrivilegeState
import io.github.akane599.coulomb.core.model.ServerInfo
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import io.github.akane599.coulomb.core.ui.page.StandIn
import io.github.akane599.coulomb.core.ui.page.Subhead
import io.github.akane599.coulomb.core.ui.privilege.AdbCommands
import io.github.akane599.coulomb.core.ui.privilege.AdbCommandsToggle
import io.github.akane599.coulomb.core.ui.privilege.GrantChecklist
import io.github.akane599.coulomb.core.ui.privilege.SystemScreen
import io.github.akane599.coulomb.core.ui.privilege.copy
import io.github.akane599.coulomb.core.ui.privilege.grantRows
import io.github.akane599.coulomb.core.ui.privilege.open
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import io.github.akane599.coulomb.core.ui.R as UiR

/** What the screen needs from the phone, apart from the model. Tests pass their own. */
@Immutable
internal data class PrivilegesDevice(
    val sdkInt: Int,
    /** The manager has an app to open (a fork or Shizuku, not Sui). */
    val managerApp: Boolean,
    /** The manager app's name, or null when it has none to show. */
    val managerLabel: String?,
    val nowMs: Long,
    val zone: ZoneId,
)

@Immutable
internal class PrivilegesActions(
    val allowShizuku: () -> Unit,
    val grant: () -> Unit,
    val rerun: () -> Unit,
    val export: () -> Unit,
    val open: (SystemScreen) -> Unit,
)

/** SPEC 14.3: the manager, the grants with their repair, and the Capability Matrix. */
@Composable
public fun PrivilegesScreen(
    modifier: Modifier = Modifier,
    viewModel: PrivilegesViewModel = hiltViewModel(),
) {
    val loaded by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // "Checked" names the day against now, read at each resume rather than at each recomposition.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(viewModel) {
        nowMs = System.currentTimeMillis()
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val title = stringResource(R.string.privileges_export_title)
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MARKDOWN)) { uri ->
            if (uri != null) viewModel.export(uri)
        }
    val manager = loaded?.privilege?.server?.managerPackage
    val label =
        remember(manager) {
            manager?.let { pkg ->
                runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrNull()
            }
        }
    val managerApp = remember(manager, loaded?.privilege?.manager) { viewModel.managerIntent() != null }
    val device =
        remember(managerApp, label, nowMs) {
            PrivilegesDevice(Build.VERSION.SDK_INT, managerApp, label, nowMs, ZoneId.systemDefault())
        }
    val actions =
        remember(viewModel) {
            PrivilegesActions(
                allowShizuku = viewModel::allowShizuku,
                grant = viewModel::grant,
                rerun = viewModel::rerun,
                export = {
                    viewModel.clearExport()
                    save.launch("$title ${LocalDate.now()}.md")
                },
                open = { context.open(it, viewModel::managerIntent) },
            )
        }
    // Nothing until the stored matrix is read, then one fade-in (SPEC 10.11).
    Box(modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
        loaded?.let { state -> FadeInOnce { PrivilegesContent(state, device, actions) } }
    }
}

@Composable
internal fun PrivilegesContent(
    state: PrivilegesUiState,
    device: PrivilegesDevice,
    actions: PrivilegesActions,
    modifier: Modifier = Modifier,
) {
    val gutter = currentGutter()
    BoxWithConstraints(modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
        val column = MinColumnWidth * LocalDensity.current.fontScale.coerceAtLeast(1f)
        // The row is capped, so the columns get the capped width, not the window's.
        val twoColumns = currentWindowWidth() != WindowWidth.Compact && min(maxWidth, MaxTwoColumnWidth) - gutter * GUTTERS >= column * 2
        // Above the branches, so a rotation or a fold that changes the layout keeps the place.
        val start = rememberScrollState()
        val end = rememberScrollState()
        if (!twoColumns) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(start)
                    .padding(start = gutter, end = gutter, bottom = Bottom)
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = MaxOneColumnWidth),
            ) {
                AccessAndGrants(state, device, actions)
                Matrix(state, device, actions)
            }
        } else {
            Row(
                Modifier
                    .widthIn(max = MaxTwoColumnWidth)
                    .fillMaxSize()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = gutter),
                horizontalArrangement = Arrangement.spacedBy(gutter),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(start)
                        .padding(bottom = Bottom),
                ) {
                    AccessAndGrants(state, device, actions)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(end)
                        .padding(bottom = Bottom),
                ) {
                    Matrix(state, device, actions, besideFirst = true)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AccessAndGrants(
    state: PrivilegesUiState,
    device: PrivilegesDevice,
    actions: PrivilegesActions,
) {
    state.privilege.issue?.let { Issue(it, state, device, actions) }
    Manager(state, device, actions, first = state.privilege.issue == null)
    Grants(state, actions)
}

/** SPEC 4.5: the one row that needs attention, with its fix in place. */
@Composable
private fun ColumnScope.Issue(
    issue: PrivilegeIssue,
    state: PrivilegesUiState,
    device: PrivilegesDevice,
    actions: PrivilegesActions,
) {
    val privilege = state.privilege
    val allowed = privilege.permission == ManagerPermission.GRANTED
    val copy = issue.copy(device.sdkInt, allowed)
    val colors = CoulombTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16)) {
        Text(
            stringResource(copy.title),
            Modifier.semantics { heading() },
            color = colors.textPrimary,
            style = CoulombTheme.typography.bodySubhead,
        )
        Text(
            stringResource(copy.body),
            Modifier.padding(top = CoulombSpacing.Space4),
            color = colors.textSecondary,
            style = CoulombTheme.typography.body,
        )
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space12),
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8),
        verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8),
    ) {
        val retry = copy.action == UiR.string.privilege_try_again
        when {
            retry -> {
                // A denial, or a hang below Android 17, where asking again can work.
                CoulombOutlinedButton(stringResource(UiR.string.privilege_try_again), actions.allowShizuku)
            }

            issue == PrivilegeIssue.MANAGER_NOT_INSTALLED || issue == PrivilegeIssue.AUTHORIZATION_HANG ||
                issue == PrivilegeIssue.SERVER_TOO_OLD -> {
                // SPEC 4.5: a compatible fork or Sui is the fix for a missing, hanging or old manager.
                CoulombOutlinedButton(stringResource(R.string.privileges_get_fork), {
                    actions.open(SystemScreen.FORK_PAGE)
                }, trailingIcon = CoulombIcons.OpenInNew)
                CoulombOutlinedButton(stringResource(R.string.privileges_get_sui), {
                    actions.open(SystemScreen.SUI_PAGE)
                }, trailingIcon = CoulombIcons.OpenInNew)
            }

            issue == PrivilegeIssue.MANAGER_STOPPED || issue == PrivilegeIssue.GRANTS_REVOKED -> {
                NextShizukuStep(issue, state, device, actions)
            }
        }
    }
    // SPEC 4.5: where Shizuku can't help, the commands show at once, copy-ready.
    if (state.showsCommands) AdbCommands(state.adbCommands)
}

/**
 * Shizuku stopped, or grants removed: revoked grants usually follow an update and a restart, which
 * stop Shizuku in shell mode, so the fix is the step the bootstrap is waiting for.
 */
@Composable
private fun NextShizukuStep(
    issue: PrivilegeIssue,
    state: PrivilegesUiState,
    device: PrivilegesDevice,
    actions: PrivilegesActions,
) {
    val privilege = state.privilege
    when {
        issue == PrivilegeIssue.GRANTS_REVOKED && state.canBootstrap -> {
            GrantButton(state, actions, filled = true)
        }

        privilege.manager == ManagerStatus.NOT_RUNNING && device.managerApp -> {
            CoulombFilledButton(stringResource(UiR.string.privilege_stopped_action), { actions.open(SystemScreen.SHIZUKU_APP) })
        }

        privilege.manager == ManagerStatus.RUNNING && privilege.permission.canAsk -> {
            CoulombFilledButton(stringResource(R.string.privileges_allow), actions.allowShizuku)
        }
    }
}

private val ManagerPermission.canAsk: Boolean
    get() = this != ManagerPermission.GRANTED && this != ManagerPermission.PENDING

/**
 * The issue's fix is the ADB commands, shown at once: a hang or a manager too old, unless Shizuku
 * allowed Coulomb and every grant is in place, so only the helper is missing.
 */
private val PrivilegesUiState.showsCommands: Boolean
    get() =
        when (privilege.issue) {
            PrivilegeIssue.SERVER_TOO_OLD -> true
            PrivilegeIssue.AUTHORIZATION_HANG -> !privilege.grants.durableCore
            else -> false
        }

/** The manager and the helper process (SPEC 14.3: Shizuku, Sui or a fork, version, uid). */
@Composable
private fun ColumnScope.Manager(
    state: PrivilegesUiState,
    device: PrivilegesDevice,
    actions: PrivilegesActions,
    first: Boolean,
) {
    val privilege = state.privilege
    val server = privilege.server.takeIf { privilege.manager == ManagerStatus.RUNNING }
    SectionTitle(stringResource(R.string.privileges_access), provenance = null, first = first)
    Facts {
        Fact(stringResource(R.string.privileges_level), stringResource(privilege.tier.label))
        Fact(stringResource(R.string.privileges_manager), managerName(privilege.manager, server, device.managerLabel))
        server?.managerVersionName?.let { Fact(stringResource(R.string.privileges_version), it, latin = true) }
        server?.let {
            Fact(stringResource(R.string.privileges_api), it.apiVersion.toString(), latin = true)
            Fact(stringResource(R.string.privileges_runs_as), runsAs(it), latin = true)
        }
        Fact(stringResource(R.string.privileges_helper), stringResource(privilege.helperLabel()))
    }
    val ask =
        privilege.issue == null &&
            privilege.manager == ManagerStatus.RUNNING &&
            privilege.permission != ManagerPermission.GRANTED &&
            privilege.permission != ManagerPermission.PENDING
    if (ask) {
        CoulombFilledButton(
            stringResource(R.string.privileges_allow),
            actions.allowShizuku,
            Modifier.padding(top = CoulombSpacing.Space16),
        )
    }
    // SPEC 3, ADR-0028: once, quietly, where the official build's hang is known.
    val saidAlready = privilege.issue == PrivilegeIssue.AUTHORIZATION_HANG
    if (device.sdkInt >= ANDROID_17 && privilege.permission != ManagerPermission.GRANTED && !saidAlready) {
        Note(stringResource(R.string.privileges_android17), top = CoulombSpacing.Space12)
    }
}

@Composable
private fun managerName(
    status: ManagerStatus,
    server: ServerInfo?,
    label: String?,
): String =
    when {
        status == ManagerStatus.UNKNOWN -> stringResource(R.string.privileges_checking)
        status == ManagerStatus.NOT_INSTALLED -> stringResource(R.string.privileges_manager_none)
        status == ManagerStatus.NOT_RUNNING -> stringResource(R.string.privileges_manager_stopped)
        server?.isSui == true -> stringResource(R.string.privileges_manager_sui)
        else -> label ?: stringResource(R.string.privileges_manager_shizuku)
    }

@Composable
private fun runsAs(server: ServerInfo): String =
    when {
        server.isRoot -> stringResource(R.string.privileges_runs_root, server.uid.toString())
        server.isShell -> stringResource(R.string.privileges_runs_shell, server.uid.toString())
        else -> stringResource(R.string.privileges_runs_other, server.uid.toString())
    }

/** SPEC 14.3: each grant with its state and a repair button. */
@Composable
private fun ColumnScope.Grants(
    state: PrivilegesUiState,
    actions: PrivilegesActions,
) {
    val grants = state.privilege.grants
    SectionTitle(stringResource(R.string.privileges_grants), provenance = null)
    Note(
        stringResource(
            when {
                grants.durableCore -> R.string.privileges_grants_durable
                state.canBootstrap -> R.string.privileges_grants_missing
                else -> R.string.privileges_grants_missing_adb
            },
        ),
        Modifier.padding(bottom = CoulombSpacing.Space8),
        top = 0.dp,
    )
    GrantChecklist(grantRows(grants))
    if (state.grantFailed && !grants.durableCore) {
        Note(stringResource(R.string.privileges_grant_failed), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    // The issue block offers the bootstrap for revoked grants; one button for it is enough.
    if (!grants.durableCore && state.canBootstrap && state.privilege.issue != PrivilegeIssue.GRANTS_REVOKED) {
        GrantButton(state, actions, filled = false, Modifier.padding(top = CoulombSpacing.Space16))
    }
    if (!grants.usageStats) {
        CoulombTextButton(
            stringResource(R.string.privileges_usage_settings),
            { actions.open(SystemScreen.USAGE_ACCESS) },
            Modifier.padding(top = CoulombSpacing.Space8),
            trailingIcon = CoulombIcons.OpenInNew,
            edge = ButtonEdge.Start,
        )
    }
    if (!grants.durableCore && !state.showsCommands) AdbCommandsToggle(state.adbCommands)
}

@Composable
private fun GrantButton(
    state: PrivilegesUiState,
    actions: PrivilegesActions,
    filled: Boolean,
    modifier: Modifier = Modifier,
) {
    val text = stringResource(if (state.granting) R.string.privileges_granting else R.string.privileges_grant_again)
    val enabled = !state.granting
    if (filled) {
        CoulombFilledButton(text, actions.grant, modifier, enabled = enabled)
    } else {
        CoulombOutlinedButton(text, actions.grant, modifier, enabled = enabled)
    }
}

/** A failed check, or no results yet: what stands above the matrix, or in its place. */
@Composable
private fun ColumnScope.MatrixStatus(state: PrivilegesUiState) {
    val empty = state.capabilities.isEmpty()
    if (state.probeFailed) {
        Note(
            stringResource(if (empty) R.string.privileges_probe_failed_first else R.string.privileges_probe_failed),
            Modifier.padding(bottom = CoulombSpacing.Space8).semantics { liveRegion = LiveRegionMode.Polite },
            top = 0.dp,
        )
    }
    if (empty && (state.probing || !state.probeFailed)) {
        StandIn(stringResource(if (state.probing) R.string.privileges_probing else R.string.privileges_never_probed), top = 0.dp)
    }
}

/** SPEC 4.6: the full matrix by tier, when it was checked, "Re-run probes" and the report. */
@Composable
private fun ColumnScope.Matrix(
    state: PrivilegesUiState,
    device: PrivilegesDevice,
    actions: PrivilegesActions,
    besideFirst: Boolean = false,
) {
    val formats = rememberFormats()
    val checked =
        state.probedAtWallMs?.let { at ->
            val day = Instant.ofEpochMilli(at).atZone(device.zone).toLocalDate()
            val today = Instant.ofEpochMilli(device.nowMs).atZone(device.zone).toLocalDate()
            stringResource(R.string.privileges_last_probe, if (day == today) formats.clock(at, device.zone) else formats.day(day, today))
        }
    SectionTitle(stringResource(R.string.privileges_matrix), provenance = null, first = besideFirst, caption = checked)
    MatrixStatus(state)
    state.groups.forEach { (tier, rows) ->
        Subhead(stringResource(tier.group))
        Column(Modifier.fillMaxWidth()) {
            rows.forEach { row ->
                HorizontalDivider(thickness = Hairline, color = CoulombTheme.colors.hairline)
                CapabilityRow(row, state.privilege, stale = state.probeFailed)
            }
            HorizontalDivider(thickness = Hairline, color = CoulombTheme.colors.hairline)
        }
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16),
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8),
        verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8),
    ) {
        CoulombOutlinedButton(
            stringResource(if (state.probing) R.string.privileges_probing else R.string.privileges_rerun),
            actions.rerun,
            enabled = !state.probing,
        )
        if (state.capabilities.isNotEmpty()) CoulombOutlinedButton(stringResource(R.string.privileges_export), actions.export)
    }
    state.export?.let {
        Note(
            stringResource(if (it == ExportResult.SAVED) R.string.privileges_export_done else R.string.privileges_export_failed),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * One capability: its name and state, then what would fix it and the probe's own key and reason.
 * A fix the live [privilege] state has already met says the row is out of date instead; a [stale]
 * matrix (the last run failed) shows every state in `text-secondary`.
 */
@Composable
private fun CapabilityRow(
    row: Capability,
    privilege: PrivilegeState,
    stale: Boolean,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = CoulombSpacing.Space12)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16)) {
            Text(
                stringResource(row.id.label),
                Modifier.weight(1f).alignByBaseline().wrapContentWidth(Alignment.Start),
                color = colors.textPrimary,
                style = type.data,
            )
            Text(
                stringResource(row.state.label),
                Modifier.alignByBaseline(),
                color = if (row.state == CapabilityState.AVAILABLE && !stale) colors.textPrimary else colors.textSecondary,
                style = type.label,
            )
        }
        val fix = row.fixAction?.takeIf { row.state != CapabilityState.AVAILABLE }
        if (fix != null) {
            val text = if (fix.doneIn(privilege)) R.string.privileges_fix_changed else fix.label
            Text(stringResource(text), color = colors.textSecondary, style = type.caption)
        }
        val evidence = listOfNotNull(row.id.key, row.reason).joinToString(EVIDENCE_SEPARATOR)
        Text(evidence, color = colors.textSecondary, style = type.mono.copy(textDirection = TextDirection.Ltr))
    }
}

/** Label and value rows between hairlines (lab `.facts`). */
@Composable
private fun Facts(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        content()
        HorizontalDivider(thickness = Hairline, color = CoulombTheme.colors.hairline)
    }
}

@Composable
private fun ColumnScope.Fact(
    label: String,
    value: String,
    latin: Boolean = false,
) {
    val colors = CoulombTheme.colors
    HorizontalDivider(thickness = Hairline, color = colors.hairline)
    Row(
        Modifier.fillMaxWidth().padding(vertical = CoulombSpacing.Space12).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16),
    ) {
        Text(
            label,
            Modifier.weight(1f).alignByBaseline().wrapContentWidth(Alignment.Start),
            color = colors.textSecondary,
            style = CoulombTheme.typography.body,
        )
        // At the row's end in either direction, whatever the value's script; a long one wraps in its half.
        Text(
            value,
            Modifier.weight(1f).alignByBaseline().wrapContentWidth(Alignment.End),
            color = colors.textPrimary,
            style = if (latin) CoulombTheme.typography.data.copy(textDirection = TextDirection.Ltr) else CoulombTheme.typography.data,
        )
    }
}

private const val MARKDOWN = "text/markdown"
private const val ANDROID_17 = 37
private const val GUTTERS = 3
private const val EVIDENCE_SEPARATOR = " · "
private val MinColumnWidth = 320.dp
private val MaxTwoColumnWidth = 1200.dp
private val MaxOneColumnWidth = 720.dp
private val Bottom = CoulombSpacing.Space48
private val Hairline = 1.dp

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombFilledButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombOutlinedButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.WindowWidth
import io.github.akane599.coulomb.core.designsystem.theme.currentWindowWidth
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.ServerInfo
import io.github.akane599.coulomb.core.ui.MarkedText
import io.github.akane599.coulomb.core.ui.ProvenanceKey
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import io.github.akane599.coulomb.core.ui.page.StandIn
import io.github.akane599.coulomb.core.ui.privilege.AdbCommands
import io.github.akane599.coulomb.core.ui.privilege.AdbCommandsToggle
import io.github.akane599.coulomb.core.ui.privilege.GrantChecklist
import io.github.akane599.coulomb.core.ui.privilege.SystemScreen
import io.github.akane599.coulomb.core.ui.privilege.capabilitySummary
import io.github.akane599.coulomb.core.ui.privilege.copy
import io.github.akane599.coulomb.core.ui.privilege.grantRows
import io.github.akane599.coulomb.core.ui.privilege.railsSentence
import io.github.akane599.coulomb.core.ui.privilege.sightSentence
import io.github.akane599.coulomb.core.ui.R as UiR

/** Step 1: one sentence of value, then what each choice gives (SPEC 14.1). */
@Composable
internal fun ColumnScope.WelcomeStep() {
    StepHead(stringResource(R.string.onboarding_welcome_title), stringResource(R.string.onboarding_welcome_lede))
    Block(R.string.onboarding_welcome_shizuku_title, R.string.onboarding_welcome_shizuku_body)
    Block(R.string.onboarding_welcome_basic_title, R.string.onboarding_welcome_basic_body)
}

/** Step 2: notifications, and usage access in basic mode; both skippable (SPEC 14.1, ADR-0047 §3). */
@Composable
internal fun ColumnScope.PermissionsStep(
    state: OnboardingUiState,
    device: OnboardingDevice,
    actions: OnboardingActions,
) {
    val basic = state.path == OnboardingPath.BASIC
    StepHead(
        stringResource(if (basic) R.string.onboarding_permissions_title_basic else R.string.onboarding_permissions_title_shizuku),
        stringResource(if (basic) R.string.onboarding_permissions_lede_basic else R.string.onboarding_permissions_lede_shizuku),
    )
    PermissionBlock(
        name = R.string.onboarding_notifications_name,
        why = R.string.onboarding_notifications_why,
        granted = device.notificationsAllowed,
        action = if (device.notificationsDenied) R.string.onboarding_notifications_settings else R.string.onboarding_allow,
        external = device.notificationsDenied,
        onClick = {
            if (device.notificationsDenied) actions.open(SystemScreen.NOTIFICATION_SETTINGS) else actions.requestNotifications()
        },
    )
    if (basic) {
        PermissionBlock(
            name = R.string.onboarding_usage_name,
            why = R.string.onboarding_usage_why,
            granted = state.privilege.grants.usageStats,
            action = R.string.onboarding_usage_open,
            external = true,
            onClick = { actions.open(SystemScreen.USAGE_ACCESS) },
        )
    } else {
        Note(stringResource(R.string.onboarding_usage_with_shizuku), top = CoulombSpacing.Space24)
    }
}

/** Step 3: Shizuku, by what is found (SPEC 14.1 step 3, 4.5). */
@Composable
internal fun ColumnScope.ShizukuStep(
    state: OnboardingUiState,
    device: OnboardingDevice,
    actions: OnboardingActions,
) {
    StepHead(stringResource(R.string.onboarding_shizuku_title), stringResource(R.string.onboarding_shizuku_lede))
    // Each stage replaces the last one in place, so TalkBack says what changed.
    Column(Modifier.padding(top = CoulombSpacing.Space8).semantics { liveRegion = LiveRegionMode.Polite }) {
        when (state.stage) {
            ShizukuStage.CHECKING -> StandIn(stringResource(R.string.onboarding_shizuku_checking), top = CoulombSpacing.Space16)
            ShizukuStage.NOT_INSTALLED -> NotInstalled(state.adbCommands, actions)
            ShizukuStage.STOPPED -> Stopped(device.managerApp, actions)
            ShizukuStage.ASK -> Ask(state.privilege.server, actions)
            ShizukuStage.WAITING -> StandIn(stringResource(R.string.onboarding_shizuku_waiting), top = CoulombSpacing.Space16)
            ShizukuStage.PROBLEM -> Problem(state, device, actions)
            ShizukuStage.GRANTING -> StandIn(stringResource(R.string.onboarding_shizuku_granting), top = CoulombSpacing.Space16)
            ShizukuStage.CONNECTED -> Connected(state.privilege.server)
        }
    }
    // SPEC 3, ADR-0028: once, quietly, where the official build's hang is known.
    if (device.sdkInt >= ANDROID_17 && state.stage in QuietNoteStages) {
        Note(stringResource(R.string.onboarding_android17), top = CoulombSpacing.Space24)
    }
}

@Composable
private fun ColumnScope.NotInstalled(
    commands: List<String>,
    actions: OnboardingActions,
) {
    Block(R.string.onboarding_shizuku_missing_title, R.string.onboarding_shizuku_missing_body)
    ManagerLinks(actions)
    AdbCommandsToggle(commands, Modifier.padding(top = CoulombSpacing.Space8))
}

/** The thedjchi fork and Sui on GitHub, opened in the browser (ADR-0047 implementation notes). */
@Composable
private fun ManagerLinks(actions: OnboardingActions) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space12),
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8),
        verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8),
    ) {
        CoulombOutlinedButton(stringResource(R.string.onboarding_get_fork), {
            actions.open(SystemScreen.FORK_PAGE)
        }, trailingIcon = CoulombIcons.OpenInNew)
        CoulombOutlinedButton(stringResource(R.string.onboarding_get_sui), {
            actions.open(SystemScreen.SUI_PAGE)
        }, trailingIcon = CoulombIcons.OpenInNew)
    }
}

@Composable
private fun ColumnScope.Stopped(
    managerApp: Boolean,
    actions: OnboardingActions,
) {
    Block(UiR.string.privilege_stopped_title, UiR.string.privilege_stopped_body)
    if (managerApp) {
        CoulombFilledButton(
            stringResource(UiR.string.privilege_stopped_action),
            { actions.open(SystemScreen.SHIZUKU_APP) },
            Modifier.padding(top = CoulombSpacing.Space12),
        )
    }
}

@Composable
private fun ColumnScope.Ask(
    server: ServerInfo?,
    actions: OnboardingActions,
) {
    val mode = server?.mode()?.let { stringResource(it) + " " }.orEmpty()
    Block(stringResource(R.string.onboarding_shizuku_ask_title), mode + stringResource(R.string.onboarding_shizuku_ask_body))
    CoulombFilledButton(
        stringResource(R.string.onboarding_shizuku_allow),
        actions.allowShizuku,
        Modifier.padding(top = CoulombSpacing.Space12),
    )
}

@Composable
private fun ColumnScope.Problem(
    state: OnboardingUiState,
    device: OnboardingDevice,
    actions: OnboardingActions,
) {
    val problem = state.privilege.problem() ?: return
    val copy = problem.copy(device.sdkInt)
    Block(copy.title, copy.body)
    copy.action?.let {
        CoulombOutlinedButton(stringResource(it), actions.allowShizuku, Modifier.padding(top = CoulombSpacing.Space12))
    }
    // SPEC 4.5: a manager that hangs or is too old is fixed by a compatible fork or Sui; below
    // Android 17 a hang offers a retry instead.
    val hangOrOld = problem == PrivilegeIssue.AUTHORIZATION_HANG || problem == PrivilegeIssue.SERVER_TOO_OLD
    if (hangOrOld && copy.action == null) ManagerLinks(actions)
    // SPEC 14.1: on a hang or a failure the commands show at once, copy-ready.
    AdbCommands(state.adbCommands, Modifier.padding(top = CoulombSpacing.Space24))
}

@Composable
private fun Connected(server: ServerInfo?) {
    val colors = CoulombTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space24).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16),
    ) {
        Icon(painterResource(CoulombIcons.CheckCircle), null, Modifier.size(StateIcon), tint = colors.good)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.onboarding_shizuku_connected),
                color = colors.textPrimary,
                style = CoulombTheme.typography.bodySubhead,
            )
            server?.mode()?.let { Text(stringResource(it), color = colors.textSecondary, style = CoulombTheme.typography.body) }
        }
    }
}

/** Step 4: what Coulomb can see, keeping it running, the grants and the key to the marks (SPEC 14.1, lab). */
@Composable
internal fun ColumnScope.ResultStep(
    state: OnboardingUiState,
    device: OnboardingDevice,
    actions: OnboardingActions,
) {
    ResultHead(state, actions)
    if (device.oneUi) KeepRunning(actions)
    Grants(state, actions)
    SectionTitle(stringResource(R.string.onboarding_marks_title), provenance = null)
    ProvenanceKey()
    Note(stringResource(R.string.onboarding_marks_note), top = CoulombSpacing.Space12)
}

@Composable
private fun ColumnScope.ResultHead(
    state: OnboardingUiState,
    actions: OnboardingActions,
) {
    val colors = CoulombTheme.colors
    when (val result = state.result) {
        ResultState.Checking -> {
            StepHead(stringResource(R.string.onboarding_result_checking_title), stringResource(R.string.onboarding_result_checking))
        }

        ResultState.Failed -> {
            StepHead(
                stringResource(R.string.onboarding_result_failed_title),
                stringResource(R.string.onboarding_result_failed),
            )
            CoulombOutlinedButton(
                stringResource(UiR.string.privilege_try_again),
                actions.retryProbe,
                Modifier.padding(top = CoulombSpacing.Space16),
            )
        }

        is ResultState.Ready -> {
            val summary = capabilitySummary(result.capabilities)
            val sight = sightSentence(summary.sights).orEmpty()
            StepTitle(stringResource(if (summary.deep) R.string.onboarding_result_deep_title else R.string.onboarding_result_basic_title))
            val lede = Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space12)
            if (summary.deep) {
                // The rails sentence ends on its source's mark, then the full stop (lab).
                MarkedText(
                    text = "$sight ${stringResource(railsSentence(summary))}",
                    provenance = if (summary.railsExposed) Provenance.MEASURED else Provenance.MODELED,
                    style = CoulombTheme.typography.body,
                    color = colors.textPrimary,
                    modifier = lede,
                    suffix = stringResource(R.string.onboarding_full_stop),
                )
            } else {
                Text(
                    listOf(sight, stringResource(R.string.onboarding_result_basic_more)).filter { it.isNotEmpty() }.joinToString(" "),
                    lede,
                    color = colors.textPrimary,
                    style = CoulombTheme.typography.body,
                )
            }
        }
    }
}

/** The lab's `.callout`: hairlines above and below, a subhead, one sentence and the way there (SPEC 14.2). */
@Composable
private fun KeepRunning(actions: OnboardingActions) {
    val colors = CoulombTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space24)) {
        HorizontalDivider(thickness = Hairline, color = colors.hairline)
        Column(Modifier.padding(vertical = CoulombSpacing.Space16)) {
            Block(R.string.onboarding_keep_running_title, R.string.onboarding_keep_running_body, top = 0.dp)
            CoulombOutlinedButton(
                stringResource(R.string.onboarding_keep_running_action),
                { actions.open(SystemScreen.BATTERY_SETTINGS) },
                Modifier.padding(top = CoulombSpacing.Space12),
                trailingIcon = CoulombIcons.OpenInNew,
            )
        }
        HorizontalDivider(thickness = Hairline, color = colors.hairline)
    }
}

@Composable
private fun ColumnScope.Grants(
    state: OnboardingUiState,
    actions: OnboardingActions,
) {
    val grants = state.privilege.grants
    val throughShizuku = state.path == OnboardingPath.SHIZUKU && grants.anyCore
    SectionTitle(
        stringResource(if (throughShizuku) R.string.onboarding_grants_shizuku else R.string.onboarding_grants_basic),
        provenance = null,
    )
    Note(
        stringResource(if (grants.durableCore) R.string.onboarding_grants_durable else R.string.onboarding_grants_missing),
        Modifier.padding(bottom = CoulombSpacing.Space8),
        top = 0.dp,
    )
    GrantChecklist(grantRows(grants))
    if (!grants.usageStats) {
        // ADR-0047 §3: when the bootstrap did not grant it, the Settings route still does.
        CoulombTextButton(
            stringResource(R.string.onboarding_usage_settings),
            { actions.open(SystemScreen.USAGE_ACCESS) },
            Modifier.padding(top = CoulombSpacing.Space8),
            trailingIcon = CoulombIcons.OpenInNew,
            edge = ButtonEdge.Start,
        )
    }
    if (!grants.durableCore) AdbCommandsToggle(state.adbCommands)
}

/** Each step's buttons in the footer: one filled button, the way on. */
@Composable
internal fun FlowRowScope.FooterButtons(
    step: OnboardingStep,
    state: OnboardingUiState,
    actions: OnboardingActions,
) {
    when (step) {
        OnboardingStep.WELCOME -> {
            CoulombOutlinedButton(stringResource(R.string.onboarding_start_basic), { actions.choose(OnboardingPath.BASIC) })
            CoulombFilledButton(stringResource(R.string.onboarding_connect_shizuku), { actions.choose(OnboardingPath.SHIZUKU) })
        }

        OnboardingStep.PERMISSIONS -> {
            CoulombFilledButton(stringResource(R.string.onboarding_continue), actions.next)
        }

        OnboardingStep.SHIZUKU -> {
            if (state.stage == ShizukuStage.CONNECTED) {
                CoulombFilledButton(stringResource(R.string.onboarding_continue), actions.next)
            } else {
                CoulombOutlinedButton(stringResource(R.string.onboarding_skip_shizuku), actions.next)
            }
        }

        OnboardingStep.RESULT -> {
            CoulombFilledButton(stringResource(R.string.onboarding_show_battery), actions.finish)
        }
    }
}

/** The step's title (Title on compact widths, Headline wider; DESIGN.md) and its lede. */
@Composable
private fun ColumnScope.StepHead(
    title: String,
    lede: String,
) {
    StepTitle(title)
    Text(
        lede,
        Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space12),
        color = CoulombTheme.colors.textPrimary,
        style = CoulombTheme.typography.body,
    )
}

/** TalkBack announces a new step by its title, as a pane change. */
@Composable
private fun StepTitle(title: String) {
    val type = CoulombTheme.typography
    Text(
        title,
        Modifier
            .fillMaxWidth()
            .padding(top = CoulombSpacing.Space24)
            .semantics {
                heading()
                paneTitle = title
            },
        color = CoulombTheme.colors.textPrimary,
        style = if (currentWindowWidth() == WindowWidth.Compact) type.title else type.headline,
    )
}

@Composable
private fun Block(
    @StringRes title: Int,
    @StringRes body: Int,
    top: Dp = CoulombSpacing.Space24,
) = Block(stringResource(title), stringResource(body), top)

/** A subhead and its sentence (lab `.callout h2` and `p`). */
@Composable
private fun Block(
    title: String,
    body: String,
    top: Dp = CoulombSpacing.Space24,
) {
    val colors = CoulombTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = top)) {
        Text(title, Modifier.semantics { heading() }, color = colors.textPrimary, style = CoulombTheme.typography.bodySubhead)
        Text(body, Modifier.padding(top = CoulombSpacing.Space4), color = colors.textSecondary, style = CoulombTheme.typography.body)
    }
}

/** A permission: what it is, why in one sentence (SPEC 14), then its button or "Allowed". */
@Suppress("LongParameterList") // One row's facts and its action.
@Composable
private fun ColumnScope.PermissionBlock(
    @StringRes name: Int,
    @StringRes why: Int,
    granted: Boolean,
    @StringRes action: Int,
    external: Boolean,
    onClick: () -> Unit,
) {
    val colors = CoulombTheme.colors
    Block(name, why)
    if (granted) {
        Row(
            Modifier.padding(top = CoulombSpacing.Space12).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8),
        ) {
            Icon(painterResource(CoulombIcons.CheckCircle), null, Modifier.size(AllowedIcon), tint = colors.good)
            Text(stringResource(R.string.onboarding_allowed), color = colors.textPrimary, style = CoulombTheme.typography.label)
        }
    } else {
        CoulombOutlinedButton(
            stringResource(action),
            onClick,
            Modifier.padding(top = CoulombSpacing.Space12),
            trailingIcon = if (external) CoulombIcons.OpenInNew else null,
        )
    }
}

/** How the server runs, in plain words; never the manager's name (SPEC 4.2). */
@StringRes
private fun ServerInfo.mode(): Int =
    when {
        isSui -> R.string.onboarding_mode_sui
        isRoot -> R.string.onboarding_mode_root
        else -> R.string.onboarding_mode_shell
    }

private const val ANDROID_17 = 37

/** Where the quiet Android 17 line adds something; a hang or the missing-manager copy already says it. */
private val QuietNoteStages = setOf(ShizukuStage.CHECKING, ShizukuStage.ASK, ShizukuStage.WAITING, ShizukuStage.STOPPED)
private val StateIcon = 24.dp
private val AllowedIcon = 18.dp
private val Hairline = 1.dp

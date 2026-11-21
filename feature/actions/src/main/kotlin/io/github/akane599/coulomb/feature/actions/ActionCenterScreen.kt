// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.collect.actions.ActionBlock
import io.github.akane599.coulomb.core.collect.actions.ActionPreview
import io.github.akane599.coulomb.core.collect.actions.ActionRecord
import io.github.akane599.coulomb.core.collect.actions.ActionType
import io.github.akane599.coulomb.core.collect.actions.DozeOwner
import io.github.akane599.coulomb.core.collect.actions.DozeState
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombFilledButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombOutlinedButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombSwitchRow
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.component.SkeletonBlock
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.designsystem.theme.currentSectionGap
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.DataRow
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import io.github.akane599.coulomb.core.ui.page.StandIn
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * The Action Center (SPEC 13): off until turned on; then the Doze profiles, the phone's own
 * actions and the log of everything Coulomb changed, each revertible. Actions for one app are on
 * that app's page.
 */
@Composable
public fun ActionCenterScreen(
    onOpenPrivileges: () -> Unit,
    onOpenDrain: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActionCenterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val flow by viewModel.flow.state.collectAsStateWithLifecycle()
    val saveFailed by viewModel.saveFailed.collectAsStateWithLifecycle()
    // A value may have changed while the screen was away.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val actions =
        remember(viewModel, onOpenPrivileges, onOpenDrain) {
            CenterActions(
                enable = viewModel::enable,
                disable = viewModel::disable,
                open = viewModel.flow::open,
                revert = { viewModel.flow.revert(it.id, it.type) },
                revertAll = { viewModel.flow.revertAll() },
                openPrivileges = onOpenPrivileges,
                openDrain = onOpenDrain,
            )
        }
    ActionCenterContent(state, flow, actions, modifier, saveFailed = saveFailed)
    flow.preview?.let { ActionSheet(it, flow.busy, viewModel.flow::confirm, viewModel.flow::dismiss) }
}

/** What the Action Center's controls do. */
@Immutable
internal class CenterActions(
    val enable: () -> Unit = {},
    val disable: (revertFirst: Boolean) -> Unit = {},
    val open: (ActionPreview) -> Unit = {},
    val revert: (ActionRecord) -> Unit = {},
    val revertAll: () -> Unit = {},
    val openPrivileges: () -> Unit = {},
    val openDrain: () -> Unit = {},
)

@Composable
internal fun ActionCenterContent(
    state: ActionCenterState,
    flow: FlowState,
    actions: CenterActions,
    modifier: Modifier = Modifier,
    saveFailed: Boolean = false,
    clock: LogClock = remember(state) { LogClock(ZoneId.systemDefault(), System.currentTimeMillis()) },
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
        when (state) {
            ActionCenterState.Loading -> Skeleton()
            is ActionCenterState.Ready -> FadeInOnce { Ready(state, flow, actions, saveFailed, clock) }
        }
    }
}

private val MaxWidth = 720.dp

@Composable
private fun Skeleton() {
    val loading = stringResource(R.string.actions_loading)
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8).semantics { contentDescription = loading }) {
        SkeletonBlock(26.dp, Modifier.width(220.dp))
        SkeletonBlock(16.dp, Modifier.padding(top = CoulombSpacing.Space12).fillMaxWidth())
        SkeletonBlock(16.dp, Modifier.padding(top = CoulombSpacing.Space8).width(280.dp))
        SkeletonBlock(64.dp, Modifier.padding(top = currentSectionGap()).fillMaxWidth())
    }
}

/** Which question is open over the screen. */
private enum class Asking { TURN_OFF, REVERT_ALL }

@Composable
private fun Ready(
    state: ActionCenterState.Ready,
    flow: FlowState,
    actions: CenterActions,
    saveFailed: Boolean,
    clock: LogClock,
) {
    val formats = rememberFormats()
    var asking by rememberSaveable { mutableStateOf<Asking?>(null) }
    var opened by remember { mutableStateOf<Long?>(null) }
    val active = state.active.size
    Column(Modifier.fillMaxWidth()) {
        if (saveFailed) Note(stringResource(R.string.actions_save_failed), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        // A revert's result is said at the log, where its button is; an action's at the top, under the bar.
        flow.note?.takeUnless { it.revert }?.let { ActionNoteLine(it, Modifier.padding(top = CoulombSpacing.Space8)) }
        val board = state.board
        if (!state.enabled || board == null) {
            Off(actions.enable)
        } else {
            CoulombSwitchRow(
                stringResource(R.string.actions_switch),
                checked = true,
                onCheckedChange = { if (active > 0) asking = Asking.TURN_OFF else actions.disable(false) },
                Modifier.fillMaxWidth(),
                note = stringResource(R.string.actions_switch_note),
            )
            if (board.noWorker) NoWorker(actions.openPrivileges)
            Doze(board, state, formats, flow.busy, actions)
            Device(board, actions.open)
            Apps(actions.openDrain)
        }
        // The log stays while the Action Center is off, and a change in force can still be reverted.
        if (state.enabled || state.log.isNotEmpty()) {
            LogSection(state, flow, formats, clock, actions.revert, onRevertAll = { asking = Asking.REVERT_ALL }) { opened = it }
        }
        // Last: what is not offered is the least of what the screen is for (ADR-0060 §7, §8).
        if (state.enabled && board != null) NotOffered()
    }
    state.log.firstOrNull { it.record.id == opened }?.let { row ->
        CoulombSheet({ opened = null }) {
            LogDetails(row, formats, clock, flow.busy, onRevert = { actions.revert(row.record) }, onDismiss = { opened = null })
        }
    }
    Question(asking, active, formats, actions) { asking = null }
}

/** The question open over the screen, if one is; any answer closes it. */
@Composable
private fun Question(
    asking: Asking?,
    active: Int,
    formats: Formats,
    actions: CenterActions,
    close: () -> Unit,
) {
    when (asking) {
        Asking.TURN_OFF -> {
            TurnOffQuestion(
                active,
                formats,
                onRevert = {
                    close()
                    actions.disable(true)
                },
                onKeep = {
                    close()
                    actions.disable(false)
                },
                onDismiss = close,
            )
        }

        Asking.REVERT_ALL -> {
            RevertAllQuestion(
                active,
                formats,
                onConfirm = {
                    close()
                    actions.revertAll()
                },
                onDismiss = close,
            )
        }

        null -> {}
    }
}

/** SPEC 13: off until the user turns it on, with what that means and what it grants. */
@Composable
private fun Off(enable: () -> Unit) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8)) {
        Text(stringResource(R.string.actions_off_title), Modifier.semantics { heading() }, color = colors.textPrimary, style = type.title)
        Text(
            stringResource(R.string.actions_off_body),
            Modifier.padding(top = CoulombSpacing.Space8).widthIn(max = ReadingWidth),
            color = colors.textSecondary,
            style = type.body,
        )
        Note(stringResource(R.string.actions_off_grant), Modifier.widthIn(max = ReadingWidth))
        CoulombFilledButton(stringResource(R.string.actions_turn_on), enable, Modifier.padding(top = CoulombSpacing.Space16))
    }
}

private val ReadingWidth = 560.dp

@Composable
private fun NoWorker(openPrivileges: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Note(stringResource(R.string.actions_no_worker))
        CoulombTextButton(
            stringResource(R.string.actions_open_privileges),
            openPrivileges,
            trailingIcon = CoulombIcons.ChevronRight,
            edge = ButtonEdge.Start,
        )
    }
}

/** SPEC 13.3: how soon the phone dozes now, who holds the setting, and the two profiles. */
@Composable
private fun ColumnScope.Doze(
    board: DeviceBoard,
    state: ActionCenterState.Ready,
    formats: Formats,
    busy: Boolean,
    actions: CenterActions,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val doze = board.doze
    SectionTitle(stringResource(R.string.actions_doze), provenance = null)
    val idleAfter = doze?.idleAfterMs
    when {
        idleAfter != null -> {
            Text(
                stringResource(R.string.actions_doze_idle_after, formats.held(idleAfter)),
                color = colors.textPrimary,
                style = type.body,
            )
        }

        // Without a worker the line above the sections already says why nothing can be read.
        !board.noWorker -> {
            StandIn(stringResource(R.string.actions_doze_unreadable), top = 0.dp)
        }
    }
    if (doze != null) Holder(doze, state, busy, actions)
    // Without a worker a profile has no command either: it is built from values only the worker reads.
    if (board.noWorker) Note(stringResource(R.string.actions_doze_needs_worker))
    Column(Modifier.padding(top = CoulombSpacing.Space12)) {
        ProfileRow(board.balanced, R.string.actions_doze_balanced_detail, doze, formats, first = true, actions.open)
        ProfileRow(board.aggressive, R.string.actions_doze_aggressive_detail, doze, formats, first = false, actions.open)
    }
}

/** Who holds the Doze setting, where that is not simply the phone (SPEC 13.1 "External managers"). */
@Composable
private fun Holder(
    doze: DozeState,
    state: ActionCenterState.Ready,
    busy: Boolean,
    actions: CenterActions,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val formats = rememberFormats()
    val top = Modifier.padding(top = CoulombSpacing.Space12)
    when (doze.owner) {
        // The phone's own values: nothing to say.
        DozeOwner.NOBODY -> {}

        DozeOwner.EXTERNAL -> {
            Column(top.fillMaxWidth()) {
                Text(stringResource(R.string.actions_doze_external), color = colors.textPrimary, style = type.bodySubhead)
                Text(stringResource(R.string.actions_doze_external_body), color = colors.textSecondary, style = type.body)
                CoulombOutlinedButton(
                    stringResource(R.string.actions_doze_take_over),
                    { actions.open(state.board?.takeOver ?: return@CoulombOutlinedButton) },
                    Modifier.padding(top = CoulombSpacing.Space8),
                    enabled = !busy,
                )
            }
        }

        DozeOwner.BASELINE -> {
            val taken = state.active.firstOrNull { it.record.type == ActionType.DOZE_TAKEOVER }?.record
            Column(top.fillMaxWidth()) {
                Text(
                    stringResource(R.string.actions_doze_baseline, takenOn(doze.importedName.orEmpty(), formats)),
                    color = colors.textSecondary,
                    style = type.body,
                )
                if (taken != null) {
                    CoulombTextButton(
                        stringResource(R.string.actions_doze_release),
                        { actions.revert(taken) },
                        enabled = !busy,
                        edge = ButtonEdge.Start,
                    )
                }
            }
        }

        DozeOwner.PROFILE -> {
            val applied = state.active.firstOrNull { it.record.type == ActionType.DOZE_PROFILE }?.record
            Column(top.fillMaxWidth()) {
                Text(
                    stringResource(R.string.actions_doze_applied, doze.profile?.let { stringResource(ActionWords.profile(it)) }.orEmpty()),
                    color = colors.textPrimary,
                    style = type.bodySubhead,
                )
                if (applied != null) {
                    CoulombOutlinedButton(
                        stringResource(R.string.actions_doze_stock),
                        { actions.revert(applied) },
                        Modifier.padding(top = CoulombSpacing.Space8),
                        trailingIcon = CoulombIcons.Undo,
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

/** A taken-over value is named by its day; one named otherwise keeps its name. */
private fun takenOn(
    name: String,
    formats: Formats,
): String =
    try {
        formats.day(LocalDate.parse(name), LocalDate.now())
    } catch (_: DateTimeParseException) {
        name
    }

/** The log under its title, with "Revert all" while a change is in force and a revert's result where its button is. */
@Composable
@Suppress("LongParameterList") // The log, its clock and its three controls.
private fun LogSection(
    state: ActionCenterState.Ready,
    flow: FlowState,
    formats: Formats,
    clock: LogClock,
    onRevert: (ActionRecord) -> Unit,
    onRevertAll: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(
            stringResource(R.string.actions_log),
            provenance = null,
            action =
                if (state.active.isNotEmpty()) {
                    {
                        CoulombTextButton(
                            stringResource(R.string.actions_revert_all),
                            onRevertAll,
                            enabled = !flow.busy,
                            edge = ButtonEdge.End,
                        )
                    }
                } else {
                    null
                },
        )
        flow.note?.takeIf { it.revert }?.let { ActionNoteLine(it, Modifier.padding(bottom = CoulombSpacing.Space8)) }
        ActionLog(state.log, formats, clock, flow.busy, onRevert) { onOpen(it.record.id) }
    }
}

/** Why a profile cannot be applied; empty where the reason is said once above the rows, null when it can. */
@Composable
private fun blockedWords(
    preview: ActionPreview,
    idleAfter: Long?,
    formats: Formats,
): String? =
    when (val block = preview.block) {
        null -> {
            null
        }

        ActionBlock.NO_WORKER -> {
            ""
        }

        ActionBlock.NOTHING_TO_CHANGE -> {
            idleAfter?.let { stringResource(R.string.actions_block_nothing, formats.held(it)) }
                ?: stringResource(ActionWords.block(block))
        }

        else -> {
            stringResource(ActionWords.block(block))
        }
    }

/** One profile: what it is for, how soon the phone would doze with it, or why it cannot be applied. */
@Composable
private fun ProfileRow(
    preview: ActionPreview,
    detail: Int,
    doze: DozeState?,
    formats: Formats,
    first: Boolean,
    open: (ActionPreview) -> Unit,
) {
    val idleAfter = doze?.idleAfterMs
    // The two keys whose sum is the time to deep Doze; what the profile takes off them comes off the sum.
    val gain = preview.dozeChanges.filter { it.key in IDLE_KEYS }.sumOf { it.fromMs - it.toMs }
    val blocked = blockedWords(preview, idleAfter, formats)
    // What the profile is for, unless on this phone it leaves the time to deep Doze as it is.
    val same = preview.block == null && idleAfter != null && gain <= 0
    val words = if (same) stringResource(R.string.actions_doze_same_time, formats.held(idleAfter)) else stringResource(detail)
    DataRow(
        name = stringResource(ActionWords.title(preview.request)),
        detail = blocked?.ifEmpty { stringResource(detail) } ?: words,
        value =
            if (preview.block == null && idleAfter != null && gain > 0) {
                stringResource(R.string.actions_doze_would_idle, formats.held(idleAfter - gain))
            } else {
                ""
            },
        mark = null,
        share = null,
        first = first,
        onClick = if (blocked == null) ({ open(preview) }) else null,
    )
}

private val IDLE_KEYS = setOf("inactive_to", "idle_after_inactive_to")

/** Battery saver and the statistics reset (SPEC 13.2). */
@Composable
private fun ColumnScope.Device(
    board: DeviceBoard,
    open: (ActionPreview) -> Unit,
) {
    SectionTitle(stringResource(R.string.actions_device), provenance = null)
    val saver = board.saver
    val saverBlock = saver.block?.takeIf { it != ActionBlock.NO_WORKER }
    DataRow(
        name = stringResource(R.string.actions_saver),
        detail = stringResource(saverBlock?.let(ActionWords::block) ?: ActionWords.effect(saver.request)),
        value = valueText(ActionType.BATTERY_SAVER, saver.before),
        mark = null,
        share = null,
        first = true,
        onClick = if (saverBlock == null) ({ open(saver) }) else null,
    )
    DataRow(
        name = stringResource(R.string.actions_reset),
        detail = stringResource(R.string.actions_reset_detail),
        value = "",
        mark = null,
        share = null,
        first = false,
        onClick = { open(board.reset) },
    )
}

/** Where an app's actions are: on its page, reached from Drain. */
@Composable
private fun ColumnScope.Apps(openDrain: () -> Unit) {
    SectionTitle(stringResource(R.string.actions_apps), provenance = null)
    StandIn(stringResource(R.string.actions_apps_note), top = 0.dp)
    CoulombTextButton(
        stringResource(R.string.actions_open_drain),
        openDrain,
        trailingIcon = CoulombIcons.ChevronRight,
        edge = ButtonEdge.Start,
    )
}

/** The two actions of SPEC 13.2 that are not offered, with why (ADR-0060 §7, §8). */
@Composable
private fun ColumnScope.NotOffered() {
    SectionTitle(stringResource(R.string.actions_not_offered), provenance = null)
    DataRow(
        stringResource(R.string.actions_deny_wakelocks),
        stringResource(R.string.actions_deny_wakelocks_why),
        "",
        null,
        null,
        first = true,
    )
    DataRow(stringResource(R.string.actions_sensors), stringResource(R.string.actions_sensors_why), "", null, null, first = false)
}

@Composable
private fun TurnOffQuestion(
    active: Int,
    formats: Formats,
    onRevert: () -> Unit,
    onKeep: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CoulombTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                CoulombTextButton(stringResource(R.string.actions_off_revert), onRevert)
                CoulombTextButton(stringResource(R.string.actions_off_keep), onKeep)
                CoulombTextButton(stringResource(R.string.actions_cancel), onDismiss)
            }
        },
        title = {
            Text(
                stringResource(R.string.actions_off_keep_title),
                color = colors.textPrimary,
                style = CoulombTheme.typography.title,
            )
        },
        text = {
            Text(
                pluralStringResource(R.plurals.actions_off_keep_body, active, formats.integer(active.toLong())),
                color = colors.textSecondary,
                style = CoulombTheme.typography.body,
            )
        },
        shape = DialogShape,
        containerColor = colors.surfaceRaised,
    )
}

@Composable
private fun RevertAllQuestion(
    active: Int,
    formats: Formats,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CoulombTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { CoulombTextButton(stringResource(R.string.actions_revert_all), onConfirm) },
        dismissButton = { CoulombTextButton(stringResource(R.string.actions_cancel), onDismiss) },
        title = {
            Text(
                stringResource(R.string.actions_revert_all_title),
                color = colors.textPrimary,
                style = CoulombTheme.typography.title,
            )
        },
        text = {
            Text(
                pluralStringResource(R.plurals.actions_revert_all_body, active, formats.integer(active.toLong())),
                color = colors.textSecondary,
                style = CoulombTheme.typography.body,
            )
        },
        shape = DialogShape,
        containerColor = colors.surfaceRaised,
    )
}

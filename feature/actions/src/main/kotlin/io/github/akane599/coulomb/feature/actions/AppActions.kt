// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.actions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.collect.actions.ActionBlock
import io.github.akane599.coulomb.core.collect.actions.ActionPreview
import io.github.akane599.coulomb.core.collect.actions.ActionRecord
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombChoiceGroup
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.component.SkeletonBlock
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.ui.breakable
import io.github.akane599.coulomb.core.ui.page.DataRow
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import io.github.akane599.coulomb.core.ui.page.StandIn

/** The model of one app's actions in the current navigation entry. [insightId] is the insight whose card asked. */
@Composable
public fun appActionsViewModel(
    uid: Int,
    insightId: Long? = null,
): AppActionsViewModel =
    hiltViewModel<AppActionsViewModel, AppActionsViewModel.Factory>(
        key = "actions-$uid-$insightId",
        creationCallback = { it.create(uid, insightId) },
    )

/**
 * App detail's Actions section (SPEC 10.4 item 8): each action of SPEC 13.2 for the app with the
 * current state of what it changes. A tap opens the preview as a sheet.
 */
@Composable
public fun AppActionsSection(
    viewModel: AppActionsViewModel,
    onOpenActionCenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val flow by viewModel.flow.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val actions = rememberAppActions(viewModel, onOpenActionCenter)
    Column(modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.actions_app_title), provenance = null)
        AppActionsList(state, flow, actions)
    }
    flow.preview?.let {
        ActionSheet(it, flow.busy, viewModel.flow::confirm, viewModel.flow::dismiss, subject = (state as? AppActionsState.Ready)?.label)
    }
}

/**
 * The same actions as a sheet over the screen an insight's card is on (DESIGN.md, Insight cards).
 * The list and a chosen action's preview take turns in the one sheet.
 */
@Composable
public fun AppActionsSheet(
    viewModel: AppActionsViewModel,
    onDismiss: () -> Unit,
    onOpenActionCenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val flow by viewModel.flow.state.collectAsStateWithLifecycle()
    val actions =
        rememberAppActions(viewModel) {
            onDismiss()
            onOpenActionCenter()
        }
    // Swiped away with a preview open, the sheet takes the preview with it: the next opening starts at the list.
    val close = {
        viewModel.flow.dismiss()
        onDismiss()
    }
    CoulombSheet(close, modifier) {
        val label = (state as? AppActionsState.Ready)?.label
        val preview = flow.preview
        if (preview != null) {
            ActionPreviewContent(
                preview,
                flow.busy,
                viewModel.flow::confirm,
                viewModel.flow::dismiss,
                subject = label,
                dismissLabel = stringResource(R.string.actions_back),
            )
        } else {
            Text(
                label?.let { stringResource(R.string.actions_app_sheet_title, it) } ?: stringResource(R.string.actions_app_title),
                Modifier.padding(bottom = CoulombSpacing.Space12).semantics { heading() },
                color = CoulombTheme.colors.textPrimary,
                style = CoulombTheme.typography.title,
            )
            AppActionsList(state, flow, actions)
        }
    }
}

@Composable
private fun rememberAppActions(
    viewModel: AppActionsViewModel,
    onOpenActionCenter: () -> Unit,
): AppActions =
    remember(viewModel, onOpenActionCenter) {
        AppActions(
            open = viewModel.flow::open,
            revert = { viewModel.flow.revert(it.id, it.type) },
            choose = viewModel::choose,
            openActionCenter = onOpenActionCenter,
        )
    }

/** What the controls of an app's actions do. */
@Immutable
internal class AppActions(
    val open: (ActionPreview) -> Unit = {},
    val revert: (ActionRecord) -> Unit = {},
    val choose: (String) -> Unit = {},
    val openActionCenter: () -> Unit = {},
)

/** The rows, or the one line that says why there are none. */
@Composable
internal fun AppActionsList(
    state: AppActionsState,
    flow: FlowState,
    actions: AppActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        when (state) {
            AppActionsState.Loading -> {
                val loading = stringResource(R.string.actions_loading)
                SkeletonBlock(64.dp, Modifier.fillMaxWidth().semantics { contentDescription = loading })
            }

            AppActionsState.Off -> {
                StandIn(stringResource(R.string.actions_app_off), top = 0.dp)
                CoulombTextButton(
                    stringResource(R.string.actions_app_open_center),
                    actions.openActionCenter,
                    trailingIcon = CoulombIcons.ChevronRight,
                    edge = ButtonEdge.Start,
                )
            }

            AppActionsState.OtherProfile -> {
                StandIn(stringResource(R.string.actions_app_other_profile), top = 0.dp)
            }

            AppActionsState.None -> {
                StandIn(stringResource(R.string.actions_app_none), top = 0.dp)
            }

            is AppActionsState.Ready -> {
                Rows(state, flow, actions)
            }
        }
    }
}

@Composable
private fun ColumnScope.Rows(
    state: AppActionsState.Ready,
    flow: FlowState,
    actions: AppActions,
) {
    flow.note?.let { ActionNoteLine(it, Modifier.padding(bottom = CoulombSpacing.Space12)) }
    if (state.packages.size > 1) {
        Text(
            stringResource(R.string.actions_app_packages),
            Modifier.padding(bottom = CoulombSpacing.Space8),
            color = CoulombTheme.colors.textSecondary,
            style = CoulombTheme.typography.label,
        )
        CoulombChoiceGroup(
            options = state.packages.map(::breakable),
            selected = state.packages.indexOf(state.selected),
            onSelect = { actions.choose(state.packages[it]) },
            modifier = Modifier.padding(bottom = CoulombSpacing.Space12),
        )
    }
    // One refusal for every action is said once.
    val shared =
        state.items
            .map { it.preview.block }
            .distinct()
            .singleOrNull()
    if (shared in WHOLE_APP && shared != null) {
        StandIn(stringResource(ActionWords.block(shared)), top = 0.dp)
        return
    }
    if (state.items.any { it.preview.block == ActionBlock.NO_WORKER }) {
        Note(stringResource(R.string.actions_block_no_worker), Modifier.padding(bottom = CoulombSpacing.Space8), top = 0.dp)
    }
    state.items.forEachIndexed { index, item -> Row(item, first = index == 0, flow.busy, actions) }
}

/** One action: its name, what it does or why it cannot run, the value now, and the revert of Coulomb's own change. */
@Composable
private fun Row(
    item: AppActionItem,
    first: Boolean,
    busy: Boolean,
    actions: AppActions,
) {
    val preview = item.preview
    val active = item.active
    // Without a worker the row still opens, for the command.
    val reason = preview.block?.takeIf { it != ActionBlock.NO_WORKER && !(it == ActionBlock.ALREADY && active != null) }
    DataRow(
        name = stringResource(ActionWords.title(preview.request)),
        detail = stringResource(reason?.let(ActionWords::block) ?: ActionWords.effect(preview.request)),
        value =
            when {
                // The user's list does not hold it and the system's does: "not on the list" would mislead.
                preview.block == ActionBlock.SYSTEM_LISTED -> {
                    stringResource(R.string.actions_value_system_listed)
                }

                preview.request.type.stateful && preview.block !in UNREAD -> {
                    stringResource(R.string.actions_now_value, valueText(preview.request.type, preview.before))
                }

                else -> {
                    ""
                }
            },
        mark = null,
        share = null,
        first = first,
        onClick = if (preview.block == null || preview.block == ActionBlock.NO_WORKER) ({ actions.open(preview) }) else null,
        action =
            if (active != null) {
                {
                    CoulombTextButton(
                        stringResource(R.string.actions_revert),
                        { actions.revert(active) },
                        enabled = !busy,
                        trailingIcon = CoulombIcons.Undo,
                        edge = ButtonEdge.End,
                    )
                }
            } else {
                null
            },
    )
}

/** A refusal that holds for the app, not for one action. */
private val WHOLE_APP = setOf(ActionBlock.PROTECTED, ActionBlock.NOT_INSTALLED, ActionBlock.UNREADABLE)

private val UNREAD = setOf(ActionBlock.OFF, ActionBlock.NO_WORKER, ActionBlock.UNREADABLE, ActionBlock.NOT_INSTALLED, ActionBlock.PROTECTED)

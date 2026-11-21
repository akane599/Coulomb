// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.collect.actions.ActionBlock
import io.github.akane599.coulomb.core.collect.actions.ActionPreview
import io.github.akane599.coulomb.core.collect.actions.ActionRequest
import io.github.akane599.coulomb.core.collect.actions.ActionType
import io.github.akane599.coulomb.core.designsystem.component.CoulombFilledButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.ui.format.rememberFormats

/** A bottom sheet in the app's sheet shape and colours (DESIGN.md, Raised Slate). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoulombSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = CoulombTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = RoundedCornerShape(topStart = CoulombRadius.Sheet, topEnd = CoulombRadius.Sheet),
        containerColor = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = currentGutter(), end = currentGutter(), bottom = CoulombSpacing.Space24),
        ) { content() }
    }
}

/** The preview of SPEC 13.1 as a sheet. [subject] is the app the action is for. */
@Composable
internal fun ActionSheet(
    preview: ActionPreview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    subject: String? = null,
) {
    CoulombSheet(onDismiss) { ActionPreviewContent(preview, busy, onConfirm, onDismiss, subject = subject) }
}

/**
 * An action before it runs (SPEC 13.1): what it does, its risk, the value now and after, the exact
 * command and how to undo it, then the button that runs it. An action that cannot run says why
 * and has no such button; without a worker its command is still there to copy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionPreviewContent(
    preview: ActionPreview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subject: String? = null,
    dismissLabel: String = stringResource(R.string.actions_cancel),
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val request = preview.request
    val title = stringResource(ActionWords.title(request))
    val profile = request is ActionRequest.ApplyDozeProfile
    var asking by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        (subject ?: stringResource(R.string.actions_log_profile).takeIf { profile })?.let {
            Text(it, color = colors.textSecondary, style = type.label)
        }
        Text(title, Modifier.semantics { heading() }, color = colors.textPrimary, style = type.title)
        Text(
            stringResource(ActionWords.effect(request)),
            Modifier.padding(top = CoulombSpacing.Space8),
            color = colors.textSecondary,
            style = type.body,
        )
        preview.block?.let {
            Text(
                stringResource(ActionWords.block(it)),
                Modifier.padding(top = CoulombSpacing.Space16),
                color = colors.textPrimary,
                style = type.bodySubhead,
            )
        }
        Fact(stringResource(R.string.actions_risk), stringResource(ActionWords.risk(preview.risk)))
        Values(preview)
        preview.command?.let {
            Text(
                stringResource(R.string.actions_command),
                Modifier.padding(top = CoulombSpacing.Space16, bottom = CoulombSpacing.Space4),
                color = colors.textSecondary,
                style = type.label,
            )
            CommandBlock(it)
        }
        Undo(preview)
        FlowRow(
            Modifier.padding(top = CoulombSpacing.Space16),
            horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (preview.block == null) {
                // SPEC 13.2: a reset asks twice.
                val confirm = if (request == ActionRequest.ResetBatteryStats) ({ asking = true }) else onConfirm
                // A button names what it does; a profile's name alone would not.
                CoulombFilledButton(if (profile) stringResource(R.string.actions_doze_apply, title) else title, confirm, enabled = !busy)
            }
            CoulombTextButton(dismissLabel, onDismiss, enabled = !busy)
        }
    }
    if (asking) {
        SecondQuestion(
            onConfirm = {
                asking = false
                onConfirm()
            },
            onDismiss = { asking = false },
        )
    }
}

/** Now and after; a Doze profile lists each key it changes instead. */
@Composable
private fun Values(preview: ActionPreview) {
    val type = preview.request.type
    val formats = rememberFormats()
    val doze = type == ActionType.DOZE_PROFILE || type == ActionType.DOZE_TAKEOVER
    // Unread or off: there is no value to show, and "not set" would be a guess.
    if (preview.block in UNREAD) return
    if (type.stateful) {
        Fact(
            stringResource(if (doze) R.string.actions_doze_setting else R.string.actions_now),
            valueText(type, preview.before),
            mono =
                doze && !preview.before.isNullOrEmpty(),
        )
    }
    if (preview.dozeChanges.isNotEmpty()) {
        Text(
            stringResource(R.string.actions_doze_changes),
            Modifier.padding(top = CoulombSpacing.Space16),
            color = CoulombTheme.colors.textSecondary,
            style = CoulombTheme.typography.label,
        )
        preview.dozeChanges.forEach { change ->
            Fact(
                change.key,
                stringResource(R.string.actions_log_change, formats.held(change.fromMs), formats.held(change.toMs)),
                Modifier.padding(top = 0.dp),
            )
        }
    } else if (type.stateful && !doze && preview.block == null) {
        Fact(stringResource(R.string.actions_after), valueText(type, preview.after))
    }
}

@Composable
private fun Undo(preview: ActionPreview) {
    val type = preview.request.type
    val text =
        when {
            type == ActionType.FORCE_STOP -> R.string.actions_undo_none_stop
            type == ActionType.RESET_STATS -> R.string.actions_undo_none_reset
            type == ActionType.DOZE_TAKEOVER -> R.string.actions_undo_take_over
            preview.revertCommand != null -> R.string.actions_undo_log
            else -> return
        }
    Fact(stringResource(R.string.actions_undo), stringResource(text))
    preview.revertCommand?.let { CommandBlock(it, Modifier.padding(top = CoulombSpacing.Space8)) }
}

/** SPEC 13.2 "two confirmations": the second, for the one action that cannot be undone. */
@Composable
private fun SecondQuestion(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CoulombTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { CoulombTextButton(stringResource(R.string.actions_reset_confirm), onConfirm) },
        dismissButton = { CoulombTextButton(stringResource(R.string.actions_cancel), onDismiss) },
        title = {
            Text(
                stringResource(R.string.actions_reset_confirm_title),
                color = colors.textPrimary,
                style = CoulombTheme.typography.title,
            )
        },
        text = {
            Text(
                stringResource(R.string.actions_reset_confirm_body),
                color = colors.textSecondary,
                style = CoulombTheme.typography.body,
            )
        },
        shape = DialogShape,
        containerColor = colors.surfaceRaised,
    )
}

internal val DialogShape = RoundedCornerShape(28.dp)

private val UNREAD = setOf(ActionBlock.OFF, ActionBlock.NO_WORKER, ActionBlock.UNREADABLE, ActionBlock.NOT_INSTALLED, ActionBlock.PROTECTED)

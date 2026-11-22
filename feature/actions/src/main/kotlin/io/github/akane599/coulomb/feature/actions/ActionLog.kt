// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.actions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.akane599.coulomb.core.collect.actions.ActionBlock
import io.github.akane599.coulomb.core.collect.actions.ActionRecord
import io.github.akane599.coulomb.core.collect.actions.ActionType
import io.github.akane599.coulomb.core.collect.actions.AutomationCalls
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.ActionOrigin
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.page.DataRow
import io.github.akane599.coulomb.core.ui.page.StandIn

/**
 * The action log, newest first (SPEC 13). A row names the action and what it was for, the change
 * and when, and whether it is still in force. One in force reverts in a tap; a tap on the row
 * opens its details, with the revert as a command.
 */
@Composable
internal fun ColumnScope.ActionLog(
    rows: List<LogRow>,
    formats: Formats,
    clock: LogClock,
    busy: Boolean,
    onRevert: (ActionRecord) -> Unit,
    /** The next page, when there are older rows; the view model reads a page at a time (audit PERF-007). */
    onOlder: (() -> Unit)?,
    onOpen: (LogRow) -> Unit,
) {
    if (rows.isEmpty()) {
        StandIn(stringResource(R.string.actions_log_empty), top = CoulombSpacing.Space4)
        return
    }
    rows.forEachIndexed { index, row ->
        val record = row.record
        DataRow(
            name = stringResource(ActionWords.logTitle(record.type)),
            detail = logDetail(row, formats, clock),
            value = stringResource(logState(record)),
            mark = null,
            share = null,
            first = index == 0,
            onClick = { onOpen(row) },
            action =
                if (record.active) {
                    {
                        CoulombTextButton(
                            stringResource(R.string.actions_revert),
                            { onRevert(record) },
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
    if (onOlder != null) {
        CoulombTextButton(stringResource(R.string.actions_log_older), onOlder, edge = ButtonEdge.Start)
    }
}

// SPEC 14.4's range for the charge target.
private const val TARGET_MIN = 50
private const val TARGET_MAX = 100

/** What the row was for and when: "Halcyon · Allowed → Restricted · 9:30 PM". */
@Composable
private fun logDetail(
    row: LogRow,
    formats: Formats,
    clock: LogClock,
): String {
    val record = row.record
    val whenText = formats.clock(record.executedWallMs, clock.zone, clock.nowMs)
    val parts = listOfNotNull(subject(row), change(record, formats), whenText)
    return parts.reduce { joined, part -> stringResource(R.string.actions_log_joined, joined, part) }
}

/** The app, or the profile; nothing for an action on the phone itself. */
@Composable
private fun subject(row: LogRow): String? =
    when (row.record.type) {
        ActionType.DOZE_PROFILE -> profileName(row.record.target)
        ActionType.DOZE_TAKEOVER, ActionType.BATTERY_SAVER, ActionType.RESET_STATS -> null
        ActionType.AUTOMATION_CALL -> automationSubject(row.record.target)
        else -> row.appLabel ?: row.record.target
    }

/** What the call asked for and, where the caller said who it is, its package: "Charge target · com.example.tasks". */
@Composable
private fun automationSubject(target: String): String {
    val asked = ActionWords.automationCall(target)?.let { stringResource(it) } ?: AutomationCalls.name(target)
    val caller = AutomationCalls.caller(target) ?: return asked
    return stringResource(R.string.actions_log_joined, asked, caller)
}

@Composable
internal fun profileName(target: String): String =
    when (target) {
        "balanced" -> stringResource(R.string.actions_doze_balanced)
        "aggressive" -> stringResource(R.string.actions_doze_aggressive)
        else -> target
    }

/** "Allowed → Restricted"; nothing where the values are a setting too long for a row, or there is no change to name. */
@Composable
private fun change(
    record: ActionRecord,
    formats: Formats,
): String? =
    when (record.type) {
        ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET, ActionType.DOZE_ALLOWLIST, ActionType.BATTERY_SAVER -> {
            stringResource(R.string.actions_log_change, valueText(record.type, record.before), valueText(record.type, record.after))
        }

        // The charge target's two values, or the profile "stock" replaced; a snapshot has neither.
        ActionType.AUTOMATION_CALL -> {
            val before = record.before
            if (before == null || AutomationCalls.refusal(record.after) != null) {
                null
            } else {
                stringResource(R.string.actions_log_change, automationValue(before, formats), automationValue(record.after, formats))
            }
        }

        else -> {
            null
        }
    }

/** A call's value in words: a charge target as a percentage, a profile by its name. */
@Composable
private fun automationValue(
    value: String,
    formats: Formats,
): String =
    when {
        value == AutomationCalls.STOCK -> stringResource(R.string.actions_automation_stock)
        value.toIntOrNull() != null -> formats.percent(value.toInt())
        else -> profileName(value)
    }

/** That another app asked for it, and for a refused call what was asked and why not. */
@Composable
private fun AutomationOrigin(
    record: ActionRecord,
    formats: Formats,
) {
    Text(
        stringResource(R.string.actions_log_from_automation),
        color = CoulombTheme.colors.textSecondary,
        style = CoulombTheme.typography.body,
    )
    val reason = AutomationCalls.refusal(record.after).takeIf { record.type == ActionType.AUTOMATION_CALL } ?: return
    record.before?.let { Fact(stringResource(R.string.actions_log_asked), automationValue(it, formats)) }
    Fact(stringResource(R.string.actions_log_refused), refusalText(reason, formats))
}

/**
 * Why a call was refused, in a sentence. A reason this build has no sentence for shows as the
 * word the API gave the caller (`docs/automation-api.md`).
 */
@Composable
private fun refusalText(
    reason: String,
    formats: Formats,
): String {
    val own = ActionWords.refusal(reason)
    val block = ActionBlock.entries.firstOrNull { it.name.lowercase() == reason }
    return when {
        reason == "bad_target" -> {
            stringResource(
                R.string.actions_refused_bad_target,
                formats.percent(TARGET_MIN),
                formats.percent(TARGET_MAX),
            )
        }

        own != null -> {
            stringResource(own)
        }

        block != null -> {
            stringResource(ActionWords.block(block))
        }

        else -> {
            reason
        }
    }
}

private fun logState(record: ActionRecord): Int =
    when {
        record.type == ActionType.AUTOMATION_CALL && AutomationCalls.refusal(record.after) != null -> R.string.actions_log_refused
        record.driftWallMs != null -> R.string.actions_log_drifted
        record.revertedWallMs != null -> R.string.actions_log_reverted
        record.active -> R.string.actions_log_active
        else -> R.string.actions_log_done
    }

/** One log row in full: the values, the times, and for a change in force its revert as a command. */
@Composable
internal fun LogDetails(
    row: LogRow,
    formats: Formats,
    clock: LogClock,
    busy: Boolean,
    onRevert: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val record = row.record
    val setting = record.type == ActionType.DOZE_PROFILE || record.type == ActionType.DOZE_TAKEOVER
    Column(modifier.fillMaxWidth()) {
        subject(row)?.let { Text(it, color = colors.textSecondary, style = type.label) }
        Text(
            stringResource(ActionWords.logTitle(record.type)),
            Modifier.semantics { heading() },
            color = colors.textPrimary,
            style = type.title,
        )
        Text(
            stringResource(
                R.string.actions_log_joined,
                stringResource(logState(record)),
                formats.clock(record.revertedWallMs ?: record.driftWallMs ?: record.executedWallMs, clock.zone, clock.nowMs),
            ),
            Modifier.padding(top = CoulombSpacing.Space4),
            color = colors.textSecondary,
            style = type.body,
        )
        if (record.origin == ActionOrigin.INSIGHT) {
            Text(stringResource(R.string.actions_log_from_insight), color = colors.textSecondary, style = type.body)
        }
        if (record.origin == ActionOrigin.AUTOMATION) AutomationOrigin(record, formats)
        if (record.type?.stateful == true) {
            Fact(
                stringResource(R.string.actions_now_before),
                valueText(record.type, record.before),
                mono =
                    setting && !record.before.isNullOrEmpty(),
            )
            Fact(stringResource(R.string.actions_after), valueText(record.type, record.after), mono = setting && record.after.isNotEmpty())
        }
        if (record.driftWallMs != null) {
            Fact(
                stringResource(R.string.actions_log_drifted),
                stringResource(R.string.actions_log_drift_value, valueText(record.type, record.driftValue)),
            )
        }
        record.revertCommand?.let {
            Fact(stringResource(R.string.actions_undo), stringResource(R.string.actions_undo_log))
            CommandBlock(it, Modifier.padding(top = CoulombSpacing.Space8), copyLabel = R.string.actions_copy_undo)
        }
        Column(Modifier.padding(top = CoulombSpacing.Space8)) {
            if (record.active) {
                CoulombTextButton(
                    stringResource(R.string.actions_revert),
                    onRevert,
                    enabled = !busy,
                    trailingIcon = CoulombIcons.Undo,
                    edge = ButtonEdge.Start,
                )
            }
            CoulombTextButton(stringResource(R.string.actions_back), onDismiss, edge = ButtonEdge.Start)
        }
    }
}

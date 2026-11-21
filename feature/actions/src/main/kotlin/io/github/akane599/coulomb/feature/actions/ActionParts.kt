// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.actions

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import io.github.akane599.coulomb.core.collect.actions.ActionResult
import io.github.akane599.coulomb.core.collect.actions.ActionType
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.ui.breakable
import kotlinx.coroutines.launch

/** A value of the action log in words; a Doze setting and a mode Coulomb never writes are shown as they are. */
@Composable
internal fun valueText(
    type: ActionType?,
    value: String?,
): String = ActionWords.value(type, value)?.let { stringResource(it) } ?: value.orEmpty()

/** How the last action ended, in one sentence. */
@Composable
internal fun noteText(note: ActionNote): String =
    when (val result = note.result) {
        is ActionResult.Done -> {
            when {
                note.revert -> {
                    stringResource(R.string.actions_result_reverted)
                }

                // Android kept a nearer bucket than the one asked for.
                note.asked != null && result.value != note.asked -> {
                    stringResource(
                        R.string.actions_result_short,
                        valueText(note.type, result.value),
                    )
                }

                else -> {
                    stringResource(R.string.actions_result_done)
                }
            }
        }

        is ActionResult.Blocked -> {
            stringResource(ActionWords.block(result.reason))
        }

        is ActionResult.Unchanged -> {
            stringResource(R.string.actions_result_unchanged)
        }

        is ActionResult.Unverified -> {
            stringResource(R.string.actions_result_unverified)
        }

        is ActionResult.Drifted -> {
            stringResource(R.string.actions_result_drifted)
        }

        is ActionResult.Failed -> {
            val detail = result.detail?.trim()?.take(MAX_DETAIL)
            if (detail.isNullOrEmpty()) {
                stringResource(
                    R.string.actions_result_failed,
                )
            } else {
                stringResource(R.string.actions_result_failed_detail, detail)
            }
        }
    }

/** The sentence for [note], announced when it changes. */
@Composable
internal fun ActionNoteLine(
    note: ActionNote,
    modifier: Modifier = Modifier,
) {
    Text(
        noteText(note),
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = CoulombTheme.colors.textPrimary,
        style = CoulombTheme.typography.body,
    )
}

/**
 * One `adb shell` line in Mono on `surface`, selectable, with "Copy command" (SPEC 4.4). It stays
 * left to right in a right-to-left layout. Android 13 and later confirm the copy themselves.
 */
@Composable
internal fun CommandBlock(
    command: String,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.actions_clip_label)
    Column(modifier.fillMaxWidth()) {
        SelectionContainer(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(CoulombRadius.Row))) {
            // As it is: a copied selection must be the command, without the marks that let a long name wrap.
            Text(
                command,
                Modifier.padding(CoulombSpacing.Space16),
                color = colors.textPrimary,
                style = CoulombTheme.typography.mono.copy(textDirection = TextDirection.Ltr),
            )
        }
        CoulombTextButton(
            text = stringResource(R.string.actions_copy),
            onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, command))) } },
            modifier = Modifier.padding(top = CoulombSpacing.Space4),
            trailingIcon = CoulombIcons.ContentCopy,
            edge = ButtonEdge.Start,
        )
    }
}

/** A named value of a preview: the name in Label, the value under it, so a long one has the whole width. */
@Composable
internal fun Fact(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Column(modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16).semantics(mergeDescendants = true) {}) {
        Text(label, color = colors.textSecondary, style = type.label)
        if (mono) {
            Text(breakable(value), color = colors.textPrimary, style = type.mono.copy(textDirection = TextDirection.Ltr))
        } else {
            Text(value, color = colors.textPrimary, style = type.body)
        }
    }
}

private const val MAX_DETAIL = 200

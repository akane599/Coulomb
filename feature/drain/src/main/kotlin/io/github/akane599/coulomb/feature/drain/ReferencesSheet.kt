// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.data.NamedReference
import io.github.akane599.coulomb.core.data.References
import io.github.akane599.coulomb.core.designsystem.component.CoulombFilledButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombOutlinedButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Named references (SPEC 7.6): mark this moment by name, then read Drain since one reference or
 * between any two. Choosing a third drops the first chosen, so two stay chosen at most.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReferencesSheet(
    references: List<NamedReference>,
    zone: ZoneId,
    onMark: (String) -> Unit,
    onDelete: (List<Long>) -> Unit,
    onChoose: (DrainSpan) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val formats = rememberFormats()
    var naming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var chosenIds by rememberSaveable { mutableStateOf(listOf<Long>()) }
    // A reference deleted elsewhere leaves the choice.
    val chosen = chosenIds.mapNotNull { id -> references.firstOrNull { it.id == id } }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = CoulombRadius.Sheet, topEnd = CoulombRadius.Sheet),
        containerColor = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = currentGutter(), end = currentGutter(), bottom = CoulombSpacing.Space24),
        ) {
            Text(
                stringResource(R.string.drain_references),
                Modifier.semantics { heading() },
                color = colors.textPrimary,
                style = type.title,
            )
            Text(
                stringResource(R.string.drain_references_body),
                Modifier.padding(top = CoulombSpacing.Space8),
                color = colors.textSecondary,
                style = type.caption,
            )
            CoulombOutlinedButton(
                stringResource(R.string.drain_references_mark),
                { naming = true },
                Modifier.padding(top = CoulombSpacing.Space16, bottom = CoulombSpacing.Space8),
            )
            if (references.isEmpty()) {
                Text(
                    stringResource(R.string.drain_references_none),
                    Modifier.padding(vertical = CoulombSpacing.Space12),
                    color = colors.textSecondary,
                    style = type.body,
                )
            }
            references.forEach { reference ->
                ReferenceRow(
                    name = reference.mark.name.ifEmpty { stringResource(R.string.drain_references_unnamed) },
                    // The date, not only the weekday: references lie weeks apart.
                    time =
                        stringResource(
                            R.string.drain_references_when,
                            formats.day(Instant.ofEpochMilli(reference.mark.wallMs).atZone(zone).toLocalDate()),
                            formats.clock(reference.mark.wallMs, zone),
                        ),
                    chosen = reference in chosen,
                    onClick = { chosenIds = toggled(chosenIds, reference.id) },
                )
            }
            if (references.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16),
                    horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoulombTextButton(stringResource(R.string.drain_references_delete), { deleting = true }, enabled = chosen.isNotEmpty())
                    CoulombFilledButton(
                        stringResource(if (chosen.size == 2) R.string.drain_references_between else R.string.drain_references_since),
                        {
                            spanOf(chosen)?.let(onChoose)
                            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                        },
                        enabled = chosen.isNotEmpty(),
                    )
                }
            }
        }
    }
    if (naming) {
        NameDialog(
            onSave = {
                onMark(it)
                naming = false
            },
            onDismiss = { naming = false },
        )
    }
    if (deleting) {
        DeleteDialog(
            count = chosen.size,
            onDelete = {
                onDelete(chosen.map { it.id })
                chosenIds = emptyList()
                deleting = false
            },
            onDismiss = { deleting = false },
        )
    }
}

/** [id] added as the latest choice, dropping the earliest past two, or taken out when already chosen. */
internal fun toggled(
    chosen: List<Long>,
    id: Long,
): List<Long> = if (id in chosen) chosen - id else (chosen + id).takeLast(2)

/** Since the one chosen reference, or from the older of two to the newer. */
internal fun spanOf(chosen: List<NamedReference>): DrainSpan? {
    val marks = chosen.map { it.mark }.sortedBy { it.wallMs }
    return when (marks.size) {
        1 -> DrainSpan.Between(marks[0], null)
        2 -> if (marks[1].wallMs > marks[0].wallMs) DrainSpan.Between(marks[0], marks[1]) else DrainSpan.Between(marks[0], null)
        else -> null
    }
}

@Composable
private fun ReferenceRow(
    name: String,
    time: String,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    val colors = CoulombTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics(mergeDescendants = true) { selected = chosen }
            .heightIn(min = RowHeight)
            .padding(vertical = CoulombSpacing.Space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = colors.textPrimary, style = CoulombTheme.typography.bodyName)
            Text(time, color = colors.textSecondary, style = CoulombTheme.typography.caption)
        }
        if (chosen) {
            Icon(painterResource(CoulombIcons.Check), contentDescription = null, Modifier.size(CheckSize), tint = colors.textPrimary)
        }
    }
}

/** Names the reference about to be marked; Mark stays off until there is a name. */
@Composable
private fun NameDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CoulombTheme.colors
    val formats = rememberFormats()
    val focus = remember { FocusRequester() }
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val save = { if (text.text.isNotBlank()) onSave(text.text.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { CoulombTextButton(stringResource(R.string.drain_references_save), save, enabled = text.text.isNotBlank()) },
        dismissButton = { CoulombTextButton(stringResource(R.string.drain_references_cancel), onDismiss) },
        title = {
            Text(
                stringResource(R.string.drain_references_name_title),
                color = colors.textPrimary,
                style = CoulombTheme.typography.title,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(top = CoulombSpacing.Space4)) {
                OutlinedTextField(
                    value = text,
                    // A longer paste is cut to the limit rather than dropped.
                    onValueChange = { entry ->
                        val cut = entry.text.take(References.MAX_NAME)
                        text = if (cut.length == entry.text.length) entry else TextFieldValue(cut, TextRange(cut.length))
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    label = { Text(stringResource(R.string.drain_references_name_label)) },
                    supportingText = {
                        Row {
                            Text(stringResource(R.string.drain_references_name_hint), Modifier.weight(1f))
                            Text(
                                stringResource(
                                    R.string.drain_references_name_count,
                                    formats.integer(text.text.length.toLong()),
                                    formats.integer(References.MAX_NAME.toLong()),
                                ),
                                Modifier.padding(start = CoulombSpacing.Space8),
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(CoulombRadius.Row),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.textPrimary,
                            focusedLabelColor = colors.textPrimary,
                            // DESIGN.md gives copper to the text caret.
                            cursorColor = colors.accent,
                        ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
            }
            LaunchedEffect(focus) { focus.requestFocus() }
        },
        shape = RoundedCornerShape(CoulombRadius.Large),
        containerColor = colors.surfaceRaised,
    )
}

/** Deleting a reference takes its name and lets its snapshot go; the battery's data stays. */
@Composable
private fun DeleteDialog(
    count: Int,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CoulombTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { CoulombTextButton(stringResource(R.string.drain_references_delete), onDelete) },
        dismissButton = { CoulombTextButton(stringResource(R.string.drain_references_cancel), onDismiss) },
        title = {
            Text(
                pluralStringResource(R.plurals.drain_references_delete_title, count),
                color = colors.textPrimary,
                style = CoulombTheme.typography.title,
            )
        },
        text = {
            Text(
                stringResource(R.string.drain_references_delete_body),
                color = colors.textSecondary,
                style = CoulombTheme.typography.body,
            )
        },
        shape = RoundedCornerShape(CoulombRadius.Large),
        containerColor = colors.surfaceRaised,
    )
}

private val RowHeight = 64.dp
private val CheckSize = 24.dp

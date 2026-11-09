// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.DesignCapacity
import io.github.akane599.coulomb.core.model.DesignCapacitySource
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import kotlin.math.roundToInt

private val DialogShape = RoundedCornerShape(28.dp)
private val FieldShape = RoundedCornerShape(12.dp)

/** The longest name a charger keeps (ADR-0044 §6). */
private const val MAX_NAME = 40

/** Renames a charger; an empty name makes it unnamed again (ADR-0044 §6). */
@Composable
internal fun RenameDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val formats = rememberFormats()
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(current, TextRange(current.length))) }
    val save = { onSave(text.text.trim()) }
    TextDialog(
        title = stringResource(R.string.health_rename_title),
        onConfirm = save,
        confirmEnabled = true,
        onDismiss = onDismiss,
    ) { focus ->
        OutlinedTextField(
            value = text,
            // A longer paste is cut to the limit rather than dropped.
            onValueChange = { entry ->
                val cut = entry.text.take(MAX_NAME)
                text = if (cut.length == entry.text.length) entry else TextFieldValue(cut, TextRange(cut.length))
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            label = { Text(stringResource(R.string.health_rename_label)) },
            supportingText = {
                Row {
                    Text(stringResource(R.string.health_rename_hint), Modifier.weight(1f))
                    Text(
                        stringResource(
                            R.string.health_rename_count,
                            formats.integer(text.text.length.toLong()),
                            formats.integer(MAX_NAME.toLong()),
                        ),
                        Modifier.padding(start = CoulombSpacing.Space8),
                    )
                }
            },
            singleLine = true,
            shape = FieldShape,
            colors = fieldColors(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )
    }
}

/**
 * The owner's design capacity, in mAh within [DesignCapacity.PLAUSIBLE_MAH] (SPEC 6.3, ADR-0044 §5),
 * with where the current one comes from; an entry of the owner's can be undone ([onUseReportedValue]).
 */
@Composable
internal fun DesignDialog(
    current: DesignCapacity?,
    onSave: (Int) -> Unit,
    onUseReportedValue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val formats = rememberFormats()
    val colors = CoulombTheme.colors
    val start =
        current
            ?.mah
            ?.roundToInt()
            ?.toString()
            .orEmpty()
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(start, TextRange(start.length))) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val value = text.text.toIntOrNull()
    val valid = value != null && value.toDouble() in DesignCapacity.PLAUSIBLE_MAH
    // Too large shows at once; too small once the entry has a capacity's digits, or after Save, so
    // typing 4,855 never flashes an error at "485".
    val tooLarge = value != null && value > DesignCapacity.PLAUSIBLE_MAH.endInclusive
    val wrong = !valid && text.text.isNotEmpty() && (tried || tooLarge || text.text.length >= MIN_DIGITS)
    val save = { if (valid) onSave(value) else tried = true }
    TextDialog(
        title = stringResource(R.string.health_design_title),
        onConfirm = save,
        confirmEnabled = text.text.isNotEmpty(),
        onDismiss = onDismiss,
    ) { focus ->
        current?.let {
            Text(
                stringResource(R.string.health_design_source, formats.mah(it.mah).joined()) + " " + stringResource(designSource(it.source)),
                Modifier.fillMaxWidth().padding(bottom = CoulombSpacing.Space12),
                color = colors.textSecondary,
                style = CoulombTheme.typography.caption,
            )
        }
        OutlinedTextField(
            value = text,
            // Digits only: a paste of "5,000" or "5000 mAh" keeps its number.
            onValueChange = { entry ->
                val digits = entry.text.filter(Char::isDigit).take(MAX_DIGITS)
                text = if (digits == entry.text) entry else TextFieldValue(digits, TextRange(digits.length))
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            label = { Text(stringResource(R.string.health_design_label)) },
            supportingText = {
                Text(stringResource(if (wrong) R.string.health_design_invalid else R.string.health_design_hint))
            },
            isError = wrong,
            singleLine = true,
            shape = FieldShape,
            colors = fieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )
        if (current?.source == DesignCapacitySource.USER) {
            CoulombTextButton(
                stringResource(R.string.health_design_reported),
                onUseReportedValue,
                Modifier.padding(top = CoulombSpacing.Space8),
                edge = ButtonEdge.Start,
            )
            Text(
                stringResource(R.string.health_design_reported_note),
                color = colors.textSecondary,
                style = CoulombTheme.typography.caption,
            )
        }
    }
}

private const val MIN_DIGITS = 4
private const val MAX_DIGITS = 5

/** A dialog with one text field, which takes focus, and Cancel and Save. */
@Composable
private fun TextDialog(
    title: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    field: @Composable (FocusRequester) -> Unit,
) {
    val colors = CoulombTheme.colors
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { CoulombTextButton(stringResource(R.string.health_save), onConfirm, enabled = confirmEnabled) },
        dismissButton = { CoulombTextButton(stringResource(R.string.health_cancel), onDismiss) },
        title = { Text(title, color = colors.textPrimary, style = CoulombTheme.typography.title) },
        text = {
            // Scrolls at large text with the keyboard up, where the text slot alone would squeeze the field.
            Column(Modifier.verticalScroll(rememberScrollState()).padding(top = CoulombSpacing.Space4)) { field(focus) }
            // In the dialog's own composition, so the field is attached when it asks for focus.
            LaunchedEffect(focus) { focus.requestFocus() }
        },
        shape = DialogShape,
        // Raised like the sheets and menus (DESIGN.md, Raised Slate).
        containerColor = colors.surfaceRaised,
    )
}

@Composable
private fun fieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = CoulombTheme.colors.textPrimary,
        focusedLabelColor = CoulombTheme.colors.textPrimary,
        // DESIGN.md gives copper to the text caret.
        cursorColor = CoulombTheme.colors.accent,
    )

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The More sheet of the window selector (SPEC 10.3, ADR-0023 §4): Last night, a custom range of
 * whole days within the 30 days consecutive windows are kept, and Compare two periods (ADR-0038).
 * Choosing the range opens the date picker in place of the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreSheet(
    chosen: DrainSpan,
    today: LocalDate,
    onChoose: (DrainSpan) -> Unit,
    onCompare: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    if (picking) {
        RangeDialog(
            initial = chosen as? DrainSpan.Days,
            today = today,
            title = stringResource(R.string.drain_range_title),
            onChoose = {
                onChoose(it)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        return
    }
    val colors = CoulombTheme.colors
    val formats = rememberFormats()
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    // The sheet slides away before it leaves the composition (SPEC 11.6: motion explains change).
    val close: (then: () -> Unit) -> Unit = { then -> scope.launch { sheetState.hide() }.invokeOnCompletion { then() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = CoulombRadius.Sheet, topEnd = CoulombRadius.Sheet),
        containerColor = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(start = currentGutter(), end = currentGutter(), bottom = CoulombSpacing.Space24)) {
            Text(
                stringResource(R.string.drain_sheet_title),
                Modifier.padding(bottom = CoulombSpacing.Space8).semantics { heading() },
                color = colors.textPrimary,
                style = CoulombTheme.typography.title,
            )
            SheetChoice(
                name = stringResource(R.string.drain_last_night),
                detail = stringResource(R.string.drain_last_night_detail),
                chosen = chosen == DrainSpan.LastNight,
                onClick = {
                    // The new window starts loading while the sheet slides away.
                    onChoose(DrainSpan.LastNight)
                    close(onDismiss)
                },
            )
            SheetChoice(
                name = stringResource(R.string.drain_custom_range),
                detail =
                    pluralStringResource(
                        R.plurals.drain_custom_range_detail,
                        DrainSpan.CUSTOM_DAYS.toInt(),
                        formats.integer(DrainSpan.CUSTOM_DAYS),
                    ),
                chosen = chosen is DrainSpan.Days,
                onClick = { close { picking = true } },
            )
            // Opens its own screen, so it is never the chosen window.
            SheetChoice(
                name = stringResource(R.string.drain_compare_title),
                detail = stringResource(R.string.drain_compare_detail),
                chosen = false,
                onClick = {
                    close {
                        onDismiss()
                        onCompare()
                    }
                },
            )
        }
    }
}

/** A sheet row: name and detail, with the check while it is the chosen window. */
@Composable
private fun SheetChoice(
    name: String,
    detail: String,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { selected = chosen }
            .heightIn(min = ChoiceHeight)
            .padding(vertical = CoulombSpacing.Space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = colors.textPrimary, style = type.bodyName)
            Text(detail, color = colors.textSecondary, style = type.caption)
        }
        if (chosen) {
            Icon(
                painterResource(CoulombIcons.Check),
                contentDescription = null,
                Modifier.size(CheckSize),
                tint = colors.textPrimary,
            )
        }
    }
}

/** Material's range picker, limited to the days a range may cover; a single day is a range of one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RangeDialog(
    initial: DrainSpan.Days?,
    today: LocalDate,
    title: String,
    onChoose: (DrainSpan.Days) -> Unit,
    onDismiss: () -> Unit,
) {
    val earliest = today.minusDays(DrainSpan.CUSTOM_DAYS - 1)
    val selectable =
        remember(earliest, today) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis.utcDate() in earliest..today

                override fun isSelectableYear(year: Int): Boolean = year in earliest.year..today.year
            }
        }
    // In a short window the range picker opens for typing. The window's own height, as the width
    // classes read it (WindowWidth.kt), not the screen's.
    val short =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height
                .toDp()
        } < ShortWindowHeight
    val state =
        rememberDateRangePickerState(
            initialSelectedStartDateMillis = initial?.first?.utcMillis(),
            initialSelectedEndDateMillis = initial?.last?.utcMillis(),
            initialDisplayedMonthMillis = today.utcMillis(),
            yearRange = earliest.year..today.year,
            // In a short window the month grid shrinks to a few rows; typing the dates fits.
            initialDisplayMode = if (short) DisplayMode.Input else DisplayMode.Picker,
            selectableDates = selectable,
        )
    val start = state.selectedStartDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (start != null) {
                CoulombTextButton(
                    text = stringResource(R.string.drain_range_confirm),
                    onClick = {
                        val first = start.utcDate()
                        val last = state.selectedEndDateMillis?.utcDate() ?: first
                        onChoose(DrainSpan.Days(first, last))
                    },
                )
            }
        },
        dismissButton = { CoulombTextButton(text = stringResource(R.string.drain_range_cancel), onClick = onDismiss) },
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.weight(1f),
            title = {
                Text(
                    title,
                    Modifier.padding(start = CoulombSpacing.Space24, end = CoulombSpacing.Space12, top = CoulombSpacing.Space16),
                    style = CoulombTheme.typography.label,
                )
            },
        )
    }
}

/** Material's pickers carry dates as UTC midnights. */
private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.utcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private val ChoiceHeight = 72.dp
private val CheckSize = 24.dp

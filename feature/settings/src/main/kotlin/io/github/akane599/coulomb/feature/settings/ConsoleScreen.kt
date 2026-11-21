// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.data.ConsoleQuery
import io.github.akane599.coulomb.core.data.ConsoleTable
import io.github.akane599.coulomb.core.data.ConsoleValue
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombChoiceGroup
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.ui.format.DASH
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.InlineTable
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import io.github.akane599.coulomb.core.ui.page.TableRow
import java.time.Instant
import java.time.ZoneId

/** SPEC 14.4 "Developer console": a fixed list of read-only queries on Coulomb's own database. */
@Composable
public fun ConsoleScreen(
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    viewModel: ConsoleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ConsoleContent(
        state,
        ZoneId.systemDefault(),
        onSelect = viewModel::select,
        onHide = {
            viewModel.hide()
            onClose()
        },
        modifier = modifier,
    )
}

@Composable
internal fun ConsoleContent(
    state: ConsoleUiState,
    zone: ZoneId,
    onSelect: (ConsoleQuery) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gutter = currentGutter()
    val formats = rememberFormats()
    Column(
        modifier
            .fillMaxSize()
            .background(CoulombTheme.colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(start = gutter, end = gutter, bottom = CoulombSpacing.Space48)
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = MaxWidth),
    ) {
        Note(stringResource(R.string.console_about), top = CoulombSpacing.Space16)
        SectionTitle(stringResource(R.string.console_query), provenance = null)
        CoulombChoiceGroup(
            options = ConsoleQuery.entries.map { stringResource(it.label()) },
            selected = state.query.ordinal,
            onSelect = { onSelect(ConsoleQuery.entries[it]) },
        )
        val table = state.table
        when {
            state.failed -> Note(stringResource(R.string.console_failed), top = CoulombSpacing.Space16)

            // Read in a moment: nothing rather than a line that flips.
            table == null -> Unit

            table.rows.isEmpty() -> Note(stringResource(R.string.console_empty), top = CoulombSpacing.Space16)

            else -> Answer(table, zone, formats)
        }
        CoulombTextButton(stringResource(R.string.console_hide), onHide, edge = ButtonEdge.Start)
    }
}

/** The answer under the database's own column names; the first column names the row. */
@Composable
private fun Answer(
    table: ConsoleTable,
    zone: ZoneId,
    formats: Formats,
) {
    InlineTable(
        columns = table.columns,
        rows =
            table.rows.map { row ->
                TableRow(name = text(row.first(), zone, formats), values = row.drop(1).map { text(it, zone, formats) }, mono = true)
            },
        modifier = Modifier.padding(top = CoulombSpacing.Space16),
        shown = SHOWN,
        // Names and states are words: beside a name they would break in a figure's column.
        stack = table.columns.size > 2,
    )
}

private fun text(
    value: ConsoleValue,
    zone: ZoneId,
    formats: Formats,
): String =
    when (value) {
        is ConsoleValue.Text -> {
            value.text
        }

        is ConsoleValue.Count -> {
            formats.integer(value.count)
        }

        is ConsoleValue.Time -> {
            val day = formats.day(Instant.ofEpochMilli(value.wallMs).atZone(zone).toLocalDate())
            "$day ${formats.clockWithSeconds(value.wallMs, zone)}"
        }

        ConsoleValue.None -> {
            DASH
        }
    }

private fun ConsoleQuery.label(): Int =
    when (this) {
        ConsoleQuery.STORAGE -> R.string.console_storage
        ConsoleQuery.SNAPSHOTS -> R.string.console_snapshots
        ConsoleQuery.SOURCES -> R.string.console_sources
        ConsoleQuery.CAPABILITIES -> R.string.console_capabilities
        ConsoleQuery.BOOTS -> R.string.console_boots
    }

private const val SHOWN = 30
private val MaxWidth = 720.dp

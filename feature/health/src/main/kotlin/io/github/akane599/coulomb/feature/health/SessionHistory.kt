// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import io.github.akane599.coulomb.core.data.ChargeSessionRow
import io.github.akane599.coulomb.core.data.DischargeSessionRow
import io.github.akane599.coulomb.core.designsystem.component.CoulombViewTabs
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.format.Figure
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.page.DataRow
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The tabs between charges and discharges, shown once discharges are read, and the search, which
 * keeps the rows whose shown text holds every word of the query.
 */
@Composable
internal fun SessionFilters(
    tab: SessionKindTab,
    withDischarges: Boolean,
    query: String,
    onTab: (SessionKindTab) -> Unit,
    onQuery: (String) -> Unit,
) {
    val colors = CoulombTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = CoulombSpacing.Space8)) {
        if (withDischarges) {
            CoulombViewTabs(
                tabs = listOf(stringResource(R.string.health_tab_charges), stringResource(R.string.health_tab_discharges)),
                selected = tab.ordinal,
                onSelect = { onTab(SessionKindTab.entries[it]) },
                modifier = Modifier.padding(bottom = CoulombSpacing.Space12),
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { onQuery(it.take(MAX_QUERY)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.health_search_label)) },
            supportingText = { Text(stringResource(R.string.health_search_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(CoulombRadius.Row),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.textPrimary,
                    focusedLabelColor = colors.textPrimary,
                    // DESIGN.md gives copper to the text caret.
                    cursorColor = colors.accent,
                ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
    }
}

/** A charge row's searchable text: what the row shows, and its date written out. */
@Composable
internal fun chargeText(
    row: ChargeSessionRow,
    nowMs: Long,
    zone: ZoneId,
    formats: Formats,
): String =
    listOf(
        startedAt(row.startWallMs, nowMs, zone, formats),
        date(row.startWallMs, zone, formats),
        sessionLine(row, formats),
    ).joinToString(" ")

/** A discharge row's searchable text, as [chargeText]. */
@Composable
internal fun dischargeText(
    row: DischargeSessionRow,
    nowMs: Long,
    zone: ZoneId,
    formats: Formats,
): String =
    listOf(
        startedAt(row.startWallMs, nowMs, zone, formats),
        date(row.startWallMs, zone, formats),
        dischargeLine(row, formats),
    ).joinToString(" ")

private fun date(
    wallMs: Long,
    zone: ZoneId,
    formats: Formats,
): String = formats.day(Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate())

/** Every word of [query] is in [text], ignoring case; a blank query matches all. */
internal fun matches(
    text: String,
    query: String,
    locale: Locale = Locale.getDefault(),
): Boolean {
    val haystack = text.lowercase(locale)
    return query
        .lowercase(locale)
        .split(' ', ' ')
        .filter { it.isNotBlank() }
        .all { it in haystack }
}

/** One discharge session: when it began, its levels, time and rate, and the points it fell. */
@Composable
internal fun DischargeRow(
    row: DischargeSessionRow,
    first: Boolean,
    nowMs: Long,
    zone: ZoneId,
    formats: Formats,
    onClick: () -> Unit,
) {
    val fell = (row.fromPct - row.toPct).coerceAtLeast(0)
    DataRow(
        name = startedAt(row.startWallMs, nowMs, zone, formats),
        detail = dischargeLine(row, formats),
        detailMark = Provenance.DERIVED.takeIf { row.pctPerHour != null },
        value = formats.signedShare(-fell / FULL),
        // The level is the platform's reading at each end (SPEC 6.7).
        mark = Provenance.MEASURED,
        share = fell / FULL,
        first = first,
        onClick = onClick,
    )
}

/** "100% to 62% in 9 h 12 min · 4.1 %/h", and low confidence where the levels are coarse (SPEC 6.7). */
@Composable
internal fun dischargeLine(
    row: DischargeSessionRow,
    formats: Formats,
): String {
    val minutes = ((row.endWallMs - row.startWallMs).coerceAtLeast(0) / MS_PER_MINUTE).roundToInt().toLong()
    val line =
        stringResource(R.string.health_session_line, formats.percent(row.fromPct), formats.percent(row.toPct), formats.duration(minutes))
    return listOfNotNull(
        line,
        row.pctPerHour?.let { Figure(formats.rate(it), formats.rateUnit).joined() },
        stringResource(R.string.health_low_confidence_inline).takeIf { row.quality < LOW_QUALITY },
    ).joinToString(SEPARATOR)
}

private const val FULL = 100.0
private const val MS_PER_MINUTE = 60_000.0
private const val MAX_QUERY = 60

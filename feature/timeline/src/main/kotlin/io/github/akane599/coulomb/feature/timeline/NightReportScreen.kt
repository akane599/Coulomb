// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.data.NightSummary
import io.github.akane599.coulomb.core.designsystem.component.SkeletonBlock
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.designsystem.theme.currentSectionGap
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.format.DASH
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.DataRow
import io.github.akane599.coulomb.core.ui.page.ErrorState
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.StandIn
import io.github.akane599.coulomb.core.ui.rowMotion
import java.time.ZoneId

/**
 * The Night Report (SPEC 10.7): every screen-off stretch of an hour or more, newest first, with
 * its rate, its deep sleep and whether deep Doze began. A tap opens one ([selected] marks the one
 * open beside the list).
 */
@Composable
public fun NightReportScreen(
    onOpenNight: (Long) -> Unit,
    modifier: Modifier = Modifier,
    selected: Long? = null,
    viewModel: NightReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NightReportContent(state, viewModel::retry, onOpenNight, modifier, selected)
}

@Composable
@Suppress("LongParameterList") // The read, its controls and the test clock.
internal fun NightReportContent(
    state: Read<List<NightSummary>>,
    retry: () -> Unit,
    onOpenNight: (Long) -> Unit,
    modifier: Modifier = Modifier,
    selected: Long? = null,
    zone: ZoneId = remember(state) { ZoneId.systemDefault() },
    nowMs: Long = remember(state) { System.currentTimeMillis() },
) {
    val gutter = currentGutter()
    val formats = rememberFormats()
    when (state) {
        Read.Loading -> {
            Column(modifier.fillMaxSize().padding(horizontal = gutter)) { PageSkeleton() }
        }

        Read.Error -> {
            Column(modifier.fillMaxSize().padding(horizontal = gutter)) { ReadError(retry) }
        }

        is Read.Ready -> {
            if (state.value.isEmpty()) {
                Column(modifier.fillMaxSize().padding(horizontal = gutter)) {
                    StandIn(stringResource(R.string.night_empty), top = CoulombSpacing.Space24)
                }
            } else {
                // The longest bar is the fastest night: the rows compare by it.
                val fastest = state.value.maxOf { it.ratePctPerHour ?: 0.0 }
                LazyColumn(
                    modifier.fillMaxSize().readingWidth(),
                    contentPadding =
                        PaddingValues(
                            start = gutter,
                            end = gutter,
                            top = CoulombSpacing.Space8,
                            bottom = CoulombSpacing.Space48,
                        ),
                ) {
                    item(key = "note", contentType = "note") { Note(stringResource(R.string.night_report_note), top = 0.dp) }
                    itemsIndexed(state.value, key = { _, night -> night.id }, contentType = { _, _ -> "night" }) { index, night ->
                        Box(rowMotion().padding(top = if (index == 0) CoulombSpacing.Space12 else 0.dp)) {
                            NightRow(night, index == 0, night.id == selected, fastest, nowMs, zone, formats) { onOpenNight(night.id) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One night as a row: when it began, its line, and its rate with a bar against the list's fastest.
 * A night without a rate shows the level it lost, which the fuel gauge reports, or a dash on the charger.
 */
@Composable
@Suppress("LongParameterList") // One row's data, its place and its action.
internal fun NightRow(
    night: NightSummary,
    first: Boolean,
    selected: Boolean,
    fastest: Double,
    nowMs: Long,
    zone: ZoneId,
    formats: Formats,
    onClick: () -> Unit,
) {
    val rate = night.rate(formats)
    val drop = night.startLevel - night.endLevel
    DataRow(
        name = startedAt(night.startWallMs, nowMs, zone, formats),
        detail = nightLine(night, formats),
        value =
            when {
                rate != null -> rate.joined()
                night.charged -> DASH
                else -> formats.signedShare(-drop / PERCENT)
            },
        mark =
            when {
                rate != null -> Provenance.DERIVED
                night.charged -> null
                else -> Provenance.MODELED
            },
        share = night.ratePctPerHour?.takeIf { fastest > 0 }?.let { it / fastest },
        first = first,
        selected = selected,
        onClick = onClick,
    )
}

private const val PERCENT = 100.0

/** A page stays a readable measure, centred, when it covers a wide window (SPEC 10.10). */
internal fun Modifier.readingWidth(): Modifier = wrapContentWidth().widthIn(max = MaxPageWidth).fillMaxWidth()

private val MaxPageWidth = 840.dp

/** SPEC 10.11 loading for the list or a night. */
@Composable
internal fun PageSkeleton() {
    val loading = stringResource(R.string.timeline_loading)
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8).semantics { contentDescription = loading }) {
        SkeletonBlock(26.dp, Modifier.width(200.dp))
        SkeletonBlock(16.dp, Modifier.padding(top = CoulombSpacing.Space8).width(280.dp))
        SkeletonBlock(96.dp, Modifier.padding(top = currentSectionGap()).fillMaxWidth())
    }
}

/** SPEC 10.11 error: what happened and what to do. */
@Composable
internal fun ReadError(retry: () -> Unit) {
    ErrorState(
        stringResource(R.string.timeline_error_title),
        stringResource(R.string.timeline_error_body),
        stringResource(R.string.timeline_error_action),
        retry,
    )
}

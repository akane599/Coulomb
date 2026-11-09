// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.charging

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.data.ChargingNow
import io.github.akane599.coulomb.core.designsystem.component.CoulombOutlinedButton
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.component.SkeletonBlock
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.designsystem.theme.currentSectionGap
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.DataTable
import io.github.akane599.coulomb.core.ui.DataTableSheet
import io.github.akane599.coulomb.core.ui.format.DASH
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.ErrorState
import io.github.akane599.coulomb.core.ui.page.StandIn
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/**
 * Charging (SPEC 10.9, ADR-0046), shown in place of Now while plugged in. While it is resumed the
 * collector samples every second.
 */
@Composable
public fun ChargingScreen(
    modifier: Modifier = Modifier,
    viewModel: ChargingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.visible(true)
        onPauseOrDispose { viewModel.visible(false) }
    }
    // One instance, so sections that take only the actions skip the 1 Hz recompositions.
    val actions = remember(viewModel) { ChargingActions(retry = viewModel::retry, setTarget = viewModel::setTarget) }
    ChargingContent(state, actions, modifier)
}

/** What Charging's controls do; the screen itself holds no logic. */
@Immutable
internal class ChargingActions(
    val retry: () -> Unit,
    val setTarget: (Int) -> Unit,
)

/** Charging's column on wide windows, as Now's (ADR-0034). */
private val MaxContentWidth = 720.dp

@Composable
internal fun ChargingContent(
    state: ChargingUiState,
    actions: ChargingActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val formats = rememberFormats()
    var table by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = currentGutter(), end = currentGutter(), bottom = CoulombSpacing.Space48)
            .wrapContentWidth(Alignment.Start)
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth(),
    ) {
        when (state) {
            ChargingUiState.Loading -> {
                // The first sample is usually read well within this; only a slow read shows the skeleton.
                var late by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(SKELETON_DELAY_MS)
                    late = true
                }
                if (late) Skeleton()
            }

            ChargingUiState.Error -> {
                ErrorState(
                    stringResource(R.string.charging_error_title),
                    stringResource(R.string.charging_error_body),
                    stringResource(R.string.charging_error_action),
                    actions.retry,
                )
            }

            ChargingUiState.Unplugged -> {
                StandIn(stringResource(R.string.charging_unplugged), top = CoulombSpacing.Space24)
            }

            is ChargingUiState.Ready -> {
                FadeInOnce {
                    Column {
                        val now = state.now
                        Hero(now, formats)
                        LevelScale(now, formats, zone, onViewTable = { table = SCALE_TABLE })
                        Times(now, formats)
                        Readings(now, formats)
                        Session(now, formats, zone, onViewTable = { table = CURVE_TABLE })
                        Target(now.targetPct, formats, actions.setTarget)
                    }
                }
            }
        }
    }
    // A snapshot taken when the sheet opens: at 1 Hz the rows would otherwise shift under the reader (gate audit).
    val ready = (state as? ChargingUiState.Ready)?.now
    val shown = remember(table, ready == null) { ready }
    if (shown != null) {
        when (table) {
            CURVE_TABLE -> DataTableSheet(curveTable(shown, formats, zone), onDismiss = { table = null })
            SCALE_TABLE -> DataTableSheet(scaleTable(shown, formats, zone), onDismiss = { table = null })
        }
    }
}

private const val CURVE_TABLE = "curve"
private const val SKELETON_DELAY_MS = 150L
private const val SCALE_TABLE = "scale"

/** The level scale as a table: the level, and when the target and full are reached. */
@Composable
private fun scaleTable(
    now: ChargingNow,
    formats: Formats,
    zone: ZoneId,
): DataTable {
    val toTarget = now.toTarget?.takeIf { now.levelPct < now.targetPct }
    val toFull = now.toFull?.takeIf { now.levelPct < FULL_PCT && now.targetPct < FULL_PCT }
    val rows =
        listOfNotNull(
            listOf(stringResource(R.string.charging_row_level), formats.percent(now.levelPct)) to Provenance.MODELED,
            toTarget?.let {
                listOf(
                    stringResource(R.string.charging_row_reaches, formats.percent(now.targetPct)),
                    reachedAt(it, now.wallMs, formats, zone),
                ) to
                    Provenance.ESTIMATED
            },
            toFull?.let {
                listOf(stringResource(R.string.charging_row_full), reachedAt(it, now.wallMs, formats, zone)) to
                    Provenance.ESTIMATED
            },
        )
    return DataTable(
        title = stringResource(R.string.charging_table_scale),
        columns = listOf(stringResource(R.string.charging_column_reading), stringResource(R.string.charging_column_value)),
        rows = rows.map { it.first },
        provenance = null,
        rowProvenance = rows.map { it.second },
    )
}

private const val FULL_PCT = 100

/** The session's readings, newest first, as the curve's table. */
@Composable
private fun curveTable(
    now: ChargingNow,
    formats: Formats,
    zone: ZoneId,
): DataTable {
    val power = now.curve.any { it.watts != null }
    val title = stringResource(R.string.charging_table_curve)
    val columns =
        listOfNotNull(
            stringResource(R.string.charging_column_time),
            stringResource(R.string.charging_column_level),
            stringResource(R.string.charging_column_power).takeIf { power },
        )
    // Formatted once per snapshot, not at each recomposition while the sheet is open.
    return remember(now, formats, zone, title, columns) { curveTableOf(now, formats, zone, title, columns, power) }
}

@Suppress("LongParameterList") // A snapshot, its formats and its headings.
private fun curveTableOf(
    now: ChargingNow,
    formats: Formats,
    zone: ZoneId,
    title: String,
    columns: List<String>,
    power: Boolean,
): DataTable =
    DataTable(
        title = title,
        columns = columns,
        rows =
            now.curve.asReversed().map { point ->
                listOfNotNull(
                    // A reading from an earlier day names it (gate harden); today's keep their seconds.
                    if (sameDay(point.wallMs, now.wallMs, zone)) {
                        formats.clockWithSeconds(point.wallMs, zone)
                    } else {
                        formats.clock(point.wallMs, zone, now.wallMs)
                    },
                    formats.percent(point.levelPct),
                    if (power) point.watts?.let { formats.signedWatts(it).joined() } ?: DASH else null,
                )
            },
        // The level is the fuel gauge's, power is from current and voltage.
        provenance = Provenance.MODELED.takeIf { !power },
        columnProvenance = listOf(null, Provenance.MODELED, Provenance.DERIVED).takeIf { power },
    )

private fun sameDay(
    a: Long,
    b: Long,
    zone: ZoneId,
): Boolean = Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

/** SPEC 10.11 loading: a still skeleton in the hero's, scale's and strip's shape. */
@Composable
private fun Skeleton() {
    val loading = stringResource(R.string.charging_loading)
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8).semantics { contentDescription = loading }) {
        SkeletonBlock(60.dp, Modifier.width(160.dp))
        SkeletonBlock(26.dp, Modifier.padding(top = CoulombSpacing.Space8).width(120.dp))
        SkeletonBlock(24.dp, Modifier.padding(top = CoulombSpacing.Space8).width(260.dp))
        SkeletonBlock(24.dp, Modifier.padding(top = CoulombSpacing.Space24).fillMaxWidth())
        SkeletonBlock(20.dp, Modifier.padding(top = CoulombSpacing.Space16).width(240.dp))
        SkeletonBlock(80.dp, Modifier.padding(top = currentSectionGap()).fillMaxWidth())
    }
}

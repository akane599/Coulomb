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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.StandIn
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
    ChargingContent(state, ChargingActions(retry = viewModel::retry, setTarget = viewModel::setTarget), modifier)
}

/** What Charging's controls do; the screen itself holds no logic. */
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
    var table by rememberSaveable { mutableStateOf(false) }
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
                Skeleton()
            }

            ChargingUiState.Error -> {
                ErrorState(actions.retry)
            }

            ChargingUiState.Unplugged -> {
                StandIn(stringResource(R.string.charging_unplugged), top = CoulombSpacing.Space24)
            }

            is ChargingUiState.Ready -> {
                FadeInOnce {
                    Column {
                        val now = state.now
                        Hero(now, formats)
                        LevelScale(now, formats, zone)
                        Times(now, formats)
                        Readings(now, formats)
                        Session(now, formats, zone, onViewTable = { table = true })
                        Target(now.targetPct, formats, actions.setTarget)
                    }
                }
            }
        }
    }
    val ready = state as? ChargingUiState.Ready
    if (table && ready != null) DataTableSheet(curveTable(ready.now, formats, zone), onDismiss = { table = false })
}

/** The session's readings, newest first, as the curve's table. */
@Composable
private fun curveTable(
    now: ChargingNow,
    formats: Formats,
    zone: ZoneId,
): DataTable {
    val power = now.curve.any { it.watts != null }
    return DataTable(
        title = stringResource(R.string.charging_table_curve),
        columns =
            listOfNotNull(
                stringResource(R.string.charging_column_time),
                stringResource(R.string.charging_column_level),
                stringResource(R.string.charging_column_power).takeIf { power },
            ),
        rows =
            now.curve.asReversed().map { point ->
                listOfNotNull(
                    formats.clockWithSeconds(point.wallMs, zone),
                    formats.percent(point.levelPct),
                    if (power) point.watts?.let { formats.watts(it).joined() } ?: DASH else null,
                )
            },
        // The level is the fuel gauge's, power is from current and voltage.
        provenance = Provenance.MODELED.takeIf { !power },
        columnProvenance = listOf(null, Provenance.MODELED, Provenance.DERIVED).takeIf { power },
    )
}

/** SPEC 10.11 loading: a still skeleton in the hero's, scale's and strip's shape. */
@Composable
private fun Skeleton() {
    val loading = stringResource(R.string.charging_loading)
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16).semantics { contentDescription = loading }) {
        SkeletonBlock(60.dp, Modifier.width(160.dp))
        SkeletonBlock(24.dp, Modifier.padding(top = CoulombSpacing.Space24).fillMaxWidth())
        SkeletonBlock(20.dp, Modifier.padding(top = CoulombSpacing.Space16).width(240.dp))
        SkeletonBlock(80.dp, Modifier.padding(top = currentSectionGap()).fillMaxWidth())
    }
}

/** SPEC 10.11 error: what happened and what to do. */
@Composable
private fun ErrorState(retry: () -> Unit) {
    Column(Modifier.widthIn(max = 560.dp).padding(top = CoulombSpacing.Space24)) {
        Text(
            stringResource(R.string.charging_error_title),
            Modifier.fillMaxWidth().semantics { heading() },
            color = CoulombTheme.colors.textPrimary,
            style = CoulombTheme.typography.title,
        )
        Text(
            stringResource(R.string.charging_error_body),
            Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8),
            color = CoulombTheme.colors.textSecondary,
            style = CoulombTheme.typography.body,
        )
        CoulombOutlinedButton(
            text = stringResource(R.string.charging_error_action),
            onClick = retry,
            modifier = Modifier.padding(top = CoulombSpacing.Space16),
        )
    }
}

internal const val DASH = "–"

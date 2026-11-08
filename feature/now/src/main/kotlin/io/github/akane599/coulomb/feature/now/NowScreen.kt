// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.now

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import java.time.ZoneId

/**
 * Now (SPEC 10.2): the three-second answer. Insights join in P5 (SPEC 17).
 *
 * @param onSeeAllDrains opens Drain with the same window.
 * @param onOpenPrivileges opens Privileges, where a missing capability is fixed.
 */
@Composable
public fun NowScreen(
    onSeeAllDrains: () -> Unit,
    onOpenPrivileges: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    NowContent(
        state = state,
        refreshing = refreshing,
        actions =
            NowActions(
                refresh = viewModel::refresh,
                retry = viewModel::retry,
                seeAllDrains = onSeeAllDrains,
                openPrivileges = onOpenPrivileges,
                icon = viewModel::icon,
            ),
        modifier = modifier,
    )
}

/** What Now's controls do; the screen itself holds no logic. */
internal class NowActions(
    val refresh: () -> Unit,
    val retry: () -> Unit,
    val seeAllDrains: () -> Unit,
    val openPrivileges: () -> Unit,
    val icon: suspend (packageName: String, sizePx: Int) -> ImageBitmap?,
)

/** Now's column on wide windows (ADR-0034). */
private val MaxContentWidth = 720.dp

/** Which chart's table is open. */
internal enum class OpenTable { LEVEL, CURRENT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowContent(
    state: NowUiState,
    refreshing: Boolean,
    actions: NowActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val formats = rememberFormats()
    var table by rememberSaveable { mutableStateOf<OpenTable?>(null) }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = actions.refresh, modifier = modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = currentGutter(), end = currentGutter(), bottom = CoulombSpacing.Space48)
                // One readable column on wide windows: names and their figures stay in one glance.
                .wrapContentWidth(Alignment.Start)
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth(),
        ) {
            when (state) {
                NowUiState.Loading -> {
                    Skeleton()
                }

                NowUiState.Error -> {
                    ErrorState(actions.retry)
                }

                is NowUiState.Ready -> {
                    FadeInOnce {
                        Column {
                            Hero(state, formats, zone, onViewTable = { table = OpenTable.LEVEL })
                            if (state.discharge != null) SessionStrip(state.discharge, formats)
                            TopDrains(state, formats, zone, actions)
                            LiveCurrent(state, formats, onViewTable = { table = OpenTable.CURRENT })
                        }
                    }
                }
            }
        }
    }
    val ready = state as? NowUiState.Ready
    val open = table
    if (ready != null && open != null) {
        DataTableSheet(table = tableOf(open, ready, formats, zone), onDismiss = { table = null })
    }
}

@Composable
internal fun tableOf(
    open: OpenTable,
    state: NowUiState.Ready,
    formats: Formats,
    zone: ZoneId,
): DataTable {
    val time = stringResource(R.string.now_column_time)
    return when (open) {
        OpenTable.LEVEL -> {
            DataTable(
                title = stringResource(R.string.now_level_table),
                columns = listOf(time, stringResource(R.string.now_column_level)),
                rows =
                    state.levels.map {
                        listOf(formats.clock(it.wallMs, zone, state.battery.wallMs), formats.percent(it.levelPct))
                    },
                provenance = Provenance.MODELED,
            )
        }

        OpenTable.CURRENT -> {
            DataTable(
                title = stringResource(R.string.now_current_table),
                columns = listOf(time, stringResource(R.string.now_column_current)),
                rows =
                    state.live.asReversed().map {
                        listOf(
                            formats.clockWithSeconds(it.wallMs, zone),
                            formats.signedCurrent(it.currentMa).joined(),
                        )
                    },
                provenance = Provenance.MEASURED,
            )
        }
    }
}

/** SPEC 10.11 loading: a still skeleton in the final layout's shape: hero, scale, verdict, strip, rows. */
@Composable
private fun Skeleton() {
    val loading = stringResource(R.string.now_loading)
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16).semantics { contentDescription = loading }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column {
                SkeletonBlock(60.dp, Modifier.width(132.dp))
                SkeletonBlock(12.dp, Modifier.padding(top = CoulombSpacing.Space4).width(112.dp))
            }
            Column(Modifier.padding(start = CoulombSpacing.Space16)) {
                SkeletonBlock(26.dp, Modifier.width(200.dp))
                SkeletonBlock(20.dp, Modifier.padding(top = CoulombSpacing.Space4).width(150.dp))
            }
        }
        SkeletonBlock(136.dp, Modifier.padding(top = CoulombSpacing.Space16).fillMaxWidth())
        SkeletonBlock(48.dp, Modifier.padding(top = CoulombSpacing.Space16).fillMaxWidth())
        SkeletonBlock(104.dp, Modifier.padding(top = currentSectionGap()).fillMaxWidth())
        SkeletonBlock(26.dp, Modifier.padding(top = currentSectionGap()).width(120.dp))
        Column(Modifier.padding(top = CoulombSpacing.Space12)) {
            repeat(NowUiState.TOP_DRAINS) {
                Row(
                    Modifier.heightIn(min = 72.dp).padding(vertical = CoulombSpacing.Space12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkeletonBlock(40.dp, Modifier.width(40.dp))
                    Column(Modifier.padding(start = CoulombSpacing.Space16).weight(1f)) {
                        SkeletonBlock(16.dp, Modifier.width(140.dp))
                        SkeletonBlock(4.dp, Modifier.padding(top = CoulombSpacing.Space16).fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** SPEC 10.11 error: what happened and what to do. */
@Composable
private fun ErrorState(retry: () -> Unit) {
    Column(Modifier.padding(top = CoulombSpacing.Space24)) {
        Text(
            stringResource(R.string.now_error_title),
            Modifier.semantics { heading() },
            color = CoulombTheme.colors.textPrimary,
            style = CoulombTheme.typography.title,
        )
        Text(
            stringResource(R.string.now_error_body),
            modifier = Modifier.padding(top = CoulombSpacing.Space8),
            color = CoulombTheme.colors.textSecondary,
            style = CoulombTheme.typography.body,
        )
        CoulombOutlinedButton(
            text = stringResource(R.string.now_error_action),
            onClick = retry,
            modifier = Modifier.padding(top = CoulombSpacing.Space16),
        )
    }
}

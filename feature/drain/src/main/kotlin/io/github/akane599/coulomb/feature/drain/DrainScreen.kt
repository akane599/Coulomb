// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

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
import androidx.compose.material3.Text
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
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.designsystem.component.CoulombOutlinedButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombViewTabs
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.component.SkeletonBlock
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.designsystem.theme.currentSectionGap
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import java.time.LocalDate
import java.time.ZoneId

/**
 * Drain (SPEC 10.3): who and what used the battery in the chosen window.
 *
 * @param onOpenApp opens App detail for a UID; null while there is none to open.
 */
@Composable
public fun DrainScreen(
    onOpenApp: ((uid: Int) -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: DrainViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DrainContent(
        state = state,
        actions =
            DrainActions(
                choose = viewModel::choose,
                includeSystem = viewModel::includeSystem,
                scale = viewModel::scale,
                retry = viewModel::retry,
                openApp = onOpenApp,
                icon = viewModel::icon,
            ),
        modifier = modifier,
    )
}

/** What Drain's controls do; the screen itself holds no logic. */
internal class DrainActions(
    val choose: (DrainSpan) -> Unit,
    val includeSystem: (Boolean) -> Unit,
    val scale: (Boolean) -> Unit,
    val retry: () -> Unit,
    val openApp: ((Int) -> Unit)?,
    val icon: suspend (packageName: String, sizePx: Int) -> ImageBitmap?,
)

/** The three views of the same window (SPEC 10.3). */
internal enum class Lens { APPS, COMPONENTS, WAKEUPS }

/** Drain's column on wide windows, as Now's (ADR-0034). */
private val MaxContentWidth = 720.dp

@Composable
internal fun DrainContent(
    state: DrainUiState,
    actions: DrainActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
    initialLens: Lens = Lens.APPS,
) {
    val formats = rememberFormats()
    var lens by rememberSaveable { mutableStateOf(initialLens) }
    var sheet by rememberSaveable { mutableStateOf(false) }
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
            DrainUiState.Loading -> {
                Skeleton()
            }

            DrainUiState.Error -> {
                ErrorState(actions.retry)
            }

            is DrainUiState.Ready -> {
                FadeInOnce {
                    Column {
                        WindowChoice(state.chosen, formats, onChoose = actions.choose, onMore = { sheet = true })
                        val report = state.report
                        if (report == null) {
                            EmptyWindow(state.chosen)
                        } else {
                            WindowNote(report, formats, zone)
                            Total(report, formats)
                            val labels =
                                listOf(
                                    stringResource(R.string.drain_lens_apps),
                                    stringResource(R.string.drain_lens_components),
                                    stringResource(R.string.drain_lens_wakeups),
                                )
                            CoulombViewTabs(
                                tabs = labels,
                                selected = lens.ordinal,
                                onSelect = { lens = Lens.entries[it] },
                                modifier = Modifier.padding(top = currentSectionGap()),
                            )
                            when (lens) {
                                Lens.APPS -> AppsLens(state, report, formats, actions)
                                Lens.COMPONENTS -> ComponentsLens(report, formats)
                                Lens.WAKEUPS -> WakeupsLens(report, formats)
                            }
                        }
                    }
                }
            }
        }
    }
    val ready = state as? DrainUiState.Ready
    if (sheet && ready != null) {
        MoreSheet(
            chosen = ready.chosen,
            today = today,
            onChoose = {
                actions.choose(it)
                sheet = false
            },
            onDismiss = { sheet = false },
        )
    }
}

/** Why the chosen window has nothing to show yet (SPEC 10.11 empty). */
@Composable
private fun EmptyWindow(chosen: DrainSpan) {
    val text =
        when (chosen) {
            DrainSpan.SinceUnplugged -> R.string.drain_empty_since_unplugged
            DrainSpan.Today -> R.string.drain_empty_today
            DrainSpan.Last24Hours -> R.string.drain_empty_last_24h
            DrainSpan.LastNight -> R.string.drain_empty_last_night
            is DrainSpan.Days -> R.string.drain_empty_days
        }
    Text(
        stringResource(text),
        Modifier.padding(top = CoulombSpacing.Space16),
        color = CoulombTheme.colors.textSecondary,
        style = CoulombTheme.typography.body,
    )
}

/** SPEC 10.11 loading: a still skeleton in the final layout's shape. */
@Composable
private fun Skeleton() {
    val loading = stringResource(R.string.drain_loading)
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8).semantics { contentDescription = loading }) {
        SkeletonBlock(40.dp, Modifier.width(300.dp))
        SkeletonBlock(16.dp, Modifier.padding(top = CoulombSpacing.Space8).width(180.dp))
        SkeletonBlock(60.dp, Modifier.padding(top = CoulombSpacing.Space24).width(180.dp))
        SkeletonBlock(14.dp, Modifier.padding(top = CoulombSpacing.Space16).fillMaxWidth())
        SkeletonBlock(40.dp, Modifier.padding(top = currentSectionGap()).fillMaxWidth())
        Column(Modifier.padding(top = CoulombSpacing.Space16)) {
            repeat(SKELETON_ROWS) {
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

private const val SKELETON_ROWS = 4

/** SPEC 10.11 error: what happened and what to do. */
@Composable
private fun ErrorState(retry: () -> Unit) {
    Column(Modifier.padding(top = CoulombSpacing.Space24)) {
        Text(
            stringResource(R.string.drain_error_title),
            Modifier.semantics { heading() },
            color = CoulombTheme.colors.textPrimary,
            style = CoulombTheme.typography.title,
        )
        Text(
            stringResource(R.string.drain_error_body),
            modifier = Modifier.padding(top = CoulombSpacing.Space8),
            color = CoulombTheme.colors.textSecondary,
            style = CoulombTheme.typography.body,
        )
        CoulombOutlinedButton(
            text = stringResource(R.string.drain_error_action),
            onClick = retry,
            modifier = Modifier.padding(top = CoulombSpacing.Space16),
        )
    }
}

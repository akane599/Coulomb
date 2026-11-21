// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.now

import androidx.activity.compose.ReportDrawnWhen
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.akane599.coulomb.core.data.BatteryNow
import io.github.akane599.coulomb.core.designsystem.component.CoulombOutlinedButton
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.component.SkeletonBlock
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.designsystem.theme.currentSectionGap
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.insight.ActionId
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.ui.DataTable
import io.github.akane599.coulomb.core.ui.DataTableSheet
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.insight.InsightCopy
import io.github.akane599.coulomb.core.ui.page.ErrorState
import io.github.akane599.coulomb.core.ui.page.Note
import kotlinx.coroutines.delay
import java.time.ZoneId

/**
 * Now (SPEC 10.2): the three-second answer.
 *
 * @param onSeeAllDrains opens Drain with the same window.
 * @param onOpenPrivileges opens Privileges, where a missing capability is fixed.
 * @param onOpenApp opens an app's detail for the same window.
 */
@Composable
public fun NowScreen(
    onSeeAllDrains: () -> Unit,
    onOpenPrivileges: () -> Unit,
    modifier: Modifier = Modifier,
    onInsightAction: (ActionId, Insight) -> Unit = { _, _ -> },
    onOpenApp: (Int) -> Unit = {},
    viewModel: NowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val lite by viewModel.lite.collectAsStateWithLifecycle()
    // Time to verdict (SPEC 1.3, 16.5): the app is fully drawn once Now shows the battery.
    ReportDrawnWhen { state is NowUiState.Ready }
    NowContent(
        state = state,
        refreshing = refreshing,
        actions =
            NowActions(
                refresh = viewModel::refresh,
                retry = viewModel::retry,
                seeAllDrains = {
                    viewModel.seeAllDrains()
                    onSeeAllDrains()
                },
                openPrivileges = onOpenPrivileges,
                openApp = { uid ->
                    // The same window as the row came from, as "Everything else" does.
                    viewModel.seeAllDrains()
                    onOpenApp(uid)
                },
                icon = viewModel::icon,
                snoozeInsight = viewModel::snoozeInsight,
                dismissInsight = viewModel::dismissInsight,
                showAllInsights = viewModel::showAllInsights,
                insightAction = onInsightAction,
            ),
        modifier = modifier,
        screen = rememberScreenContext(),
        nowMs = rememberResumedNowMs((state as? NowUiState.Ready)?.battery?.wallMs?.plus(BatteryNow.STALE_MS)),
        lite = lite,
    )
}

/** What Now's controls do; the screen itself holds no logic. */
internal class NowActions(
    val refresh: () -> Unit,
    val retry: () -> Unit,
    val seeAllDrains: () -> Unit,
    val openPrivileges: () -> Unit,
    val icon: suspend (packageName: String, sizePx: Int) -> ImageBitmap?,
    val snoozeInsight: (id: Long, forMs: Long) -> Unit = { _, _ -> },
    val dismissInsight: (id: Long) -> Unit = {},
    /** The button of an insight's card: where it leads is the app's to say. */
    val insightAction: (ActionId, Insight) -> Unit = { _, _ -> },
    val openApp: (uid: Int) -> Unit = {},
    val showAllInsights: () -> Unit = {},
)

/** What the Macrobenchmark waits for: Now's content once the verdict shows (SPEC 16.5). */
internal const val NOW_VERDICT_TAG = "now-verdict"

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
    screen: ScreenContext? = null,
    // Null where the reading's age is not judged (previews and goldens).
    nowMs: Long? = null,
    lite: Boolean = false,
) {
    val formats = rememberFormats()
    var table by rememberSaveable { mutableStateOf<OpenTable?>(null) }
    // The insight whose "Why am I seeing this?" is open, by id.
    var why by rememberSaveable { mutableStateOf<Long?>(null) }
    val resources = LocalResources.current
    val copy = remember(resources, formats, zone) { InsightCopy(resources, formats, zone) }
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
                    ErrorState(
                        stringResource(R.string.now_error_title),
                        stringResource(R.string.now_error_body),
                        stringResource(R.string.now_error_action),
                        actions.retry,
                    )
                }

                is NowUiState.Ready -> {
                    FadeInOnce {
                        Column(Modifier.testTag(NOW_VERDICT_TAG)) {
                            val staleSince =
                                nowMs?.takeIf { state.battery.isStale(it) }?.let { formats.clock(state.battery.wallMs, zone, it) }
                            val staleNote =
                                staleSince?.let { stringResource(if (lite) R.string.now_as_of else R.string.now_as_of_full, it) }
                            Hero(state, formats, zone, onViewTable = { table = OpenTable.LEVEL }, staleNote = staleNote)
                            if (state.discharge != null) {
                                SessionStrip(state.discharge, formats)
                                screen?.let { ScreenContextNote(it, formats) }
                            }
                            TopDrains(state, formats, zone, actions)
                            val more = state.moreInsights
                            Insights(
                                state.insights,
                                copy,
                                actions,
                                onWhy = { why = it },
                                more =
                                    if (more > 0) {
                                        pluralStringResource(R.plurals.now_insights_more, more, formats.integer(more.toLong()))
                                    } else {
                                        null
                                    },
                            )
                            LiveCurrent(state, formats, onViewTable = { table = OpenTable.CURRENT }, staleSince = staleSince, lite = lite)
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
    // An insight that left the list while its sheet was open closes the sheet with it.
    val asked = ready?.insights?.firstOrNull { it.insight.id == why }
    if (ready != null && asked != null) {
        DataTableSheet(table = copy.evidence(asked.insight, zone, ready.battery.wallMs), onDismiss = { why = null })
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

/**
 * The wall clock, read again each time the screen resumes and once more at [changesAt], when the
 * reading turns old, so an open screen shows its age too (ADR-0069). Nothing ticks in between.
 */
@Composable
internal fun rememberResumedNowMs(changesAt: Long?): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, changesAt) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            now = System.currentTimeMillis()
            val wait = (changesAt ?: return@repeatOnLifecycle) - now
            if (wait > 0) {
                delay(wait + 1)
                now = System.currentTimeMillis()
            }
        }
    }
    return now
}

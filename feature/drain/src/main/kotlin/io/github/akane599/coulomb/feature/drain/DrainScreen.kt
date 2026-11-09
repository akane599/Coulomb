// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.designsystem.component.CoulombOutlinedButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombSwitchRow
import io.github.akane599.coulomb.core.designsystem.component.CoulombViewTabs
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.component.SkeletonBlock
import io.github.akane599.coulomb.core.designsystem.theme.CoulombMotion
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.WindowWidth
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.designsystem.theme.currentSectionGap
import io.github.akane599.coulomb.core.designsystem.theme.currentWindowWidth
import io.github.akane599.coulomb.core.ui.DataTableSheet
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.privilege.PrivilegeIssueBlock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Drain (SPEC 10.3): who and what used the battery in the chosen window.
 *
 * @param onOpenApp opens App detail for a UID; null while there is none to open.
 * @param onOpenPrivileges opens Privileges, where a privilege issue is fixed.
 * @param onOpenCompare opens Compare two periods (ADR-0038).
 */
@Composable
public fun DrainScreen(
    onOpenApp: ((uid: Int) -> Unit)?,
    onOpenPrivileges: () -> Unit,
    onOpenCompare: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DrainViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val actions =
        remember(viewModel, onOpenApp, onOpenPrivileges, onOpenCompare) {
            DrainActions(
                choose = viewModel::choose,
                refresh = viewModel::refresh,
                includeSystem = viewModel::includeSystem,
                scale = viewModel::scale,
                retry = viewModel::retry,
                openApp = onOpenApp,
                openPrivileges = onOpenPrivileges,
                openCompare = onOpenCompare,
                icon = viewModel::icon,
                peekIcon = viewModel::peekIcon,
            )
        }
    DrainContent(state = state, actions = actions, modifier = modifier, refreshing = refreshing)
}

/** What Drain's controls do; the screen itself holds no logic. */
@Suppress("LongParameterList") // One callback per control.
internal class DrainActions(
    val choose: (DrainSpan) -> Unit,
    val refresh: () -> Unit,
    val includeSystem: (Boolean) -> Unit,
    val scale: (Boolean) -> Unit,
    val retry: () -> Unit,
    val openApp: ((Int) -> Unit)?,
    val openPrivileges: () -> Unit,
    val openCompare: () -> Unit,
    val icon: suspend (packageName: String, sizePx: Int) -> ImageBitmap?,
    val peekIcon: (packageName: String, sizePx: Int) -> ImageBitmap? = { _, _ -> null },
)

/** The three views of the same window (SPEC 10.3). */
internal enum class Lens { APPS, COMPONENTS, WAKEUPS }

/** Two columns have a gutter at each side and one between them. */
private const val GUTTERS = 3

/**
 * Each column of the two-column layout needs this much for a row's name and figure at 100% text
 * (ADR-0036 §23); larger text needs it in proportion.
 */
private val MinColumnWidth = 280.dp

/**
 * Below this height the selector scrolls with the summary, so it cannot crowd the columns out, and
 * the range picker opens for typing (adapt gate, 2026-11-09).
 */
internal val ShortWindowHeight = 480.dp

/** The two columns side by side on very wide windows: the summary column, then the lens column. */
internal val MaxTwoColumnWidth = 1200.dp
internal const val SUMMARY_WEIGHT = 0.42f
internal const val LENS_WEIGHT = 0.58f

/**
 * The window fits two columns of [MinColumnWidth], scaled with the text (SPEC 10.10 medium and up;
 * ADR-0036 §23). A landscape phone at 200% text keeps one list.
 */
@Composable
internal fun fitsTwoColumns(
    maxWidth: Dp,
    gutter: Dp,
): Boolean {
    val column = MinColumnWidth * LocalDensity.current.fontScale.coerceAtLeast(1f)
    return currentWindowWidth() != WindowWidth.Compact && maxWidth - gutter * GUTTERS >= column * 2
}

/** What the lens lists keep open, saved per window: the remainder, the groups showing all rows, and the apps showing their components. */
internal class ListState(
    val remainderOpen: Boolean,
    val setRemainderOpen: (Boolean) -> Unit,
    /** Bit [WakeGroup.ordinal] for a group, bit [ALARMS_BIT] for the alarms. */
    val openGroups: Int,
    val toggleGroup: (Int) -> Unit,
    /** The UIDs whose components are open (ADR-0035 §7). */
    private val openApps: IntArray = IntArray(0),
    val toggleApp: (Int) -> Unit = {},
) {
    fun appOpen(uid: Int): Boolean = uid in openApps
}

internal const val ALARMS_BIT = 3

@Composable
internal fun DrainContent(
    state: DrainUiState,
    actions: DrainActions,
    modifier: Modifier = Modifier,
    // Read again with each state, so the pickers move on past midnight, but not on every recomposition.
    zone: ZoneId = remember(state) { ZoneId.systemDefault() },
    today: LocalDate = remember(state, zone) { LocalDate.now(zone) },
    initialLens: Lens = Lens.APPS,
    refreshing: Boolean = false,
) {
    val gutter = currentGutter()
    var sheet by rememberSaveable { mutableStateOf(false) }
    when (state) {
        DrainUiState.Loading -> {
            BoxWithConstraints(modifier.fillMaxSize()) {
                val twoColumns = fitsTwoColumns(maxWidth, gutter)
                Column(Modifier.widthIn(max = MaxTwoColumnWidth).fillMaxSize().padding(horizontal = gutter)) {
                    Skeleton(twoColumns)
                }
            }
        }

        DrainUiState.Error -> {
            Column(modifier.fillMaxSize().padding(horizontal = gutter)) { ErrorState(actions.retry) }
        }

        is DrainUiState.Ready -> {
            FadeInOnce(modifier.fillMaxSize()) {
                // As on Now: a pull asks for a heavy snapshot, and the report follows (SPEC 7.2).
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
                    ReadyContent(state, actions, zone, today, initialLens, onMore = { sheet = true })
                }
            }
        }
    }
    val ready = state as? DrainUiState.Ready
    if (sheet && ready != null) {
        MoreSheet(
            chosen = ready.chosen,
            today = today,
            onChoose = actions.choose,
            onCompare = actions.openCompare,
            onDismiss = { sheet = false },
        )
    }
}

/**
 * One list on compact widths. Where the window fits two columns of [MinColumnWidth] (SPEC 10.10
 * medium and up), the selector spans both, the note and total scroll in the first and the lens in
 * the second, so a landscape phone shows rows at once (ADR-0036 §23).
 */
@Composable
@Suppress("LongParameterList") // The screen's state, its controls and its clock.
private fun ReadyContent(
    state: DrainUiState.Ready,
    actions: DrainActions,
    zone: ZoneId,
    today: LocalDate,
    initialLens: Lens,
    onMore: () -> Unit,
) {
    val formats = rememberFormats()
    val gutter = currentGutter()
    var lens by rememberSaveable { mutableStateOf(initialLens) }
    val span = state.report?.span
    var remainderOpen by rememberSaveable(span) { mutableStateOf(false) }
    var openGroups by rememberSaveable(span) { mutableIntStateOf(0) }
    // An IntArray, so the open rows survive a configuration change in the saved state.
    var openApps by rememberSaveable(span) { mutableStateOf(IntArray(0)) }
    val lists =
        ListState(
            remainderOpen,
            { remainderOpen = it },
            openGroups,
            { bit -> openGroups = openGroups xor (1 shl bit) },
            openApps,
            { uid -> openApps = if (uid in openApps) openApps.filter { it != uid }.toIntArray() else openApps + uid },
        )
    // Built once per report and choice, not on every pass of the list (SPEC 16.4).
    val apps = state.report?.let { remember(it, state.includeSystem, state.scaled) { appsView(it, state.includeSystem, state.scaled) } }
    val wakes = state.report?.let { remember(it) { wakeGroups(it) } }
    val views = if (apps != null && wakes != null) LensViews(apps, wakes) else null
    var table by rememberSaveable { mutableStateOf<DrainTable?>(null) }
    val viewTable: (DrainTable) -> Unit = { table = it }
    val bottom = CoulombSpacing.Space48
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoColumns = fitsTwoColumns(maxWidth, gutter)
        if (!twoColumns) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = gutter, end = gutter, bottom = bottom)) {
                item(key = "summary", contentType = "summary") {
                    Column {
                        Controls(state, formats, actions, onMore)
                        Summary(state, formats, zone, today, viewTable)
                    }
                }
                lensItems(state, views, lens, { lens = it }, formats, actions, lists, viewTable)
            }
        } else {
            // The selector and an issue concern both columns, so they span them and stay in place;
            // in a short window they scroll with the summary instead, so the columns keep their height.
            val short = maxHeight < ShortWindowHeight
            Column(Modifier.widthIn(max = MaxTwoColumnWidth).fillMaxSize().padding(horizontal = gutter)) {
                if (!short) Controls(state, formats, actions, onMore)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gutter)) {
                    Column(
                        Modifier
                            .weight(SUMMARY_WEIGHT)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = bottom),
                    ) {
                        if (short) Controls(state, formats, actions, onMore)
                        Summary(state, formats, zone, today, viewTable)
                    }
                    LazyColumn(Modifier.weight(LENS_WEIGHT).fillMaxHeight(), contentPadding = PaddingValues(bottom = bottom)) {
                        lensItems(state, views, lens, { lens = it }, formats, actions, lists, viewTable, besideSummary = true)
                    }
                }
            }
        }
    }
    val open = table
    val report = state.report
    if (open != null && report != null) DataTableSheet(tableOf(open, report, formats), onDismiss = { table = null })
}

/** The window selector, and a privilege issue under it. */
@Composable
private fun Controls(
    state: DrainUiState.Ready,
    formats: Formats,
    actions: DrainActions,
    onMore: () -> Unit,
) = Column {
    WindowChoice(state.chosen, formats, onChoose = actions.choose, onMore = onMore)
    // Without snapshots the window stops where the last one was taken (SPEC 10.11 partial).
    state.issue?.let { PrivilegeIssueBlock(it, actions.openPrivileges, Modifier.padding(top = CoulombSpacing.Space16)) }
}

/** The window's note and total, or why there are none. */
@Composable
@Suppress("LongParameterList") // The window's state, its clock and the lens it serves.
private fun Summary(
    state: DrainUiState.Ready,
    formats: Formats,
    zone: ZoneId,
    today: LocalDate,
    viewTable: (DrainTable) -> Unit,
) = Column {
    val report = state.report
    when {
        // The previous window's figures never sit under the new choice (SPEC 0.2).
        !state.current -> {
            SummarySkeleton()
        }

        // With an issue, no snapshot is coming: the issue says why, without a promise.
        report == null -> {
            if (state.issue == null) EmptyWindow(state.chosen)
        }

        else -> {
            WindowNote(report, formats, zone, today)
            Total(report, formats, onViewTable = { viewTable(DrainTable.RECONCILE) })
        }
    }
}

/** The lens tabs and the chosen lens's rows, each its own keyed item (SPEC 15: stable keys). */
@Suppress("LongParameterList") // One list's inputs.
private fun LazyListScope.lensItems(
    state: DrainUiState.Ready,
    views: LensViews?,
    lens: Lens,
    choose: (Lens) -> Unit,
    formats: Formats,
    actions: DrainActions,
    lists: ListState,
    viewTable: (DrainTable) -> Unit,
    besideSummary: Boolean = false,
) {
    val report = state.report
    if (!state.current) {
        item(key = "lens-skeleton", contentType = "skeleton") { LensSkeleton(besideSummary) }
        return
    }
    if (report == null || views == null) return
    item(key = "tabs", contentType = "tabs") { LensTabs(lens, choose, besideSummary) }
    when (lens) {
        Lens.APPS -> {
            appsItems(state, report, views.apps, formats, actions, lists)
        }

        Lens.COMPONENTS -> {
            componentItems(report, formats, onViewTable = { viewTable(DrainTable.COMPONENTS) })
        }

        Lens.WAKEUPS -> {
            wakeupItems(report, views.wakes, formats, lists)
        }
    }
}

/** What the lenses show of one report, built once per report and choice. */
@Immutable
internal class LensViews(
    val apps: AppsView,
    val wakes: List<WakeGroupView>,
)

@Composable
internal fun LensTabs(
    lens: Lens,
    choose: (Lens) -> Unit,
    besideSummary: Boolean,
) {
    val labels =
        listOf(
            stringResource(R.string.drain_lens_apps),
            stringResource(R.string.drain_lens_components),
            stringResource(R.string.drain_lens_wakeups),
        )
    CoulombViewTabs(
        tabs = labels,
        selected = lens.ordinal,
        onSelect = { choose(Lens.entries[it]) },
        // Beside the summary the tabs line up with the selector; under it they open a section.
        modifier = Modifier.padding(top = if (besideSummary) CoulombSpacing.Space8 else currentSectionGap()),
    )
}

/**
 * A row's motion in Drain's lists: a reorder slides on the default spatial spring, and a row added
 * by a tap or a new report fades in on the effects spring, so it never draws over the rows sliding
 * past it (SPEC 11.6; animate gate 2026-11-09). Under reduced motion the row takes its place at
 * once. `animateItem` is a member of the item scope, so this starts there rather than from a Modifier.
 */
@SuppressLint("ModifierFactoryExtensionFunction")
@Composable
internal fun LazyItemScope.rowMotion(): Modifier {
    val reduced = CoulombTheme.reducedMotion
    return Modifier.animateItem(
        fadeInSpec = if (reduced) null else CoulombMotion.effects(),
        placementSpec = if (reduced) null else CoulombMotion.spatial(),
        fadeOutSpec = null,
    )
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
        Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16),
        color = CoulombTheme.colors.textSecondary,
        style = CoulombTheme.typography.body,
    )
}

/** SPEC 10.11 loading: a still skeleton in the final layout's shape. */
@Composable
private fun Skeleton(twoColumns: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8)) {
        SkeletonBlock(40.dp, Modifier.width(300.dp))
        // The same columns as the content that replaces it (SPEC 10.11).
        if (twoColumns) {
            Row(horizontalArrangement = Arrangement.spacedBy(currentGutter())) {
                Column(Modifier.weight(SUMMARY_WEIGHT)) { SummarySkeleton() }
                Column(Modifier.weight(LENS_WEIGHT)) { LensSkeleton(besideSummary = true) }
            }
        } else {
            SummarySkeleton()
            LensSkeleton(besideSummary = false)
        }
    }
}

/** The note, total and reconcile bar's skeleton: while a newly chosen window loads, and in [Skeleton]. */
@Composable
internal fun SummarySkeleton() {
    val loading = stringResource(R.string.drain_loading)
    Column(Modifier.fillMaxWidth().semantics { contentDescription = loading }) {
        SkeletonBlock(16.dp, Modifier.padding(top = CoulombSpacing.Space8).width(180.dp))
        SkeletonBlock(60.dp, Modifier.padding(top = CoulombSpacing.Space24).width(180.dp))
        SkeletonBlock(14.dp, Modifier.padding(top = CoulombSpacing.Space16).fillMaxWidth())
    }
}

/** The tabs and rows' skeleton, which TalkBack skips: [SummarySkeleton] already says it is loading. */
@Composable
internal fun LensSkeleton(besideSummary: Boolean) {
    Column(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
        SkeletonBlock(40.dp, Modifier.padding(top = if (besideSummary) CoulombSpacing.Space8 else currentSectionGap()).fillMaxWidth())
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

private val ErrorMaxWidth = 560.dp

/** SPEC 10.11 error: what happened and what to do. */
@Composable
internal fun ErrorState(retry: () -> Unit) {
    // A reading measure, so the message does not run across a tablet.
    Column(Modifier.widthIn(max = ErrorMaxWidth).padding(top = CoulombSpacing.Space24)) {
        Text(
            stringResource(R.string.drain_error_title),
            Modifier.fillMaxWidth().semantics { heading() },
            color = CoulombTheme.colors.textPrimary,
            style = CoulombTheme.typography.title,
        )
        Text(
            stringResource(R.string.drain_error_body),
            modifier = Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8),
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

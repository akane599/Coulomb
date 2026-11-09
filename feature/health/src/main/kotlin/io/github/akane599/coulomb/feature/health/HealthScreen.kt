// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.data.HealthReport
import io.github.akane599.coulomb.core.designsystem.component.CoulombOutlinedButton
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.component.SkeletonBlock
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.WindowWidth
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.designsystem.theme.currentSectionGap
import io.github.akane599.coulomb.core.designsystem.theme.currentWindowWidth
import io.github.akane599.coulomb.core.model.BatteryDates
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.DataTable
import io.github.akane599.coulomb.core.ui.DataTableSheet
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Health (SPEC 10.8): the capacity estimate with its cross-check, cycles and age, capacity over
 * time, charging habits, charge sessions and chargers. A session opens beside the list where the
 * window has room ([selectedSession] marks it), else over it.
 */
@Composable
public fun HealthScreen(
    onOpenSession: (Long) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
    selectedSession: Long? = null,
    viewModel: HealthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions =
        remember(viewModel, onOpenSession, onSeeAll) {
            HealthActions(
                retry = viewModel::retry,
                openSession = onOpenSession,
                seeAll = onSeeAll,
                rename = viewModel::rename,
                setDesign = viewModel::setDesignCapacity,
                useReportedDesign = viewModel::useReportedDesignCapacity,
            )
        }
    HealthContent(state, actions, modifier, selectedSession)
}

/** What Health's controls do. */
internal class HealthActions(
    val retry: () -> Unit,
    val openSession: (Long) -> Unit,
    val seeAll: () -> Unit,
    val rename: (chargerId: Long, name: String) -> Unit,
    val setDesign: (mah: Int) -> Unit,
    val useReportedDesign: () -> Unit,
)

/** The tables Health's charts open ("View as table", SPEC 11.8). */
internal enum class HealthTable { SCALE, TREND }

@Composable
internal fun HealthContent(
    state: HealthUiState,
    actions: HealthActions,
    modifier: Modifier = Modifier,
    selectedSession: Long? = null,
    zone: ZoneId = remember(state) { ZoneId.systemDefault() },
    nowMs: Long = remember(state) { System.currentTimeMillis() },
) {
    val gutter = currentGutter()
    when (state) {
        HealthUiState.Loading -> {
            Column(modifier.fillMaxSize().padding(horizontal = gutter)) { Skeleton() }
        }

        HealthUiState.Error -> {
            Column(modifier.fillMaxSize().padding(horizontal = gutter)) { ErrorState(actions.retry) }
        }

        is HealthUiState.Ready -> {
            FadeInOnce(modifier.fillMaxSize()) { Ready(state.report, state.dates, actions, selectedSession, zone, nowMs) }
        }
    }
}

/**
 * One list on compact widths. Where two columns of [MinColumnWidth] fit (SPEC 10.10 medium and
 * up), the battery's state fills the first (estimate, cross-check, cycles, chart) and its use the
 * second (habits, sessions, chargers), as App detail splits its figure from its evidence.
 */
@Composable
@Suppress("LongParameterList") // The report and what the screen does with it.
private fun Ready(
    report: HealthReport,
    dates: BatteryDates,
    actions: HealthActions,
    selectedSession: Long?,
    zone: ZoneId,
    nowMs: Long,
) {
    val formats = rememberFormats()
    val gutter = currentGutter()
    var table by rememberSaveable { mutableStateOf<HealthTable?>(null) }
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var design by rememberSaveable { mutableStateOf(false) }
    // Built once per input, so opening a sheet or dialog does not recompose every visible section.
    val sections =
        remember(report, dates, formats, zone, nowMs, selectedSession, actions) {
            Sections(
                report = report,
                dates = dates,
                formats = formats,
                zone = zone,
                nowMs = nowMs,
                selected = selectedSession,
                actions = actions,
                events = SectionEvents(viewTable = { table = it }, rename = { renaming = it }, design = { design = true }),
            )
        }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val column = MinColumnWidth * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val twoColumns = currentWindowWidth() != WindowWidth.Compact && maxWidth - gutter * GUTTERS >= column * 2
        if (!twoColumns) {
            // One column stays a readable measure on a wide window at large text (SPEC 10.10).
            LazyColumn(
                Modifier.widthIn(max = MaxOneColumnWidth).fillMaxSize().align(Alignment.TopCenter),
                contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Bottom),
            ) {
                sections.first(this)
                sections.second(this)
            }
        } else {
            Row(
                Modifier.widthIn(max = MaxTwoColumnWidth).fillMaxSize().padding(horizontal = gutter),
                horizontalArrangement = Arrangement.spacedBy(gutter),
            ) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = Bottom)) { sections.first(this) }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = Bottom)) {
                    sections.second(this, besideFirst = true)
                }
            }
        }
    }
    table?.let { open -> tableOf(open, report, formats)?.let { DataTableSheet(it, onDismiss = { table = null }) } }
    // A charger gone from the records closes its dialog.
    report.chargers.firstOrNull { it.id == renaming }?.let { charger ->
        RenameDialog(
            current = charger.name,
            onSave = { name ->
                actions.rename(charger.id, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    if (design) {
        DesignDialog(
            current = report.design,
            onSave = { mah ->
                actions.setDesign(mah)
                design = false
            },
            onUseReportedValue = {
                actions.useReportedDesign()
                design = false
            },
            onDismiss = { design = false },
        )
    }
}

/** What a section asks the screen to open. */
private class SectionEvents(
    val viewTable: (HealthTable) -> Unit,
    val rename: (chargerId: Long) -> Unit,
    val design: () -> Unit,
)

/** The screen's sections as list items, in the two groups the wide layout splits them into. */
@Suppress("LongParameterList") // What the sections read and do, held for the list's items.
private class Sections(
    val report: HealthReport,
    val dates: BatteryDates,
    val formats: Formats,
    val zone: ZoneId,
    val nowMs: Long,
    val selected: Long?,
    val actions: HealthActions,
    val events: SectionEvents,
) {
    fun first(scope: LazyListScope) {
        scope.item(key = "estimate", contentType = "estimate") {
            Estimate(report, formats, events.design) { events.viewTable(HealthTable.SCALE) }
        }
        scope.item(key = "cross-check", contentType = "cross-check") { CrossCheck(report, formats) }
        scope.item(key = "cycles", contentType = "cycles") { CyclesAge(report.cycles, dates, formats) }
        scope.item(key = "trend", contentType = "trend") { Trend(report, formats, zone) { events.viewTable(HealthTable.TREND) } }
    }

    fun second(
        scope: LazyListScope,
        besideFirst: Boolean = false,
    ) {
        scope.item(key = "habits", contentType = "habits") { Habits(report.habits, formats, first = besideFirst) }
        scope.item(key = "sessions", contentType = "sessions") {
            Sessions(report.sessions, report.moreSessions, selected, nowMs, zone, formats, actions.openSession, actions.seeAll)
        }
        scope.item(key = "chargers", contentType = "chargers") {
            Chargers(report.chargers, nowMs, zone, formats) { events.rename(it.id) }
        }
    }
}

/** The table behind a chart; null when the chart has nothing to list. */
@Composable
private fun tableOf(
    table: HealthTable,
    report: HealthReport,
    formats: Formats,
): DataTable? =
    when (table) {
        HealthTable.SCALE -> {
            report.capacity?.let { capacity ->
                val rows =
                    listOfNotNull(
                        listOf(stringResource(R.string.health_row_estimate), formats.mah(capacity.estimateMah).joined()),
                        listOf(
                            stringResource(R.string.health_row_interval),
                            formats.range(formats.mah(capacity.ciLowMah).joined(), formats.mah(capacity.ciHighMah).joined()),
                        ),
                        report.design?.let { listOf(stringResource(R.string.health_row_design), formats.mah(it.mah).joined()) },
                        capacity.healthPct?.let { listOf(stringResource(R.string.health_row_health), formats.percent(it.roundToInt())) },
                    )
                DataTable(
                    title = stringResource(R.string.health_table_scale),
                    columns = listOf(stringResource(R.string.health_column_figure), stringResource(R.string.health_column_value)),
                    rows = rows,
                    provenance = Provenance.ESTIMATED,
                )
            }
        }

        HealthTable.TREND -> {
            report.trend.days.takeIf { it.isNotEmpty() }?.let { days ->
                DataTable(
                    title = stringResource(R.string.health_table_trend),
                    columns =
                        listOf(
                            stringResource(R.string.health_column_day),
                            stringResource(R.string.health_column_estimate),
                            stringResource(R.string.health_column_interval),
                        ),
                    // Newest first, as the list a reader scans for the latest value.
                    rows =
                        days.asReversed().map { day ->
                            listOf(
                                formats.day(day.day),
                                formats.integer(day.mah.roundToLong()),
                                formats.range(formats.integer(day.lowMah.roundToLong()), formats.integer(day.highMah.roundToLong())),
                            )
                        },
                    provenance = Provenance.ESTIMATED,
                )
            }
        }
    }

/** Two columns have a gutter at each side and one between them. */
private const val GUTTERS = 3

/** Each column needs this much at 100% text; larger text needs it in proportion (as App detail). */
private val MinColumnWidth = 280.dp
private val MaxTwoColumnWidth = 1200.dp
private val MaxOneColumnWidth = 840.dp
private val Bottom: Dp = CoulombSpacing.Space48
private val ReadingWidth = 560.dp

/** SPEC 10.11 loading: a still skeleton in the hero's and first section's shape. */
@Composable
private fun Skeleton() {
    val loading = stringResource(R.string.health_loading)
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8).semantics { contentDescription = loading }) {
        SkeletonBlock(60.dp, Modifier.width(220.dp))
        SkeletonBlock(16.dp, Modifier.padding(top = CoulombSpacing.Space8).width(260.dp))
        SkeletonBlock(48.dp, Modifier.padding(top = CoulombSpacing.Space24).fillMaxWidth())
        SkeletonBlock(26.dp, Modifier.padding(top = currentSectionGap()).width(160.dp))
        SkeletonBlock(14.dp, Modifier.padding(top = CoulombSpacing.Space16).fillMaxWidth())
    }
}

/** SPEC 10.11 error: what happened and what to do. */
@Composable
private fun ErrorState(retry: () -> Unit) {
    Column(Modifier.widthIn(max = ReadingWidth).padding(top = CoulombSpacing.Space24)) {
        Text(
            stringResource(R.string.health_error_title),
            Modifier.fillMaxWidth().semantics { heading() },
            color = CoulombTheme.colors.textPrimary,
            style = CoulombTheme.typography.title,
        )
        Text(
            stringResource(R.string.health_error_body),
            Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8),
            color = CoulombTheme.colors.textSecondary,
            style = CoulombTheme.typography.body,
        )
        CoulombOutlinedButton(
            text = stringResource(R.string.health_error_action),
            onClick = retry,
            modifier = Modifier.padding(top = CoulombSpacing.Space16),
        )
    }
}

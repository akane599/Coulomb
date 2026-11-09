// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.akane599.coulomb.core.data.ProcessStates
import io.github.akane599.coulomb.core.designsystem.chart.DailyBarChart
import io.github.akane599.coulomb.core.designsystem.chart.DailyBarLabels
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigure
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigureStrip
import io.github.akane599.coulomb.core.designsystem.component.CoulombTerm
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import io.github.akane599.coulomb.core.ui.DataTable
import io.github.akane599.coulomb.core.ui.FigureText
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.affixed
import io.github.akane599.coulomb.core.ui.breakdown.label
import io.github.akane599.coulomb.core.ui.format.Formats
import java.time.Instant
import java.time.ZoneId
import io.github.akane599.coulomb.core.ui.R as UiR

/** The app's energy by process state (SPEC 10.4 item 4), with what S4 did not split. Modeled. */
@Composable
internal fun ProcessStateTable(
    states: ProcessStates,
    factor: Double,
    formats: Formats,
) {
    Subhead(stringResource(R.string.appdetail_states))
    val mah = { value: Double -> formats.mah(value * factor).joined() }
    val cached = stringResource(R.string.appdetail_state_cached)
    val rows =
        listOf(
            TableRow(stringResource(R.string.appdetail_state_foreground), listOf(mah(states.foregroundMah))),
            TableRow(stringResource(R.string.appdetail_state_background), listOf(mah(states.backgroundMah))),
            TableRow(stringResource(R.string.appdetail_state_foreground_service), listOf(mah(states.foregroundServiceMah))),
            TableRow(cached, listOf(mah(states.cachedMah)), term = stringResource(R.string.appdetail_state_cached_definition)),
        ) + listOfNotNull(states.notSplitMah?.let { TableRow(stringResource(R.string.appdetail_state_not_split), listOf(mah(it))) })
    InlineTable(
        columns = listOf(stringResource(R.string.appdetail_state), stringResource(R.string.appdetail_column_mah)),
        rows = rows,
        termAction = stringResource(R.string.appdetail_term_action),
    )
}

/**
 * Woke the phone (SPEC 10.4 item 5): the count is the app's wakeup alarms, which S5 keeps per app,
 * not per alarm, so there is no alarm table; then its wakelocks and its jobs, from their wakelocks,
 * with the job counters' totals. Measured. A source the window did not collect says so (ADR-0036).
 */
@Composable
internal fun Woke(
    view: AppDetailView,
    formats: Formats,
    besideFirst: Boolean,
) {
    val detail = view.detail
    val counters = detail.counters
    val coverage = detail.report.wakeCoverage.batteryStats
    Column(Modifier.fillMaxWidth()) {
        val alarms = counters.alarmWakeups
        val title =
            if (alarms != null) {
                pluralStringResource(R.plurals.appdetail_woke, alarms.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), formats.integer(alarms))
            } else {
                stringResource(R.string.appdetail_wakeups)
            }
        SectionTitle(title, Provenance.MEASURED.takeIf { alarms != null }, first = besideFirst)
        Text(
            stringResource(if (alarms != null) R.string.appdetail_alarms_note else R.string.appdetail_alarms_not_recorded),
            Modifier.fillMaxWidth(),
            color = CoulombTheme.colors.textSecondary,
            style = CoulombTheme.typography.caption,
        )
        if (coverage == Coverage.PARTIAL) Note(stringResource(R.string.appdetail_alarms_partial))
        Subhead(stringResource(R.string.appdetail_wakelocks))
        when {
            coverage == Coverage.NONE -> Note(stringResource(R.string.appdetail_wakelocks_not_recorded))
            view.wakelocks.isEmpty() -> Note(stringResource(R.string.appdetail_wakelocks_none))
            else -> WakelockTable(view, formats)
        }
        Subhead(stringResource(R.string.appdetail_jobs))
        when {
            coverage == Coverage.NONE -> Note(stringResource(R.string.appdetail_wakelocks_not_recorded))
            view.jobs.isEmpty() -> Note(stringResource(R.string.appdetail_jobs_none))
            else -> JobTable(view, formats)
        }
        val runs = counters.jobCount
        val runtime = counters.jobRuntimeMs
        if (runs != null && runtime != null && runs > 0) {
            Note(stringResource(R.string.appdetail_jobs_total, formats.integer(runs), formats.held(runtime)))
        }
    }
}

@Composable
private fun WakelockTable(
    view: AppDetailView,
    formats: Formats,
) {
    val rows =
        view.wakelocks.map {
            TableRow(
                it.tag,
                listOf(formats.held(it.totalMs), formats.integer(it.count)),
                mono = true,
                note = screenOffNote(it.screenOffShare, formats),
            )
        }
    InlineTable(
        columns =
            listOf(
                stringResource(R.string.appdetail_column_tag),
                stringResource(R.string.appdetail_column_time),
                stringResource(R.string.appdetail_column_count),
            ),
        rows = rows,
    )
}

@Composable
private fun JobTable(
    view: AppDetailView,
    formats: Formats,
) {
    val rows =
        view.jobs.map {
            TableRow(
                it.name,
                listOf(formats.integer(it.runs), formats.held(it.totalMs)),
                mono = true,
                note = screenOffNote(it.screenOffShare, formats),
            )
        }
    InlineTable(
        columns =
            listOf(
                stringResource(R.string.appdetail_column_job),
                stringResource(R.string.appdetail_column_runs),
                stringResource(R.string.appdetail_column_time),
            ),
        rows = rows,
    )
}

/** When a record was held: wholly with the screen off or on, or its share off (Derived; lab `.note`). */
@Composable
private fun screenOffNote(
    share: Double?,
    formats: Formats,
): String? =
    when {
        share == null -> null
        share >= WHOLE -> stringResource(R.string.appdetail_screen_off_all)
        share <= 1 - WHOLE -> stringResource(R.string.appdetail_screen_off_none)
        else -> stringResource(R.string.appdetail_screen_off_share, formats.share(share))
    }

/** A share this close to 0 or 1 rounds to "all" in a percentage. */
private const val WHOLE = 0.995

/**
 * Network and screen time (SPEC 10.4 item 6) as a figure strip: mobile data and Wi-Fi, received
 * plus sent, and time with an activity in the foreground. Measured; a counter with no row reads
 * "Not recorded", never zero (ADR-0041 §1).
 */
@Composable
internal fun Network(
    view: AppDetailView,
    formats: Formats,
) {
    val counters = view.detail.counters
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.appdetail_network), provenance = null)
        CoulombFigureStrip {
            Traffic(stringResource(R.string.appdetail_mobile), counters.mobileRxBytes, counters.mobileTxBytes, formats)
            Traffic(stringResource(R.string.appdetail_wifi), counters.wifiRxBytes, counters.wifiTxBytes, formats)
            val foreground = counters.foregroundActivityMs
            val service =
                counters.foregroundServiceMs
                    ?.takeIf {
                        it > 0
                    }?.let { stringResource(R.string.appdetail_foreground_service, formats.held(it)) }
            CoulombFigure(stringResource(R.string.appdetail_foreground), caption = service) {
                if (foreground != null) {
                    Text(formats.held(foreground), color = CoulombTheme.colors.textPrimary, style = CoulombTheme.typography.title)
                    ProvenanceMark(Provenance.MEASURED)
                } else {
                    NotRecorded()
                }
            }
        }
    }
}

@Composable
private fun Traffic(
    label: String,
    received: Long?,
    sent: Long?,
    formats: Formats,
) {
    val type = CoulombTheme.typography
    val caption =
        if (received != null && sent != null) {
            stringResource(R.string.appdetail_traffic, formats.bytes(received).joined(), formats.bytes(sent).joined())
        } else {
            null
        }
    CoulombFigure(label, caption) {
        if (received == null && sent == null) {
            NotRecorded()
        } else {
            FigureText(
                formats.bytes((received ?: 0) + (sent ?: 0)).affixed(),
                numberStyle = type.title,
                unitStyle = type.label,
            )
            ProvenanceMark(Provenance.MEASURED)
        }
    }
}

@Composable
private fun NotRecorded() {
    Text(stringResource(R.string.appdetail_not_recorded), color = CoulombTheme.colors.textSecondary, style = CoulombTheme.typography.body)
}

/**
 * The last 14 days (SPEC 10.4 item 7): one bar a day, today's so far, with the app's update as a
 * marker (ADR-0041 §4, §5). Modeled, unscaled: each window has its own measured total.
 */
@Composable
internal fun Trend(
    view: AppDetailView,
    formats: Formats,
    zone: ZoneId,
    onViewTable: () -> Unit,
) {
    val detail = view.detail
    val updated = remember(detail.updatedWallMs, zone) { detail.updatedWallMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } }
    val scale = remember(detail.trend, updated) { TrendScale.of(detail.trend, updated) }
    val days = scale.days
    val name = formats.appName(detail.label, detail.uid)
    val today = stringResource(R.string.appdetail_trend_today)
    val dayName = { index: Int -> if (index == days.lastIndex) today else formats.days(days[index].day, days[index].day) }
    val gap = stringResource(R.string.appdetail_trend_day_gap, "%s")
    val summary =
        stringResource(
            R.string.appdetail_trend_chart,
            name,
            days.indices.joinToString(", ") { index ->
                days[index].mah?.let { "${dayName(index)} ${formats.mah(it).joined()}" } ?: gap.format(dayName(index))
            },
        )
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.appdetail_trend), Provenance.MODELED)
        DailyBarChart(
            days = days.map { it.mah },
            top = scale.top,
            labels =
                DailyBarLabels(
                    unit = stringResource(R.string.appdetail_trend_axis),
                    start = dayName(0),
                    middle = dayName(days.size / 2),
                    end = today,
                    value = { formats.amount(it) },
                    marker = stringResource(R.string.appdetail_trend_updated).takeIf { scale.updated != null },
                ),
            summary = summary,
            viewTableLabel = stringResource(R.string.appdetail_view_table),
            onViewTable = onViewTable,
            markerAt = scale.updated?.let { day -> days.indexOfFirst { it.day == day } },
        )
        val notes =
            listOfNotNull(
                stringResource(R.string.appdetail_trend_caption),
                scale.updated?.let { stringResource(R.string.appdetail_trend_updated_on, formats.days(it, it)) },
                scale.gaps.takeIf { it > 0 }?.let {
                    pluralStringResource(
                        R.plurals.appdetail_trend_gaps,
                        it,
                        formats.integer(it.toLong()),
                    )
                },
            )
        Note(notes.joinToString(" "), Modifier.padding(top = CoulombSpacing.Space4))
        ViewTableButton(onViewTable)
    }
}

/** The figures a chart shows, as a table (SPEC 11.8). */
@Composable
internal fun tableOf(
    table: AppTable,
    view: AppDetailView,
    formats: Formats,
): DataTable {
    val detail = view.detail
    val name = formats.appName(detail.label, detail.uid)
    return when (table) {
        AppTable.COMPONENTS -> {
            val components = detail.components
            val rows =
                components?.parts.orEmpty().map { listOf(stringResource(it.component.label), formats.mah(it.mah * view.factor).joined()) } +
                    listOfNotNull(
                        components?.notBrokenDownMah?.let {
                            listOf(stringResource(UiR.string.component_not_broken_down), formats.mah(it * view.factor).joined())
                        },
                    )
            DataTable(
                title = stringResource(R.string.appdetail_components_title, name),
                columns = listOf(stringResource(R.string.appdetail_column_component), stringResource(R.string.appdetail_column_mah)),
                rows = rows,
                provenance = view.provenance,
            )
        }

        AppTable.TREND -> {
            val today = detail.trend.lastOrNull()?.day
            DataTable(
                title = stringResource(R.string.appdetail_trend_table, name),
                columns = listOf(stringResource(R.string.appdetail_column_day), stringResource(R.string.appdetail_column_mah)),
                rows =
                    detail.trend.map { day ->
                        val label = if (day.day == today) stringResource(R.string.appdetail_trend_today) else formats.days(day.day, day.day)
                        listOf(label, day.mah?.let { formats.mah(it).joined() } ?: stringResource(R.string.appdetail_no_records))
                    },
                provenance = Provenance.MODELED,
            )
        }
    }
}

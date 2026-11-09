// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.data.ChargeSessionDetail
import io.github.akane599.coulomb.core.data.ChargeSessionRow
import io.github.akane599.coulomb.core.designsystem.chart.ChargeCurveChart
import io.github.akane599.coulomb.core.designsystem.chart.CurveLabels
import io.github.akane599.coulomb.core.designsystem.chart.CurveReading
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigure
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigureStrip
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
import io.github.akane599.coulomb.core.ui.FigureText
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.affixed
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import io.github.akane599.coulomb.core.ui.page.StandIn
import io.github.akane599.coulomb.core.ui.page.ViewTableButton
import io.github.akane599.coulomb.core.ui.rowMotion
import kotlinx.coroutines.delay
import java.time.ZoneId
import io.github.akane599.coulomb.core.ui.R as UiR

/** The model of one session's screen in the current navigation entry. */
@Composable
public fun chargeSessionViewModel(sessionId: Long): ChargeSessionViewModel =
    hiltViewModel<ChargeSessionViewModel, ChargeSessionViewModel.Factory>(
        key = "charge-session-$sessionId",
        creationCallback = { it.create(sessionId) },
    )

/** Every closed charge session, newest first; a tap opens one ([selected] marks the one open beside the list). */
@Composable
public fun ChargeSessionsScreen(
    onOpenSession: (Long) -> Unit,
    modifier: Modifier = Modifier,
    selected: Long? = null,
    viewModel: ChargeSessionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChargeSessionsContent(state, viewModel::retry, onOpenSession, modifier, selected)
}

@Composable
@Suppress("LongParameterList") // The read, its controls and the test clock.
internal fun ChargeSessionsContent(
    state: Read<List<ChargeSessionRow>>,
    retry: () -> Unit,
    onOpenSession: (Long) -> Unit,
    modifier: Modifier = Modifier,
    selected: Long? = null,
    zone: ZoneId = remember(state) { ZoneId.systemDefault() },
    nowMs: Long = remember(state) { System.currentTimeMillis() },
) {
    val gutter = currentGutter()
    val formats = rememberFormats()
    when (state) {
        Read.Loading -> {
            Column(modifier.fillMaxSize().padding(horizontal = gutter)) { ListSkeleton() }
        }

        Read.Error -> {
            Column(modifier.fillMaxSize().padding(horizontal = gutter)) { SessionError(retry) }
        }

        is Read.Ready -> {
            if (state.value.isEmpty()) {
                Column(modifier.fillMaxSize().padding(horizontal = gutter)) {
                    StandIn(stringResource(R.string.health_no_sessions), top = CoulombSpacing.Space24)
                }
            } else {
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
                    itemsIndexed(state.value, key = { _, row -> row.id }, contentType = { _, _ -> "session" }) { index, row ->
                        Box(rowMotion()) {
                            SessionRow(row, index == 0, row.id == selected, nowMs, zone, formats) { onOpenSession(row.id) }
                        }
                    }
                }
            }
        }
    }
}

/** One charge session (SPEC 10.8 item 6): its figures, its curve, its heat and its charger. */
@Composable
public fun ChargeSessionScreen(
    viewModel: ChargeSessionViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChargeSessionContent(state, viewModel::retry, modifier)
}

@Composable
internal fun ChargeSessionContent(
    state: Read<ChargeSessionDetail?>,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = remember(state) { ZoneId.systemDefault() },
    nowMs: Long = remember(state) { System.currentTimeMillis() },
) {
    val gutter = currentGutter()
    when (state) {
        Read.Loading -> {
            // A session beside the list usually reads in well under this; only a slow read shows the skeleton.
            var late by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(SKELETON_DELAY_MS)
                late = true
            }
            if (late) Column(modifier.fillMaxSize().padding(horizontal = gutter)) { ListSkeleton() }
        }

        Read.Error -> {
            Column(modifier.fillMaxSize().padding(horizontal = gutter)) { SessionError(retry) }
        }

        is Read.Ready -> {
            val detail = state.value
            if (detail == null) {
                Column(modifier.fillMaxSize().padding(horizontal = gutter)) {
                    StandIn(stringResource(R.string.health_session_gone), top = CoulombSpacing.Space24)
                }
            } else {
                FadeInOnce(modifier.fillMaxSize()) { Session(detail, zone, nowMs) }
            }
        }
    }
}

/** A session's page stays a readable measure, centred, when it covers a wide window (SPEC 10.10). */
private fun Modifier.readingWidth(): Modifier = wrapContentWidth().widthIn(max = MaxPageWidth).fillMaxWidth()

private val MaxPageWidth = 840.dp
private const val SKELETON_DELAY_MS = 150L

@Composable
private fun Session(
    detail: ChargeSessionDetail,
    zone: ZoneId,
    nowMs: Long,
) {
    val formats = rememberFormats()
    val gutter = currentGutter()
    var table by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().readingWidth(),
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = CoulombSpacing.Space48),
    ) {
        item(key = "head", contentType = "head") { SessionHead(detail, zone, nowMs, formats) }
        item(key = "curve", contentType = "curve") { Curve(detail, zone, formats) { table = true } }
        item(key = "heat", contentType = "heat") { Heat(detail, formats) }
        detail.charger?.let { charger ->
            item(key = "charger", contentType = "charger") {
                Column(Modifier.fillMaxWidth()) {
                    SectionTitle(stringResource(R.string.health_charger), provenance = null)
                    DataRow(
                        name = chargerName(charger),
                        detail = stringResource(R.string.health_charger_line, lastSeen(charger.lastSeenWallMs, nowMs, zone, formats)),
                        value = formats.watts(charger.baselineW).joined(),
                        mark = Provenance.DERIVED,
                        share = null,
                        first = true,
                    )
                }
            }
        }
    }
    if (table) DataTableSheet(curveTable(detail, zone, formats), onDismiss = { table = false })
}

/** When it started, the session line, then power: peak, full speed and where it slowed. */
@Composable
private fun SessionHead(
    detail: ChargeSessionDetail,
    zone: ZoneId,
    nowMs: Long,
    formats: Formats,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val row = detail.row
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8)) {
        Text(
            startedAt(row.startWallMs, nowMs, zone, formats),
            Modifier.fillMaxWidth().semantics { heading() },
            color = colors.textPrimary,
            style = type.title,
        )
        // The session's one primary figure (SPEC 10.1): the level it gained.
        Row(
            Modifier.padding(top = CoulombSpacing.Space8).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formats.signedShare((row.toPct - row.fromPct).coerceAtLeast(0) / PERCENT),
                color = colors.textPrimary,
                style = type.display,
            )
            ProvenanceMark(Provenance.MODELED, Modifier.padding(start = CoulombSpacing.Space4))
        }
        Text(
            sessionLine(row, formats),
            Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space4),
            color = colors.textSecondary,
            style = type.body,
        )
        val peak = detail.peakW
        if (peak == null) {
            Note(stringResource(R.string.health_no_power), top = CoulombSpacing.Space16)
            return@Column
        }
        CoulombFigureStrip(Modifier.padding(top = CoulombSpacing.Space24)) {
            CoulombFigure(stringResource(R.string.health_peak), caption = null) { Watts(peak, formats) }
            detail.fullSpeedW?.let { full ->
                CoulombFigure(
                    stringResource(R.string.health_full_speed),
                    stringResource(R.string.health_full_speed_note),
                ) { Watts(full, formats) }
            }
            CoulombFigure(
                stringResource(R.string.health_slowed_from),
                stringResource(R.string.health_slowed_never).takeIf { detail.ccToCvLevel == null },
            ) {
                detail.ccToCvLevel?.let { level ->
                    FigureText(formats.percentParts(level), numberStyle = type.title, unitStyle = type.label)
                    ProvenanceMark(Provenance.DERIVED)
                } ?: Text(DASH, color = colors.textSecondary, style = type.title)
            }
        }
    }
}

private const val DASH = "–"
private const val PERCENT = 100.0

@Composable
private fun Watts(
    value: Double,
    formats: Formats,
) {
    val type = CoulombTheme.typography
    FigureText(formats.watts(value).affixed(), numberStyle = type.title, unitStyle = type.label)
    ProvenanceMark(Provenance.DERIVED)
}

/** The level and the power over the session, with "View as table". */
@Composable
private fun Curve(
    detail: ChargeSessionDetail,
    zone: ZoneId,
    formats: Formats,
    onViewTable: () -> Unit,
) {
    val row = detail.row
    Column(Modifier.fillMaxWidth()) {
        // Level alone is Modeled; with power beside it (Derived) the sources differ, so no heading mark (SPEC 10.1).
        val power = detail.curve.any { it.watts != null }
        SectionTitle(
            stringResource(if (power) R.string.health_curve else R.string.health_curve_level),
            Provenance.MODELED.takeIf { detail.curve.size >= 2 && !power },
        )
        if (detail.curve.size < 2) {
            StandIn(stringResource(R.string.health_no_curve), top = 0.dp)
            return@Column
        }
        val start = formats.clock(row.startWallMs, zone)
        // The end names its weekday when the charge ran past midnight.
        val end = formats.clock(row.endWallMs, zone, row.startWallMs)
        val from = formats.percent(row.fromPct)
        val to = formats.percent(row.toPct)
        val summary =
            detail.peakW?.let { stringResource(R.string.health_curve_summary_power, from, to, start, end, formats.watts(it).joined()) }
                ?: stringResource(R.string.health_curve_summary, from, to, start, end)
        val readings = remember(detail.curve) { detail.curve.map { CurveReading(it.wallMs, it.levelPct.toFloat(), it.watts?.toFloat()) } }
        val taperRule = stringResource(R.string.health_taper_rule)
        val fullSpeedRule = stringResource(R.string.health_full_speed_rule)
        val labels =
            remember(formats, zone, row.startWallMs, taperRule, fullSpeedRule) {
                CurveLabels(
                    percent = formats::percent,
                    // Axis steps are whole watts or halves of them: "20 W", "2.5 W".
                    watts = { w ->
                        val figure = formats.watts(w)
                        if (w % 1.0 == 0.0) figure.copy(number = formats.integer(w.toLong())).joined() else figure.joined()
                    },
                    time = { formats.clock(it, zone, row.startWallMs) },
                    taper = taperRule,
                    fullSpeed = fullSpeedRule,
                )
            }
        ChargeCurveChart(
            readings = readings,
            taperLevel = detail.ccToCvLevel,
            fullSpeedW = detail.fullSpeedW,
            labels = labels,
            summary = summary,
            viewTableLabel = stringResource(UiR.string.page_view_table),
            onViewTable = onViewTable,
        )
        ViewTableButton(onViewTable)
    }
}

/** Temperature, time at 80% or more and time above 38 °C, then the session's wear. */
@Composable
private fun Heat(
    detail: ChargeSessionDetail,
    formats: Formats,
) {
    val type = CoulombTheme.typography
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.health_heat), provenance = null)
        CoulombFigureStrip {
            val mean = detail.meanTempDeciC
            val highest = formats.celsius(detail.maxTempDeciC.toDouble())
            if (mean != null) {
                // An average of measured readings is Derived, as the habits' charge temperature is.
                CoulombFigure(
                    stringResource(R.string.health_temperature),
                    stringResource(R.string.health_temperature_note, highest.joined()),
                ) {
                    FigureText(formats.celsius(mean.toDouble()).affixed(), numberStyle = type.title, unitStyle = type.label)
                    ProvenanceMark(Provenance.DERIVED)
                }
            } else {
                CoulombFigure(stringResource(R.string.health_temperature_highest), caption = null) {
                    FigureText(highest.affixed(), numberStyle = type.title, unitStyle = type.label)
                    ProvenanceMark(Provenance.MEASURED)
                }
            }
            detail.minutesAtOrAbove80?.let { minutes ->
                CoulombFigure(stringResource(R.string.health_time_high, formats.percent(HIGH_LEVEL)), caption = null) {
                    Text(formats.duration(minutes.toLong()), color = CoulombTheme.colors.textPrimary, style = type.title)
                    ProvenanceMark(Provenance.DERIVED)
                }
            }
            detail.minutesAbove38C?.let { minutes ->
                CoulombFigure(stringResource(R.string.health_time_hot), caption = null) {
                    Text(formats.duration(minutes.toLong()), color = CoulombTheme.colors.textPrimary, style = type.title)
                    ProvenanceMark(Provenance.DERIVED)
                }
            }
            detail.row.stress?.let { stress ->
                CoulombFigure(stringResource(R.string.health_wear), caption = null) {
                    Text(stringResource(name(stress)), color = CoulombTheme.colors.textPrimary, style = type.title)
                    ProvenanceMark(Provenance.ESTIMATED)
                }
            }
        }
    }
}

/** The readings behind the curve, oldest first. */
@Composable
private fun curveTable(
    detail: ChargeSessionDetail,
    zone: ZoneId,
    formats: Formats,
): DataTable =
    DataTable(
        title = stringResource(R.string.health_table_curve),
        columns =
            listOf(
                stringResource(R.string.health_column_time),
                stringResource(R.string.health_column_level),
                stringResource(R.string.health_column_power),
            ),
        rows =
            detail.curve.map { point ->
                listOf(
                    formats.clock(point.wallMs, zone, detail.row.startWallMs),
                    formats.percent(point.levelPct),
                    point.watts?.let { formats.watts(it).joined() } ?: DASH,
                )
            },
        provenance = null,
    )

/** SPEC 10.11 loading for a session or the list. */
@Composable
private fun ListSkeleton() {
    val loading = stringResource(R.string.health_loading)
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8).semantics { contentDescription = loading }) {
        SkeletonBlock(26.dp, Modifier.width(200.dp))
        SkeletonBlock(16.dp, Modifier.padding(top = CoulombSpacing.Space8).width(280.dp))
        SkeletonBlock(96.dp, Modifier.padding(top = currentSectionGap()).fillMaxWidth())
    }
}

@Composable
private fun SessionError(retry: () -> Unit) {
    Column(Modifier.widthIn(max = 560.dp).padding(top = CoulombSpacing.Space24)) {
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

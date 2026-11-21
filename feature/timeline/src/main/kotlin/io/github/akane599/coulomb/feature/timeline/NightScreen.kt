// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.data.AlarmEntry
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.NightSummary
import io.github.akane599.coulomb.core.data.Timeline
import io.github.akane599.coulomb.core.data.TimelineTrack
import io.github.akane599.coulomb.core.data.TrackSpan
import io.github.akane599.coulomb.core.data.WakeEntry
import io.github.akane599.coulomb.core.designsystem.chart.TimelineChart
import io.github.akane599.coulomb.core.designsystem.chart.TimelineLabels
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigure
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigureStrip
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.ui.DataTable
import io.github.akane599.coulomb.core.ui.DataTableSheet
import io.github.akane599.coulomb.core.ui.FigureText
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.affixed
import io.github.akane599.coulomb.core.ui.format.DASH
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.insight.InsightCopy
import io.github.akane599.coulomb.core.ui.page.InlineTable
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import io.github.akane599.coulomb.core.ui.page.StandIn
import io.github.akane599.coulomb.core.ui.page.Subhead
import io.github.akane599.coulomb.core.ui.page.TableRow
import io.github.akane599.coulomb.core.ui.page.ViewTableButton
import io.github.akane599.coulomb.core.ui.story.StoryCopy
import kotlinx.coroutines.delay
import java.time.ZoneId
import io.github.akane599.coulomb.core.ui.R as UiR

/** The model of one night's screen in the current navigation entry. */
@Composable
public fun nightViewModel(sessionId: Long): NightViewModel =
    hiltViewModel<NightViewModel, NightViewModel.Factory>(
        key = "night-$sessionId",
        creationCallback = { it.create(sessionId) },
    )

/**
 * One night (SPEC 10.7): its rate, its story, a compact timeline of the session and what kept the
 * phone awake, ranked. [onOpenDrain] opens Drain, which then shows this night's window.
 */
@Composable
public fun NightScreen(
    viewModel: NightViewModel,
    onOpenDrain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NightContent(
        state,
        viewModel::retry,
        onOpenDrain = {
            viewModel.showInDrain()
            onOpenDrain()
        },
        modifier,
    )
}

@Composable
@Suppress("LongParameterList") // The read, its controls and the test clock.
internal fun NightContent(
    state: Read<NightDetail?>,
    retry: () -> Unit,
    onOpenDrain: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = remember(state) { ZoneId.systemDefault() },
    nowMs: Long = remember(state) { System.currentTimeMillis() },
) {
    val gutter = currentGutter()
    when (state) {
        Read.Loading -> {
            // A night beside the list usually reads in well under this; only a slow read shows the skeleton.
            var late by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(SKELETON_DELAY_MS)
                late = true
            }
            if (late) Column(modifier.fillMaxSize().padding(horizontal = gutter)) { PageSkeleton() }
        }

        Read.Error -> {
            Column(modifier.fillMaxSize().padding(horizontal = gutter)) { ReadError(retry) }
        }

        is Read.Ready -> {
            val detail = state.value
            if (detail == null) {
                Column(modifier.fillMaxSize().padding(horizontal = gutter)) {
                    StandIn(stringResource(R.string.night_gone), top = CoulombSpacing.Space24)
                }
            } else {
                FadeInOnce(modifier.fillMaxSize()) { Night(detail, zone, nowMs, onOpenDrain) }
            }
        }
    }
}

private const val SKELETON_DELAY_MS = 150L

/** A paragraph reads best under about 65 characters a line. */
private val StoryMeasure = 560.dp
private val MarkGap = 6.dp

/** The culprits' tables show this many rows before "Show all". */
private const val SHOWN = 5

/** The night's tracks: the phone's own states, then its radios. The screen is off throughout, so it has no band. */
private val NIGHT_TRACKS =
    listOf(
        TimelineTrack.CHARGING,
        TimelineTrack.DOZE,
        TimelineTrack.AWAKE,
        TimelineTrack.WAKELOCKS,
        TimelineTrack.MOBILE_RADIO,
        TimelineTrack.WIFI,
        TimelineTrack.GPS,
    )

@Composable
private fun Night(
    detail: NightDetail,
    zone: ZoneId,
    nowMs: Long,
    onOpenDrain: () -> Unit,
) {
    val formats = rememberFormats()
    val gutter = currentGutter()
    var table by rememberSaveable { mutableStateOf(false) }
    val timeline = detail.timeline?.takeUnless { it.empty }
    LazyColumn(
        Modifier.fillMaxSize().readingWidth(),
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = CoulombSpacing.Space48),
    ) {
        item(key = "head", contentType = "head") { NightHead(detail.night, zone, nowMs, formats) }
        item(key = "story", contentType = "story") { NightStory(detail, formats) }
        item(key = "figures", contentType = "figures") { NightFigures(detail.night, timeline, formats) }
        item(key = "timeline", contentType = "timeline") {
            Column(Modifier.fillMaxWidth()) {
                // Two sources in one chart: no one mark can head it (DESIGN.md); the line under it names both.
                SectionTitle(stringResource(R.string.night_timeline), provenance = null)
                if (timeline == null) {
                    StandIn(stringResource(R.string.night_no_history), top = 0.dp)
                } else {
                    NightTimeline(detail.night, timeline, zone, formats) { table = true }
                }
            }
        }
        item(key = "culprits", contentType = "culprits") { Culprits(detail.report, formats, onOpenDrain) }
    }
    if (table && timeline != null) DataTableSheet(tracksTable(timeline, NIGHT_TRACKS, formats), onDismiss = { table = false })
}

/** When the night began and its one primary figure (SPEC 10.1): its rate, or the level it lost where it has none. */
@Composable
private fun NightHead(
    night: NightSummary,
    zone: ZoneId,
    nowMs: Long,
    formats: Formats,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val rate = night.rate(formats)
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8)) {
        Text(
            startedAt(night.startWallMs, nowMs, zone, formats),
            Modifier.fillMaxWidth().semantics { heading() },
            color = colors.textPrimary,
            style = type.title,
        )
        val levels =
            stringResource(
                R.string.night_levels,
                formats.percent(night.startLevel),
                formats.percent(night.endLevel),
                formats.clock(night.endWallMs, zone),
            )
        if (night.charged) {
            // No drain rate on the charger: the stretch has no primary figure, and says why.
            Text(levels, Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8), color = colors.textSecondary, style = type.body)
            StandIn(stringResource(R.string.night_charged_note), top = CoulombSpacing.Space8)
            return@Column
        }
        Column(Modifier.padding(top = CoulombSpacing.Space8).semantics(mergeDescendants = true) {}) {
            Row {
                val figure = rate?.affixed() ?: formats.percentParts(night.startLevel - night.endLevel)
                FigureText(figure, type.display, type.displayUnit, Modifier.alignByBaseline())
                // The mark sits on the numeral's baseline, as on Now (lab `.hero-level .prov`).
                Box(Modifier.alignBy { it.measuredHeight }.padding(start = MarkGap)) {
                    ProvenanceMark(if (rate != null) Provenance.DERIVED else Provenance.MODELED)
                }
            }
            Text(
                stringResource(if (rate != null) R.string.night_rate_source else R.string.night_drop_source),
                color = colors.textSecondary,
                style = type.caption,
            )
        }
        Text(levels, Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8), color = colors.textSecondary, style = type.body)
    }
}

/**
 * The night's Drain Story (SPEC 9.3): how long, how much and how fast, then who, then how well
 * the phone slept. On the charger the level says nothing about the drain, so the story starts at
 * its causes; the note above it says why there is no rate.
 */
@Composable
private fun NightStory(
    detail: NightDetail,
    formats: Formats,
) {
    val resources = LocalResources.current
    val copy = remember(resources, formats) { StoryCopy(resources, formats, InsightCopy(resources, formats)) }
    val story = remember(detail) { nightStory(detail.night, detail.report, detail.insights) }
    val sentences = copy.sentences(story).drop(if (detail.night.charged) 1 else 0)
    if (sentences.isEmpty()) return
    Text(
        sentences.joinToString(" ") { it.text },
        Modifier.padding(top = CoulombSpacing.Space16).widthIn(max = StoryMeasure),
        color = CoulombTheme.colors.textPrimary,
        style = CoulombTheme.typography.body,
    )
}

/** Deep Doze and the time awake, each only where it is known. */
@Composable
private fun NightFigures(
    night: NightSummary,
    timeline: Timeline?,
    formats: Formats,
) {
    val type = CoulombTheme.typography
    val colors = CoulombTheme.colors
    val awakeMs =
        timeline?.tracks?.get(TimelineTrack.AWAKE)?.sumOf { it.durationMs }?.takeIf {
            timeline.unknown.sumOf(TrackSpan::durationMs) <
                MINUTE_MS
        }
    // Deep sleep is in the story above; the strip adds what the story does not say.
    if (night.charged || (night.deepDozeReached == null && awakeMs == null)) return
    CoulombFigureStrip(Modifier.padding(top = CoulombSpacing.Space24)) {
        night.deepDozeReached?.let { reached ->
            val after = night.deepDozeAfterMs
            CoulombFigure(
                stringResource(R.string.night_deep_doze),
                stringResource(if (reached) R.string.night_doze_after_start else R.string.night_doze_not_reached).takeIf {
                    !reached ||
                        after != null
                },
            ) {
                if (reached && after != null) {
                    Text(formats.minutes(after), color = colors.textPrimary, style = type.title)
                    ProvenanceMark(Provenance.MEASURED)
                } else {
                    Text(
                        if (reached) stringResource(R.string.night_doze_reached) else DASH,
                        color = colors.textSecondary,
                        style = type.title,
                    )
                }
            }
        }
        awakeMs?.let { ms ->
            CoulombFigure(stringResource(R.string.night_awake), caption = null) {
                Text(formats.held(ms), color = colors.textPrimary, style = type.title)
                ProvenanceMark(Provenance.MEASURED)
            }
        }
    }
}

private const val MINUTE_MS = 60_000L

/** The session as a compact timeline: the level, then the bands of [NIGHT_TRACKS] that hold anything. */
@Composable
private fun NightTimeline(
    night: NightSummary,
    timeline: Timeline,
    zone: ZoneId,
    formats: Formats,
    onViewTable: () -> Unit,
) {
    val resources = LocalResources.current
    // A band that stayed empty all night is left out; Doze and Awake always show, empty or not.
    val tracks =
        remember(timeline) {
            NIGHT_TRACKS.filter { it == TimelineTrack.DOZE || it == TimelineTrack.AWAKE || !timeline.tracks[it].isNullOrEmpty() }
        }
    val series = remember(timeline, tracks, resources) { timeline.series(tracks) { resources.getString(it.label) } }
    val ticks = remember(timeline, zone, formats) { timeTicks(timeline.fromWallMs, timeline.toWallMs, zone, formats) }
    val labels =
        TimelineLabels(
            level = stringResource(R.string.timeline_level),
            current = stringResource(R.string.timeline_current),
            percent = formats::percent,
            amps = { formats.signedCurrent(it).joined() },
        )
    val awake = timeline.tracks[TimelineTrack.AWAKE].orEmpty()
    val hours = night.durationMs / MS_PER_HOUR
    val summary =
        stringResource(
            R.string.night_timeline_summary,
            formats.percent(night.startLevel),
            formats.percent(night.endLevel),
            formats.integer(awake.size.toLong()),
            formats.held(awake.sumOf { it.durationMs }),
        )
    Column(Modifier.fillMaxWidth()) {
        // Without the current panel: Coulomb takes no samples while the phone sleeps (SPEC 7.4).
        TimelineChart(
            series = series.copy(current = emptyList()),
            fromMs = timeline.fromWallMs,
            toMs = timeline.toWallMs,
            ticks = ticks,
            labels = labels,
            summary = summary,
            viewTableLabel = stringResource(UiR.string.page_view_table),
            onViewTable = onViewTable,
        )
        if (awake.isNotEmpty() && hours > 0) {
            Note(stringResource(R.string.night_timeline_caption, formats.decimal(awake.size / hours)))
        }
        // The chart's two sources, in words: the level between readings is the last one reported.
        Note(
            stringResource(
                R.string.night_timeline_sources,
                stringResource(UiR.string.provenance_modeled),
                stringResource(UiR.string.provenance_measured),
            ),
        )
        ViewTableButton(onViewTable)
    }
}

private const val MS_PER_HOUR = 3_600_000.0

/** The tracks as a table: how long each was on and in how many stretches. */
@Composable
internal fun tracksTable(
    timeline: Timeline,
    tracks: List<TimelineTrack>,
    formats: Formats,
): DataTable {
    val rows =
        tracks.filter { it != TimelineTrack.SIGNAL && it != TimelineTrack.TEMPERATURE }.flatMap { track ->
            val spans = timeline.tracks[track].orEmpty()
            if (track == TimelineTrack.DOZE) {
                listOf(
                    stringResource(R.string.timeline_doze_light) to spans.filter { it.value == TrackSpan.DOZE_LIGHT },
                    stringResource(R.string.timeline_doze_deep) to spans.filter { it.value == TrackSpan.DOZE_DEEP },
                )
            } else {
                listOf(stringResource(track.label) to spans)
            }
        }
    return DataTable(
        title = stringResource(R.string.night_timeline),
        columns =
            listOf(
                stringResource(R.string.timeline_column_track),
                stringResource(R.string.timeline_column_time),
                stringResource(R.string.timeline_column_stretches),
            ),
        rows =
            rows.map { (name, spans) ->
                listOf(name, formats.held(spans.sumOf { it.durationMs }), formats.integer(spans.size.toLong()))
            },
        provenance = Provenance.MEASURED,
    )
}

/**
 * What kept the phone awake, ranked (SPEC 10.7): wakeup reasons, kernel wakelocks, partial
 * wakelocks and alarms, the first few of each. Drain shows them all, with what each record means.
 */
@Composable
private fun Culprits(
    report: DrainReport?,
    formats: Formats,
    onOpenDrain: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // The mark is for figures: a section that only says why it has none carries no mark.
        SectionTitle(stringResource(R.string.night_culprits), provenance = report?.let { Provenance.MEASURED })
        if (report == null) {
            StandIn(stringResource(R.string.night_no_records), top = 0.dp)
            return@Column
        }
        val wakes = report.wakes.groupBy { it.kind }
        val reasons = wakes[WakeKind.WAKEUP_REASON].orEmpty().ifEmpty { wakes[WakeKind.RESUME_CAUSE].orEmpty() }
        val kernel = wakes[WakeKind.KERNEL].orEmpty().ifEmpty { wakes[WakeKind.WAKEUP_SOURCE].orEmpty() }
        val count = stringResource(R.string.night_column_count)
        val heldFor = stringResource(R.string.night_column_held)
        WakeTable(
            R.string.night_wakeup_reasons,
            R.string.night_column_reason,
            listOf(count),
            reasons.sortedByDescending {
                it.count
            },
            first = true,
        ) {
            listOf(formats.integer(it.count))
        }
        WakeTable(
            R.string.night_kernel_wakelocks,
            R.string.night_column_wakelock,
            listOf(heldFor, count),
            kernel.sortedByDescending {
                it.totalMs
            },
        ) {
            listOf(formats.held(it.totalMs), formats.integer(it.count))
        }
        WakeTable(
            R.string.night_partial_wakelocks,
            R.string.night_column_wakelock,
            listOf(heldFor, count),
            wakes[WakeKind.PARTIAL].orEmpty().sortedByDescending { it.totalMs },
            note = { entry -> entry.uid?.let { formats.appName(entry.label, it) } },
        ) { listOf(formats.held(it.totalMs), formats.integer(it.count)) }
        Alarms(report.alarms, formats)
        CoulombTextButton(
            stringResource(R.string.night_open_drain),
            onOpenDrain,
            Modifier.padding(top = CoulombSpacing.Space8),
            edge = ButtonEdge.Start,
        )
    }
}

@Composable
@Suppress("LongParameterList") // A table's heading, columns, rows and how a row reads.
private fun WakeTable(
    title: Int,
    nameColumn: Int,
    valueColumns: List<String>,
    entries: List<WakeEntry>,
    first: Boolean = false,
    note: (WakeEntry) -> String? = { null },
    values: (WakeEntry) -> List<String>,
) {
    Subhead(stringResource(title), top = if (first) 0.dp else CoulombSpacing.Space24)
    if (entries.isEmpty()) {
        Note(stringResource(R.string.night_none), top = CoulombSpacing.Space4)
        return
    }
    InlineTable(
        columns = listOf(stringResource(nameColumn)) + valueColumns,
        rows = entries.map { TableRow(name = it.tag, values = values(it), mono = true, note = note(it)) },
        shown = SHOWN,
    )
}

@Composable
private fun Alarms(
    alarms: List<AlarmEntry>,
    formats: Formats,
) {
    Subhead(stringResource(R.string.night_alarms), top = CoulombSpacing.Space24)
    if (alarms.isEmpty()) {
        Note(stringResource(R.string.night_none), top = CoulombSpacing.Space4)
        return
    }
    val small = stringResource(R.string.night_small_apps)
    InlineTable(
        columns = listOf(stringResource(R.string.night_column_app), stringResource(R.string.night_column_wakeups)),
        rows =
            alarms.map { alarm ->
                TableRow(
                    name = if (alarm.smallApps) small else formats.appName(alarm.label, alarm.uid),
                    values = listOf(formats.integer(alarm.wakeups)),
                )
            },
        shown = SHOWN,
    )
}

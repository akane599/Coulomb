// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import androidx.annotation.StringRes
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
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
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
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

/** Deep Doze, Doze's pauses and the time awake, each only where it is known. */
@Composable
private fun NightFigures(
    night: NightSummary,
    timeline: Timeline?,
    formats: Formats,
) {
    val type = CoulombTheme.typography
    val colors = CoulombTheme.colors
    // Only from a history that covers the night: a gap in it is not a pause or time asleep.
    val whole = timeline != null && timeline.unknown.sumOf(TrackSpan::durationMs) < MINUTE_MS
    val awakeMs =
        timeline
            ?.tracks
            ?.get(TimelineTrack.AWAKE)
            ?.sumOf { it.durationMs }
            ?.takeIf { whole }
    val pauses = timeline?.let { DozePauses.of(it.tracks[TimelineTrack.DOZE].orEmpty()) }?.takeIf { whole }
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
        pauses?.let { DozePausesFigure(it, formats) }
        awakeMs?.let { ms ->
            CoulombFigure(stringResource(R.string.night_awake), caption = null) {
                Text(formats.held(ms), color = colors.textPrimary, style = type.title)
                ProvenanceMark(Provenance.MEASURED)
            }
        }
    }
}

private const val MINUTE_MS = 60_000L

/**
 * SPEC 2, "maintenance windows": the times Doze let go between entering it and the night's end.
 * Android reports Doze off to its statistics for each maintenance window, light or deep, and when
 * motion or another event ends Doze (DeviceIdleController, android-16.0.0_r4 L2002-2040,
 * L3863, L4052; android-17.0.0_r1 L2023-2061, L3926, L4115), so a gap between two Doze spans
 * is one of those; a change from light to deep leaves none.
 */
@Immutable
internal data class DozePauses(
    val count: Int,
    val totalMs: Long,
) {
    companion object {
        /** Null before Doze began twice: one span has no pause to count. */
        fun of(doze: List<TrackSpan>): DozePauses? {
            val gaps =
                doze
                    .sortedBy { it.fromWallMs }
                    .zipWithNext { a, b -> b.fromWallMs - a.toWallMs }
                    .filter { it > 0 }
            return if (doze.size < 2) null else DozePauses(gaps.size, gaps.sum())
        }
    }
}

@Composable
private fun DozePausesFigure(
    pauses: DozePauses,
    formats: Formats,
) {
    CoulombFigure(
        stringResource(R.string.night_doze_paused),
        stringResource(R.string.night_doze_paused_note, formats.held(pauses.totalMs)).takeIf { pauses.count > 0 },
    ) {
        Text(
            pluralStringResource(R.plurals.night_doze_paused_times, pauses.count, formats.integer(pauses.count.toLong())),
            color = CoulombTheme.colors.textPrimary,
            style = CoulombTheme.typography.title,
        )
        ProvenanceMark(Provenance.MEASURED)
    }
}

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
    // A stretch without history is not time asleep: over such a night both totals cover only the
    // known part and are lower bounds, as in the table (audit DATA-005).
    val summary =
        stringResource(
            if (timeline.partlyKnown) R.string.night_timeline_summary_partial else R.string.night_timeline_summary,
            formats.percent(night.startLevel),
            formats.percent(night.endLevel),
            formats.integer(timeline.wakeups.toLong()),
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
        // The Timeline's own sentence for the same quantity, and what an outlined span is on each track.
        wakeSentence(timeline, formats)?.let { Note(it + " " + stringResource(R.string.night_timeline_caption)) }
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

/**
 * The tracks as a table: how long each was on and in how many stretches. Where the history did not
 * cover the whole span, each figure is "at least" (audit DATA-016): a gap is not off.
 */
@Composable
internal fun tracksTable(
    timeline: Timeline,
    tracks: List<TimelineTrack>,
    formats: Formats,
): DataTable {
    val lowerBound = stringResource(R.string.timeline_at_least, "%s")
    val atLeast = { figure: String -> if (timeline.partlyKnown) lowerBound.format(figure) else figure }
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
                listOf(
                    name,
                    atLeast(formats.held(spans.sumOf { it.durationMs })),
                    atLeast(formats.integer(spans.size.toLong())),
                )
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
        // What S5 covered of this stretch (ADR-0028 §3): under NONE a missing record is not a zero, as Drain says of
        // the same report; under PARTIAL the counts are lower bounds.
        val covered = report?.wakeCoverage?.batteryStats
        val nothing = report == null || (report.wakes.isEmpty() && report.alarms.isEmpty())
        // The mark is for figures: a section that only says why it has none carries no mark.
        SectionTitle(stringResource(R.string.night_culprits), provenance = Provenance.MEASURED.takeUnless { nothing })
        if (report == null) {
            StandIn(stringResource(R.string.night_no_records), top = 0.dp)
            return@Column
        }
        if (nothing && covered == Coverage.NONE) {
            StandIn(stringResource(R.string.night_not_collected), top = 0.dp)
            OpenDrain(onOpenDrain)
            return@Column
        }
        if (covered == Coverage.PARTIAL) Note(stringResource(R.string.night_partial), top = 0.dp)
        val empty =
            when (covered) {
                Coverage.WHOLE -> R.string.night_none
                Coverage.PARTIAL -> R.string.night_none_partial
                else -> R.string.night_group_not_collected
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
            empty,
            first = covered != Coverage.PARTIAL,
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
            empty,
        ) {
            listOf(formats.held(it.totalMs), formats.integer(it.count))
        }
        WakeTable(
            R.string.night_partial_wakelocks,
            R.string.night_column_wakelock,
            listOf(heldFor, count),
            wakes[WakeKind.PARTIAL].orEmpty().sortedByDescending { it.totalMs },
            empty,
            note = { entry -> entry.uid?.let { formats.appName(entry.label, it) } },
        ) { listOf(formats.held(it.totalMs), formats.integer(it.count)) }
        Alarms(report.alarms, formats, empty)
        OpenDrain(onOpenDrain)
    }
}

@Composable
@Suppress("LongParameterList") // A table's heading, columns, rows and how a row reads.
private fun WakeTable(
    title: Int,
    nameColumn: Int,
    valueColumns: List<String>,
    entries: List<WakeEntry>,
    @StringRes empty: Int,
    first: Boolean = false,
    note: (WakeEntry) -> String? = { null },
    values: (WakeEntry) -> List<String>,
) {
    Subhead(stringResource(title), top = if (first) 0.dp else CoulombSpacing.Space24)
    if (entries.isEmpty()) {
        Note(stringResource(empty), top = CoulombSpacing.Space4)
        return
    }
    InlineTable(
        columns = listOf(stringResource(nameColumn)) + valueColumns,
        rows = entries.map { TableRow(name = it.tag, values = values(it), mono = true, note = note(it)) },
        shown = SHOWN,
    )
}

@Composable
private fun OpenDrain(onOpenDrain: () -> Unit) {
    CoulombTextButton(
        stringResource(R.string.night_open_drain),
        onOpenDrain,
        Modifier.padding(top = CoulombSpacing.Space8),
        trailingIcon = CoulombIcons.ChevronRight,
        edge = ButtonEdge.Start,
    )
}

@Composable
private fun Alarms(
    alarms: List<AlarmEntry>,
    formats: Formats,
    @StringRes empty: Int,
) {
    Subhead(stringResource(R.string.night_alarms), top = CoulombSpacing.Space24)
    if (alarms.isEmpty()) {
        Note(stringResource(empty), top = CoulombSpacing.Space4)
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

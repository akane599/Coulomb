// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.data.Timeline
import io.github.akane599.coulomb.core.data.TimelineTrack
import io.github.akane599.coulomb.core.data.TrackSpan
import io.github.akane599.coulomb.core.designsystem.chart.TimelineChart
import io.github.akane599.coulomb.core.designsystem.chart.TimelineGestures
import io.github.akane599.coulomb.core.designsystem.chart.TimelineLabels
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombIconButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.DataTableSheet
import io.github.akane599.coulomb.core.ui.MarkedText
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import io.github.akane599.coulomb.core.ui.page.StandIn
import io.github.akane599.coulomb.core.ui.page.ViewTableButton
import io.github.akane599.coulomb.core.ui.privilege.PrivilegeIssueBlock
import java.time.ZoneId
import kotlin.math.roundToLong
import io.github.akane599.coulomb.core.ui.R as UiR

/** Where the Timeline leads: the Night Report, one night, and an app's detail. */
@Immutable
public class TimelineLinks(
    public val onOpenNights: () -> Unit,
    public val onOpenNight: (Long) -> Unit,
    public val onOpenApp: (Int) -> Unit,
    public val onOpenPrivileges: () -> Unit = {},
)

/** What the Timeline's controls do; the view model's functions, or nothing in a test. */
@Immutable
internal class TimelineActions(
    val transform: (zoom: Float, focus: Float, pan: Float) -> Unit = { _, _, _ -> },
    val scrub: (Long) -> Unit = {},
    val tap: (TimelineTrack?, Long) -> Unit = { _, _ -> },
    val dismissPicked: () -> Unit = {},
    val setRange: (Long) -> Unit = {},
    val jumpToNow: () -> Unit = {},
    val setTrack: (TimelineTrack, Boolean) -> Unit = { _, _ -> },
    val retry: () -> Unit = {},
    val shift: (windows: Float) -> Unit = {},
)

/**
 * The Timeline (SPEC 10.6): the level, the current and one band per track over a window of 15
 * minutes to 7 days. Two fingers zoom and move it, one finger scrubs, a tap on a mark says what it
 * was. The readout above the chart names the moment under the cursor.
 */
@Composable
public fun TimelineScreen(
    links: TimelineLinks,
    modifier: Modifier = Modifier,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions =
        remember(viewModel) {
            TimelineActions(
                transform = viewModel::transform,
                scrub = viewModel::scrub,
                tap = viewModel::tap,
                dismissPicked = viewModel::dismissPicked,
                shift = viewModel::shift,
                setRange = viewModel::setRange,
                jumpToNow = viewModel::jumpToNow,
                setTrack = viewModel::setTrack,
                retry = viewModel::retry,
            )
        }
    TimelineContent(state, actions, links, modifier)
}

private val WideFrom = 840.dp
private val PanelWidth = 320.dp
private val ToolbarLift = 16.dp

/** The page ends this far under its last line, so the toolbar never covers it. */
private val ToolbarRoom = 96.dp

@Composable
internal fun TimelineContent(
    state: TimelineUiState,
    actions: TimelineActions,
    links: TimelineLinks,
    modifier: Modifier = Modifier,
    zone: ZoneId = remember { ZoneId.systemDefault() },
) {
    val formats = rememberFormats()
    val gutter = currentGutter()
    var tracks by rememberSaveable { mutableStateOf(false) }
    var table by rememberSaveable { mutableStateOf(false) }
    val timeline = state.timeline
    // What is on screen: the table, the caption and the summary speak of this, not of all that was read.
    val visible = remember(timeline, state.window) { timeline?.cut(state.window) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        // SPEC 10.10: on expanded widths a tapped mark's details sit beside the chart, not over it.
        val wide = maxWidth >= WideFrom
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = gutter, end = gutter, bottom = ToolbarRoom),
                ) {
                    when {
                        timeline == null && state.failed -> ReadError(actions.retry)
                        timeline == null || visible == null -> PageSkeleton()
                        else -> FadeInOnce { Page(state, timeline, visible, actions, links, zone, formats) { table = true } }
                    }
                }
                // Over the chart's column, not over the side panel.
                TimelineToolbar(
                    spanMs = state.window.spanMs,
                    onRange = actions.setRange,
                    onTracks = { tracks = true },
                    onNow = actions.jumpToNow,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = gutter, end = gutter, bottom = ToolbarLift),
                )
            }
            if (wide) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(CoulombTheme.colors.hairline))
                Column(
                    Modifier
                        .width(PanelWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(gutter),
                ) {
                    val picked = state.picked
                    if (picked == null) {
                        StandIn(stringResource(R.string.timeline_panel_hint), top = 0.dp)
                    } else {
                        EventDetails(picked, state.labels, zone, formats, state.nowMs, links.onOpenApp)
                    }
                }
            }
        }
        val picked = state.picked
        if (!wide && picked != null) {
            EventSheet(picked, state.labels, zone, formats, state.nowMs, links.onOpenApp, actions.dismissPicked)
        }
    }
    if (tracks) TracksSheet(state.tracks, actions.setTrack) { tracks = false }
    if (table && visible != null) DataTableSheet(tracksTable(visible, state.shown, formats), onDismiss = { table = false })
}

@Composable
@Suppress("LongParameterList") // The state, what was read, what is in view, and the page's controls.
private fun Page(
    state: TimelineUiState,
    timeline: Timeline,
    visible: Timeline,
    actions: TimelineActions,
    links: TimelineLinks,
    zone: ZoneId,
    formats: Formats,
    onViewTable: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // Without a cursor the readout is the latest moment in view that the history knows.
        val at = state.cursorMs ?: (minOf(state.window.toMs, visible.knownUntilWallMs ?: state.window.toMs) - 1)
        val moment = remember(timeline, at) { timeline.moment(at) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Readout(moment, state.nowMs, zone, formats, Modifier.weight(1f))
            // The window without two fingers: a mouse, a switch, or one hand (SPEC 10.10).
            // The same chevron turned round: it points towards earlier times in either reading direction.
            CoulombIconButton(
                CoulombIcons.ChevronRight,
                stringResource(R.string.timeline_earlier),
                { actions.shift(-STEP) },
                Modifier.graphicsLayer { scaleX = -1f },
            )
            CoulombIconButton(CoulombIcons.ChevronRight, stringResource(R.string.timeline_later), { actions.shift(STEP) })
        }
        // SPEC 10.11 partial: access lost, in SPEC 4.5's words, where the history it stops would be.
        state.issue?.let { PrivilegeIssueBlock(it, links.onOpenPrivileges, Modifier.padding(top = CoulombSpacing.Space16)) }
        Gap(state, visible, actions.retry, links.onOpenPrivileges)
        Chart(state, timeline, visible, actions, zone, formats, onViewTable)
        caption(state, visible, formats)?.let { Note(it) }
        // A table of nothing is not offered.
        if (visible.knownMs > 0L) ViewTableButton(onViewTable)
        state.latestNight?.let { night ->
            SectionTitle(
                stringResource(R.string.night_report_title),
                provenance = null,
                action = {
                    CoulombTextButton(
                        stringResource(R.string.timeline_see_all),
                        links.onOpenNights,
                        trailingIcon = CoulombIcons.ChevronRight,
                        edge = ButtonEdge.End,
                    )
                },
            )
            NightRow(night, first = true, selected = false, fastest = 0.0, nowMs = state.nowMs, zone = zone, formats = formats) {
                links.onOpenNight(night.id)
            }
        }
    }
}

/**
 * Why the chart is empty or behind: a read that failed, one that is running, history Coulomb
 * cannot read in this mode, or none kept for the range. Each is a different thing to say (SPEC 10.11).
 */
@Composable
private fun Gap(
    state: TimelineUiState,
    visible: Timeline,
    retry: () -> Unit,
    openPrivileges: () -> Unit,
) {
    val top = Modifier.padding(top = CoulombSpacing.Space16)
    when {
        state.failed -> {
            Column(top.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(
                    stringResource(R.string.timeline_read_failed),
                    color = CoulombTheme.colors.textPrimary,
                    style = CoulombTheme.typography.body,
                )
                CoulombTextButton(stringResource(R.string.timeline_error_action), retry, edge = ButtonEdge.Start)
            }
        }

        visible.knownMs > 0L -> {}

        state.pending -> {
            StandIn(stringResource(R.string.timeline_reading), top = CoulombSpacing.Space16)
        }

        // Basic mode by choice has no issue to show; the reason is still owed.
        state.locked && state.issue == null -> {
            Column(top.fillMaxWidth()) {
                Text(
                    stringResource(R.string.timeline_locked),
                    color = CoulombTheme.colors.textSecondary,
                    style = CoulombTheme.typography.body,
                )
                CoulombTextButton(
                    stringResource(UiR.string.privilege_open),
                    openPrivileges,
                    trailingIcon = CoulombIcons.ChevronRight,
                    edge = ButtonEdge.Start,
                )
            }
        }

        else -> {
            StandIn(stringResource(R.string.timeline_no_history), top = CoulombSpacing.Space16)
        }
    }
}

/** A step of "Earlier" and "Later": half the window, so what was at one edge stays in view. */
private const val STEP = 0.5f

/**
 * The moment under the cursor (design lab `.tl-read`): when and the level in Title, then the
 * phone's state and the current in Data. TalkBack hears it change while the cursor moves.
 */
@Composable
private fun Readout(
    moment: Moment,
    nowMs: Long,
    zone: ZoneId,
    formats: Formats,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val whenText = startedAt(moment.atMs, nowMs, zone, formats)
    Column(modifier.padding(top = CoulombSpacing.Space8).semantics { liveRegion = LiveRegionMode.Polite }) {
        val level = moment.levelPct
        if (level != null) {
            // The level between two readings is the last one reported: Modeled, as on Now.
            MarkedText(
                stringResource(R.string.timeline_readout, whenText, formats.percent(level)),
                Provenance.MODELED,
                type.title,
                colors.textPrimary,
            )
        } else {
            Text(whenText, color = colors.textPrimary, style = type.title)
        }
        val separator = stringResource(R.string.night_separator)
        val words =
            if (!moment.known) {
                listOf(stringResource(R.string.timeline_unknown_at))
            } else {
                listOfNotNull(
                    moment.screenOn?.let { stringResource(if (it) R.string.timeline_screen_on else R.string.timeline_screen_off) },
                    when (moment.doze) {
                        TrackSpan.DOZE_LIGHT -> stringResource(R.string.timeline_state_doze_light)
                        TrackSpan.DOZE_DEEP -> stringResource(R.string.timeline_state_doze_deep)
                        else -> null
                    },
                    stringResource(R.string.timeline_state_charging).takeIf { moment.charging == true },
                )
            }
        // Signed, as the axis is: plugged in and still discharging must not read as charging at that rate.
        val current = moment.currentMa?.let { formats.signedCurrent(it).joined() }
        val line = (words + listOfNotNull(current)).joinToString(separator)
        if (current != null) {
            MarkedText(line, Provenance.MEASURED, type.data, colors.textSecondary)
        } else {
            Text(line, color = colors.textSecondary, style = type.data)
        }
    }
}

@Composable
@Suppress("LongParameterList") // The state, what was read, what is in view, and the chart's controls.
private fun Chart(
    state: TimelineUiState,
    timeline: Timeline,
    visible: Timeline,
    actions: TimelineActions,
    zone: ZoneId,
    formats: Formats,
    onViewTable: () -> Unit,
) {
    val resources = LocalResources.current
    val haptics = LocalHapticFeedback.current
    val shown = state.shown
    val series = remember(timeline, shown, resources) { timeline.series(shown) { resources.getString(it.label) } }
    val window = state.window
    val ticks = remember(window, zone, formats) { timeTicks(window.fromMs, window.toMs, zone, formats) }
    val labels =
        TimelineLabels(
            level = stringResource(R.string.timeline_level),
            current = stringResource(R.string.timeline_current),
            percent = formats::percent,
            amps = { formats.signedCurrent(it).joined() },
        )
    // The last scrub position: a stretch that begins between it and the next is one tick (SPEC 10.6).
    val last = remember { longArrayOf(Long.MIN_VALUE) }
    val gestures =
        TimelineGestures(
            onTransform = actions.transform,
            onScrub = { at ->
                if (last[0] != Long.MIN_VALUE && timeline.crosses(last[0], at, shown)) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
                last[0] = at
                actions.scrub(at)
            },
            onTap = { band, at ->
                last[0] = at
                actions.tap(band?.let(shown::getOrNull), at)
            },
        )
    TimelineChart(
        series = series,
        fromMs = window.fromMs,
        toMs = window.toMs,
        ticks = ticks,
        labels = labels,
        summary = summary(state, visible, zone, formats),
        viewTableLabel = stringResource(UiR.string.page_view_table),
        onViewTable = onViewTable,
        modifier = Modifier.padding(top = CoulombSpacing.Space16),
        cursorMs = state.cursorMs,
        gestures = gestures,
        steps =
            listOf(
                stringResource(R.string.timeline_earlier) to { actions.shift(-STEP) },
                stringResource(R.string.timeline_later) to { actions.shift(STEP) },
            ),
    )
}

/** What TalkBack reads for the chart: the range, the level at its ends, and how often the phone woke. */
@Composable
private fun summary(
    state: TimelineUiState,
    visible: Timeline,
    zone: ZoneId,
    formats: Formats,
): String {
    val range =
        stringResource(
            R.string.timeline_summary_range,
            formats.clock(state.window.fromMs, zone, state.nowMs),
            formats.clock(state.window.toMs, zone, state.nowMs),
        )
    val first = visible.level.firstOrNull()
    val last = visible.level.lastOrNull()
    val level =
        if (first != null && last != null) {
            stringResource(R.string.timeline_summary_level, formats.percent(first.levelPct), formats.percent(last.levelPct))
        } else {
            stringResource(R.string.timeline_no_history)
        }
    return listOfNotNull(range, level, wakeSentence(visible, formats)).joinToString(" ")
}

/**
 * The caption under the chart: the wakeup rate, which a density span cannot show mark by mark
 * (ADR-0023), what an outlined span is, and the scales of the two value lines.
 */
@Composable
private fun caption(
    state: TimelineUiState,
    visible: Timeline,
    formats: Formats,
): String? {
    val shown = state.tracks
    val rate = wakeSentence(visible, formats)?.takeIf { TimelineTrack.AWAKE in shown }
    val low = visible.temperature.minOfOrNull { it.deciC }
    // One reading has no range to give.
    val high = visible.temperature.maxOfOrNull { it.deciC }?.takeIf { low != null && it > low }
    val parts =
        listOfNotNull(
            rate,
            stringResource(R.string.timeline_caption_density).takeIf { rate != null && state.window.spanMs > DENSE_FROM_MS },
            // An empty stretch of the current panel is "not sampled", not zero.
            stringResource(R.string.timeline_caption_current).takeIf { visible.knownMs > 0L },
            stringResource(R.string.timeline_caption_signal).takeIf {
                TimelineTrack.SIGNAL in shown && !visible.tracks[TimelineTrack.SIGNAL].isNullOrEmpty()
            },
            if (TimelineTrack.TEMPERATURE in shown && low != null && high != null) {
                stringResource(
                    R.string.timeline_caption_temperature,
                    formats.temperature(low.toDouble()).number,
                    formats.temperature(high.toDouble()).joined(),
                )
            } else {
                null
            },
        )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

/**
 * How often the phone woke, as exactly as the data is: a count under an hour of history, a whole
 * rate over more (two wakeups in a quarter of an hour are not "8.0 an hour").
 */
@Composable
internal fun wakeSentence(
    visible: Timeline,
    formats: Formats,
): String? {
    val rate = visible.wakeupsPerHour() ?: return null
    val wakeups = visible.tracks[TimelineTrack.AWAKE].orEmpty().size
    return if (visible.knownMs < MS_PER_HOUR) {
        pluralStringResource(R.plurals.timeline_caption_woke, wakeups, formats.integer(wakeups.toLong()))
    } else {
        stringResource(R.string.timeline_caption_wakeups, formats.integer(rate.roundToLong().coerceAtLeast(1)))
    }
}

private const val MS_PER_HOUR = 3_600_000L

/** Past this length, wakeups are usually too close to draw one by one (design lab: "Zoom to 6 h to see each"). */
private const val DENSE_FROM_MS = 6 * 60 * 60 * 1000L

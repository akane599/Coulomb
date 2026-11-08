// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.now

import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.AppDrain
import io.github.akane599.coulomb.core.data.BatteryNow
import io.github.akane599.coulomb.core.data.BatteryRepository
import io.github.akane599.coulomb.core.data.DeepSleepShare
import io.github.akane599.coulomb.core.data.DischargeSummary
import io.github.akane599.coulomb.core.data.StateRate
import io.github.akane599.coulomb.core.designsystem.chart.CurrentLabels
import io.github.akane599.coulomb.core.designsystem.chart.CurrentMark
import io.github.akane599.coulomb.core.designsystem.chart.EmptyRange
import io.github.akane599.coulomb.core.designsystem.chart.LevelMark
import io.github.akane599.coulomb.core.designsystem.chart.LiveCurrentChart
import io.github.akane599.coulomb.core.designsystem.chart.ProjectionScale
import io.github.akane599.coulomb.core.designsystem.chart.ScaleLabels
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigure
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigureStrip
import io.github.akane599.coulomb.core.designsystem.component.CoulombSectionHead
import io.github.akane599.coulomb.core.designsystem.component.CoulombTerm
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.component.animatePlacement
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentSectionGap
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.AppDrainRow
import io.github.akane599.coulomb.core.ui.FigureText
import io.github.akane599.coulomb.core.ui.IconStandIn
import io.github.akane599.coulomb.core.ui.MarkedText
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.affixed
import io.github.akane599.coulomb.core.ui.format.Affixed
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.Rounding
import io.github.akane599.coulomb.core.ui.privilege.PrivilegeIssueBlock
import java.time.ZoneId
import kotlin.math.roundToInt
import io.github.akane599.coulomb.core.ui.R as UiR

private const val MS_PER_HOUR = 3_600_000.0
private const val MS_PER_MINUTE = 60_000L
private const val LOW_QUALITY = 0.5
private const val PERCENT = 100
private const val LIVE_MINUTES = BatteryRepository.LIVE_WINDOW_MS / MS_PER_MINUTE
private val MarkWidth = 16.dp

// With the mark's own 4 dp, 6 dp from the numeral (lab `.hero-level .prov`).
private val LevelMarkGap = 2.dp

// About 70 characters of Body (SPEC 11.3 measure).
private val VerdictMeasure = 560.dp
private val IconSize = 40.dp

/** SPEC 10.2 hero: the level, time left and state, then the scale and the verdict. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun Hero(
    state: NowUiState.Ready,
    formats: Formats,
    zone: ZoneId,
    onViewTable: () -> Unit,
) {
    val discharge = state.discharge
    val type = CoulombTheme.typography
    val range = discharge?.timeLeft?.let { formats.timeLeft(it.shortestHours, it.longestHours) }?.let { formats.range(it.first, it.second) }
    val pending = stringResource(R.string.now_time_left_pending)
    val left = range?.let { timeLeftText(it) }
    val stateText = batteryStateText(state.battery, formats)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The side text sits beside the reading only while each of its lines fits unbroken; otherwise
    // it drops under it (DESIGN.md, 200% text and reflow).
    val sideMin =
        remember(range, pending, left, stateText, type, density) {
            with(density) {
                val mark = MarkWidth.roundToPx()
                val timeLine =
                    when {
                        discharge == null -> {
                            0
                        }

                        left == null -> {
                            measurer.measure(pending, type.body).size.width
                        }

                        else -> {
                            measurer.measure(left, type.title).size.width + mark
                        }
                    }
                maxOf(timeLine, measurer.measure(stateText, type.data).size.width + mark).toDp()
            }
        }
    Column(Modifier.padding(top = CoulombSpacing.Space16), verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16),
            verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8),
            itemVerticalAlignment = Alignment.Bottom,
        ) {
            Level(state.battery.levelPct, formats)
            Column(
                Modifier.widthIn(min = sideMin).weight(1f),
                verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space4),
            ) {
                if (discharge != null) TimeLeft(discharge, left, pending)
                BatteryState(stateText, measured = state.battery.currentMa != null)
            }
        }
        if (discharge != null) {
            // Until time left exists there is no projection to scale (ADR-0034).
            if (discharge.timeLeft != null) Scale(state, discharge, formats, zone, onViewTable)
            Verdict(state, discharge, formats)
        }
    }
}

@Composable
private fun Level(
    levelPct: Int,
    formats: Formats,
) {
    val type = CoulombTheme.typography
    val colors = CoulombTheme.colors
    // The fuel gauge computes the level, so it is Modeled and names its source (SPEC 6.7, ADR-0034).
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row {
            FigureText(
                formats.percentParts(levelPct),
                numberStyle = type.display,
                unitStyle = type.displayUnit,
                modifier = Modifier.alignByBaseline(),
                rolling = true,
            )
            // The mark sits on the numeral's baseline (lab `.hero-level .prov`).
            Box(Modifier.alignBy { it.measuredHeight }.padding(start = LevelMarkGap)) { ProvenanceMark(Provenance.MODELED) }
        }
        Text(stringResource(R.string.now_level_source), color = colors.textSecondary, style = type.caption)
    }
}

/**
 * "5 h 50 min – 7 h 20 min left" as one sentence, so each language orders it its own way: the range
 * in Title, the words around it in Body `text-secondary`.
 */
@Composable
private fun timeLeftText(range: String): AnnotatedString {
    val full = stringResource(R.string.now_time_left, range)
    val words =
        CoulombTheme.typography.body
            .toSpanStyle()
            .copy(color = CoulombTheme.colors.textSecondary)
    val at = full.indexOf(range)
    return buildAnnotatedString {
        append(full)
        if (at < 0) {
            addStyle(words, 0, full.length)
        } else {
            addStyle(words, 0, at)
            addStyle(words, at + range.length, full.length)
        }
    }
}

@Composable
private fun TimeLeft(
    discharge: DischargeSummary,
    left: AnnotatedString?,
    pending: String,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val estimate = discharge.timeLeft
    if (estimate == null || left == null) {
        Text(pending, color = colors.textSecondary, style = type.body)
        return
    }
    MarkedText(left, estimate.provenance, type.title, colors.textPrimary, Modifier.semantics(mergeDescendants = true) {})
}

/** "Discharging · 410 mA", or "Discharging · Calibrating" before the current is calibrated. */
@Composable
private fun batteryStateText(
    battery: BatteryNow,
    formats: Formats,
): String {
    val name =
        stringResource(
            when {
                !battery.plugged -> R.string.now_state_discharging

                // A charger too weak for the load: plugged in, and still draining.
                battery.status == BatteryManager.BATTERY_STATUS_DISCHARGING -> R.string.now_state_discharging_plugged

                battery.status == BatteryManager.BATTERY_STATUS_FULL -> R.string.now_state_full

                battery.status == BatteryManager.BATTERY_STATUS_CHARGING -> R.string.now_state_charging

                else -> R.string.now_state_not_charging
            },
        )
    // SPEC 6.1: until calibration completes, the current reads "Calibrating", not a number.
    val current =
        battery.currentMa ?: return stringResource(R.string.now_state_with_current, name, stringResource(R.string.now_calibrating))
    return stringResource(R.string.now_state_with_current, name, formats.current(current).joined())
}

@Composable
private fun BatteryState(
    text: String,
    measured: Boolean,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    // The mark never wraps away from its figure, nor gets squeezed out.
    if (measured) {
        MarkedText(text, Provenance.MEASURED, type.data, colors.textSecondary, Modifier.semantics(mergeDescendants = true) {})
    } else {
        Text(text, color = colors.textSecondary, style = type.data)
    }
}

@Composable
private fun Scale(
    state: NowUiState.Ready,
    discharge: DischargeSummary,
    formats: Formats,
    zone: ZoneId,
    onViewTable: () -> Unit,
) {
    val now = state.battery.wallMs
    val left = discharge.timeLeft
    val ends = left?.let { Rounding.timeLeftMinutes(it.shortestHours, it.longestHours) }
    val empty =
        ends?.let { (shortest, longest) ->
            val from = now + shortest * MS_PER_MINUTE
            val to = now + longest * MS_PER_MINUTE
            EmptyRange(from, to, stringResource(R.string.now_clock_range, formats.clock(from, zone, now), formats.clock(to, zone, now)))
        }
    val since = formats.hours((now - discharge.startWallMs) / MS_PER_HOUR)
    val summary =
        buildString {
            val from = formats.percent(discharge.startLevel)
            append(stringResource(R.string.now_scale_summary, from, formats.percent(state.battery.levelPct), since))
            if (left != null && empty != null) {
                val (shortest, longest) = formats.timeLeft(left.shortestHours, left.longestHours)
                append(' ')
                append(
                    stringResource(
                        R.string.now_scale_summary_empty,
                        formats.range(shortest, longest),
                        formats.clock(empty.fromMs, zone, now),
                        formats.clock(empty.toMs, zone, now),
                    ),
                )
            }
        }
    ProjectionScale(
        levels = remember(state.levels) { state.levels.map { LevelMark(it.wallMs, it.levelPct.toFloat(), it.startsSegment) } },
        startMs = discharge.startWallMs,
        nowMs = now,
        levelPct = state.battery.levelPct.toFloat(),
        empty = empty,
        labels = ScaleLabels(now = stringResource(R.string.now_now), percent = formats::percent, time = { formats.hourTick(it, zone) }),
        summary = summary,
        viewTableLabel = stringResource(R.string.now_view_table),
        onViewTable = onViewTable,
        zone = zone,
    )
}

/** The verdict sentence for "since unplugged" (SPEC 10.2). P5's Drain Story replaces it (SPEC 9.3). */
@Composable
private fun Verdict(
    state: NowUiState.Ready,
    discharge: DischargeSummary,
    formats: Formats,
) {
    val since = formats.hours((state.battery.wallMs - discharge.startWallMs) / MS_PER_HOUR)
    val fell = discharge.startLevel - state.battery.levelPct
    val top = state.drains?.let { drains -> drains.apps.firstOrNull()?.let { it to drains.share(it.mah) } }
    val text =
        when {
            fell <= 0 -> {
                stringResource(R.string.now_verdict_flat, since)
            }

            top == null -> {
                stringResource(R.string.now_verdict, since, formats.percent(fell))
            }

            else -> {
                val (app, share) = top
                val name = formats.appName(app.label, app.uid)
                stringResource(R.string.now_verdict_with_app, since, formats.percent(fell), name, formats.share(share))
            }
        }
    Text(text, Modifier.widthIn(max = VerdictMeasure), color = CoulombTheme.colors.textPrimary, style = CoulombTheme.typography.body)
}

/** SPEC 10.2 session strip: screen-on and screen-off rates and the deep sleep share. */
@Composable
internal fun SessionStrip(
    discharge: DischargeSummary,
    formats: Formats,
) {
    // The lab's visually hidden "This session" heading: TalkBack's heading navigation stops here.
    val stripTitle = stringResource(R.string.now_strip_heading)
    CoulombFigureStrip(
        Modifier.padding(top = currentSectionGap()).semantics {
            heading()
            contentDescription = stripTitle
        },
    ) {
        RateFigure(stringResource(R.string.now_screen_on), discharge.screenOn, R.string.now_first_after_on, formats)
        RateFigure(stringResource(R.string.now_screen_off), discharge.screenOff, R.string.now_first_after_off, formats)
        DeepSleepFigure(discharge.deepSleep, formats)
    }
}

@Composable
private fun DeepSleepFigure(
    sleep: DeepSleepShare?,
    formats: Formats,
) {
    if (sleep == null || !sleep.ready) {
        val needed = formats.hours(StateRate.MIN_HOURS)
        CoulombFigure(label = { DeepSleepTerm() }, caption = stringResource(R.string.now_first_after_off, needed)) { Collecting() }
        return
    }
    val span = stringResource(R.string.now_of_screen_off)
    val caption =
        if (sleep.quality < LOW_QUALITY) {
            stringResource(R.string.now_with_low_confidence, span, stringResource(UiR.string.low_confidence))
        } else {
            span
        }
    CoulombFigure(label = { DeepSleepTerm() }, caption = caption) {
        StripFigure(formats.percentParts((sleep.share * PERCENT).roundToInt()))
        ProvenanceMark(Provenance.MEASURED)
    }
}

/** "Deep sleep" with its glossary definition (SPEC 19.3; the lab's `.term`). */
@Composable
private fun DeepSleepTerm() {
    CoulombTerm(
        text = stringResource(R.string.now_deep_sleep),
        definition = stringResource(R.string.now_deep_sleep_definition),
        actionLabel = stringResource(R.string.now_term_action),
        style = CoulombTheme.typography.label,
        color = CoulombTheme.colors.textSecondary,
    )
}

@Composable
private fun RateFigure(
    label: String,
    rate: StateRate?,
    pendingCaption: Int,
    formats: Formats,
) {
    if (rate == null || !rate.ready) {
        val needed = formats.hours(StateRate.MIN_HOURS)
        CoulombFigure(label, stringResource(pendingCaption, needed)) { Collecting() }
        return
    }
    val span = stringResource(R.string.now_for, formats.hours(rate.hours))
    val caption =
        if (rate.quality < LOW_QUALITY) {
            stringResource(R.string.now_with_low_confidence, span, stringResource(UiR.string.low_confidence))
        } else {
            span
        }
    CoulombFigure(label, caption) {
        StripFigure(Affixed("", formats.rate(rate.pctPerHour), "\u00A0" + formats.rateUnit))
        ProvenanceMark(rate.provenance)
    }
}

/** A strip value: the number in Title, its unit in Label. */
@Composable
private fun StripFigure(figure: Affixed) {
    FigureText(figure, numberStyle = CoulombTheme.typography.title, unitStyle = CoulombTheme.typography.label)
}

@Composable
private fun Collecting() {
    Text(stringResource(R.string.now_collecting), color = CoulombTheme.colors.textSecondary, style = CoulombTheme.typography.data)
}

/** SPEC 10.2 top drains: three rows and "See all". */
@Composable
internal fun TopDrains(
    state: NowUiState.Ready,
    formats: Formats,
    zone: ZoneId,
    actions: NowActions,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val drains = state.drains
    Column(Modifier.padding(top = currentSectionGap())) {
        CoulombSectionHead(
            title = stringResource(R.string.now_top_drains),
            action =
                drains?.let {
                    {
                        CoulombTextButton(
                            text = stringResource(R.string.now_see_all),
                            onClick = actions.seeAllDrains,
                            trailingIcon = CoulombIcons.ChevronRight,
                            edge = ButtonEdge.End,
                        )
                    }
                },
        )
        // The issue first, then the captions right above the rows they explain.
        state.issue?.let { PrivilegeIssueBlock(it, actions.openPrivileges, Modifier.padding(top = CoulombSpacing.Space12)) }
        val captions =
            buildList {
                if (state.battery.plugged && drains != null) {
                    add(stringResource(R.string.now_last_discharge, formats.clock(drains.toWallMs, zone, state.battery.wallMs)))
                }
                if (drains != null && state.topDrains.isNotEmpty()) {
                    // DESIGN.md: the list caption names the denominator of every share.
                    val total = formats.mah(drains.total).joined()
                    add(
                        stringResource(
                            if (drains.sharesOfMeasured) R.string.now_shares_of_measured else R.string.now_shares_of_modeled,
                            total,
                        ),
                    )
                }
            }
        captions.forEachIndexed { index, caption ->
            val gap = if (index == 0 && state.issue != null) CoulombSpacing.Space12 else CoulombSpacing.Space4
            Text(caption, Modifier.padding(top = gap), color = colors.textSecondary, style = type.caption)
        }
        DrainList(state, formats, actions)
    }
}

/** The three rows and "Everything else", or why there are none yet. */
@Composable
private fun DrainList(
    state: NowUiState.Ready,
    formats: Formats,
    actions: NowActions,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val drains = state.drains
    Column(Modifier.padding(top = CoulombSpacing.Space12)) {
        val top = state.topDrains
        if (drains == null || top.isEmpty()) {
            // Unplugged, the first window is on its way; plugged in, none has been seen yet.
            val empty = if (state.battery.plugged) R.string.now_drains_empty else R.string.now_drains_collecting
            if (state.issue == null) Text(stringResource(empty), color = colors.textSecondary, style = type.body)
        } else {
            // Keyed by UID, so a reorder moves the rows instead of rewriting them (SPEC 11.6).
            top.forEachIndexed { index, app ->
                key(app.uid) { DrainRow(app, drains.share(app.mah), index == 0, formats, actions.icon, Modifier.animatePlacement()) }
            }
            val rest = state.remainderMah
            if (rest > 0) {
                // SPEC 11.8: the visible rows sum to the whole; Drain holds the breakdown.
                val restShare = drains.share(rest)
                AppDrainRow(
                    icon = null,
                    name = stringResource(R.string.now_everything_else),
                    detail = null,
                    value = formats.mah(rest),
                    provenance = Provenance.DERIVED,
                    share = restShare,
                    shareText = formats.share(restShare),
                    first = false,
                    onClick = actions.seeAllDrains,
                    modifier = Modifier.animatePlacement(),
                    standIn = IconStandIn.Remainder("+"),
                )
            }
        }
    }
}

@Composable
private fun DrainRow(
    app: AppDrain,
    share: Double,
    first: Boolean,
    formats: Formats,
    icon: suspend (String, Int) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val sizePx = with(LocalDensity.current) { IconSize.roundToPx() }
    val load by rememberUpdatedState(icon)
    val bitmap by produceState<ImageBitmap?>(null, app.iconPackage, sizePx) {
        value = app.iconPackage?.let { load(it, sizePx) }
    }
    AppDrainRow(
        icon = bitmap,
        name = formats.appName(app.label, app.uid),
        // A bare UID has no name to take an initial from.
        standIn = if (app.label == null && !app.isSystem) IconStandIn.Initial("#") else null,
        detail = null,
        value = formats.mah(app.mah),
        provenance = Provenance.MODELED,
        share = share,
        shareText = formats.share(share),
        first = first,
        onClick = null,
        modifier = modifier,
    )
}

/** SPEC 10.2 live current: the last 10 minutes while the screen is open. */
@Composable
internal fun LiveCurrent(
    state: NowUiState.Ready,
    formats: Formats,
    onViewTable: () -> Unit,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val current = state.battery.currentMa
    Column(Modifier.padding(top = currentSectionGap())) {
        CoulombSectionHead(
            title = stringResource(R.string.now_live_current),
            action =
                current?.let {
                    {
                        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                            FigureText(formats.current(it).affixed(), numberStyle = type.data, unitStyle = type.data, rolling = true)
                            ProvenanceMark(Provenance.MEASURED)
                        }
                    }
                },
        )
        val live = state.live
        if (live.isEmpty()) {
            Text(
                stringResource(if (current == null) R.string.now_live_uncalibrated else R.string.now_live_waiting),
                Modifier.padding(top = CoulombSpacing.Space12),
                color = colors.textSecondary,
                style = type.body,
            )
            return@Column
        }
        val low = live.minOf { it.currentMa }
        val high = live.maxOf { it.currentMa }
        // SPEC 6.7: the axis turns to A once the window reaches 1 A, so its ticks share one unit.
        val inAmps = formats.current(maxOf(-low, high)).unit == stringResource(UiR.string.unit_a)
        val zero = stringResource(if (inAmps) UiR.string.unit_a else UiR.string.unit_ma)
        LiveCurrentChart(
            marks = remember(live) { live.map { CurrentMark(it.wallMs, it.currentMa) } },
            nowMs = state.battery.wallMs,
            windowMs = BatteryRepository.LIVE_WINDOW_MS,
            labels =
                CurrentLabels(
                    start = stringResource(R.string.now_minutes_ago, formats.integer(LIVE_MINUTES)),
                    middle = stringResource(R.string.now_minutes_ago, formats.integer(LIVE_MINUTES / 2)),
                    end = stringResource(R.string.now_now),
                    value = { if (it == 0.0) "${formats.integer(0)}\u00A0$zero" else formats.signedAxis(it, inAmps) },
                ),
            summary =
                stringResource(
                    R.string.now_live_summary,
                    formats.signedCurrent(low).joined(),
                    formats.signedCurrent(high).joined(),
                    formats.signedCurrent(live.last().currentMa).joined(),
                ),
            viewTableLabel = stringResource(R.string.now_view_table),
            onViewTable = onViewTable,
            modifier = Modifier.padding(top = CoulombSpacing.Space12),
        )
        Text(
            stringResource(R.string.now_live_caption),
            Modifier.padding(top = CoulombSpacing.Space8),
            color = colors.textSecondary,
            style = type.caption,
        )
        CoulombTextButton(
            text = stringResource(R.string.now_view_table),
            onClick = onViewTable,
            modifier = Modifier.padding(top = CoulombSpacing.Space4),
            edge = ButtonEdge.Start,
        )
    }
}

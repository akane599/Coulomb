// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.charging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.analytics.ChargePhase
import io.github.akane599.coulomb.core.analytics.ChargeTime
import io.github.akane599.coulomb.core.data.ChargingNow
import io.github.akane599.coulomb.core.data.LiveCharger
import io.github.akane599.coulomb.core.designsystem.chart.ChargeCurveChart
import io.github.akane599.coulomb.core.designsystem.chart.ChargeLevelScale
import io.github.akane599.coulomb.core.designsystem.chart.CurveLabels
import io.github.akane599.coulomb.core.designsystem.chart.CurveReading
import io.github.akane599.coulomb.core.designsystem.component.CoulombChoiceGroup
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigure
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigureStrip
import io.github.akane599.coulomb.core.designsystem.component.CoulombTerm
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.FigureText
import io.github.akane599.coulomb.core.ui.MarkedText
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.affixed
import io.github.akane599.coulomb.core.ui.chargerName
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.Rounding
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import io.github.akane599.coulomb.core.ui.page.StandIn
import io.github.akane599.coulomb.core.ui.page.ViewTableButton
import io.github.akane599.coulomb.core.ui.unnamedCharger
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import io.github.akane599.coulomb.core.ui.R as UiR

/** ADR-0026: the target group's choices. */
internal val TARGETS = listOf(80, 85, 90, 100)

private val MarkGap = 2.dp
private const val MS_PER_MIN = 60_000.0
private const val MIN_PER_HOUR = 60.0
private const val STEADY_DECI_C = 1
private const val PERCENT = 100.0

/**
 * Power, the screen's one Display figure (DESIGN.md: copper numeral and unit on Charging), Derived
 * from the current and voltage; beside it the level and phase, and the charger.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Hero(
    now: ChargingNow,
    formats: Formats,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16),
            verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val watts = now.watts
            Row(Modifier.semantics(mergeDescendants = true) {}) {
                if (watts != null) {
                    FigureText(
                        formats.watts(watts).affixed(),
                        numberStyle = type.display,
                        unitStyle = type.displayUnit,
                        modifier = Modifier.alignByBaseline(),
                        numberColor = colors.accent,
                        unitColor = colors.accent,
                        rolling = true,
                    )
                    Box(Modifier.alignBy { it.measuredHeight }.padding(start = MarkGap)) { ProvenanceMark(Provenance.DERIVED) }
                } else {
                    Text(DASH, color = colors.textSecondary, style = type.display)
                }
            }
            Column {
                LevelAndPhase(now, formats)
                // Before calibration no charger can be told; the calibrating line says why.
                if (now.watts != null || now.charger is LiveCharger.Known) {
                    Text(chargerLine(now.charger, formats), color = colors.textSecondary, style = type.body)
                }
            }
        }
        if (now.watts == null) StandIn(stringResource(R.string.charging_power_calibrating))
    }
}

/** "52% ○ · full speed": the level, Modeled by the fuel gauge, then the phase as a glossary term. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelAndPhase(
    now: ChargingNow,
    formats: Formats,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            FigureText(formats.percentParts(now.levelPct), numberStyle = type.title, unitStyle = type.label)
            ProvenanceMark(Provenance.MODELED, Modifier.padding(start = CoulombSpacing.Space4))
        }
        val phase = now.phase ?: return@FlowRow
        Text(SEPARATOR, color = colors.textSecondary, style = type.body)
        CoulombTerm(
            text = stringResource(phaseWords(phase)),
            definition = stringResource(phaseDefinition(phase)),
            actionLabel = stringResource(UiR.string.page_term_action),
            style = type.body,
            color = colors.textSecondary,
        )
    }
}

private const val SEPARATOR = " · "

private fun phaseWords(phase: ChargePhase): Int =
    when (phase) {
        ChargePhase.FULL_SPEED -> R.string.charging_phase_full_speed
        ChargePhase.SLOWING -> R.string.charging_phase_slowing
        ChargePhase.HOLDING -> R.string.charging_phase_holding
        ChargePhase.FULL -> R.string.charging_phase_full
        ChargePhase.DRAWING -> R.string.charging_phase_drawing
    }

private fun phaseDefinition(phase: ChargePhase): Int =
    when (phase) {
        ChargePhase.FULL_SPEED -> R.string.charging_phase_full_speed_def
        ChargePhase.SLOWING -> R.string.charging_phase_slowing_def
        ChargePhase.HOLDING -> R.string.charging_phase_holding_def
        ChargePhase.FULL -> R.string.charging_phase_full_def
        ChargePhase.DRAWING -> R.string.charging_phase_drawing_def
    }

/** The charger as far as this charge can tell (ADR-0046 §5). */
@Composable
private fun chargerLine(
    charger: LiveCharger,
    formats: Formats,
): String =
    when (charger) {
        is LiveCharger.Known -> {
            stringResource(
                R.string.charging_charger_known,
                chargerName(charger.charger.name, charger.plugType),
                formats.percent((charger.shareOfBaseline * PERCENT).roundToInt()),
            )
        }

        is LiveCharger.New -> {
            unnamedCharger(charger.plugType) + SEPARATOR + stringResource(R.string.charging_charger_new)
        }

        is LiveCharger.Learning -> {
            stringResource(R.string.charging_charger_learning)
        }

        is LiveCharger.StartedHigh -> {
            stringResource(R.string.charging_charger_high)
        }
    }

/** The still copper wave to the level, the target tick and when the target is reached. */
@Composable
internal fun LevelScale(
    now: ChargingNow,
    formats: Formats,
    zone: ZoneId,
) {
    val type = CoulombTheme.typography
    val colors = CoulombTheme.colors
    val target = formats.percent(now.targetPct)
    val level = formats.percent(now.levelPct)
    val at = now.toTarget?.let { reachedAt(it, now.wallMs, formats, zone) }
    val description =
        if (at != null) {
            stringResource(R.string.charging_scale_description_time, level, target, at)
        } else {
            stringResource(R.string.charging_scale_description, level, target)
        }
    ChargeLevelScale(
        levelPct = now.levelPct,
        targetPct = now.targetPct,
        description = description,
        modifier = Modifier.padding(top = CoulombSpacing.Space24),
        targetLabel = {
            if (at != null) {
                MarkedText(
                    stringResource(R.string.charging_target_at, target, at),
                    Provenance.ESTIMATED,
                    type.captionEmphasis,
                    colors.textPrimary,
                )
            } else {
                Text(stringResource(R.string.charging_target_plain, target), color = colors.textSecondary, style = type.captionEmphasis)
            }
        },
    )
}

/** "19:06 – 19:10": the clock times of a range from [nowMs], rounded outward as time left is. */
private fun reachedAt(
    time: ChargeTime,
    nowMs: Long,
    formats: Formats,
    zone: ZoneId,
): String {
    val (shortest, longest) = minutes(time)
    return formats.range(
        formats.clock(nowMs + (shortest * MS_PER_MIN).toLong(), zone, nowMs),
        formats.clock(nowMs + (longest * MS_PER_MIN).toLong(), zone, nowMs),
    )
}

/** The ends in whole minutes, rounded outward (SPEC 6.8, `Rounding.timeLeftMinutes`). */
private fun minutes(time: ChargeTime): Pair<Long, Long> =
    Rounding.timeLeftMinutes(time.shortestMin / MIN_PER_HOUR, time.longestMin / MIN_PER_HOUR)

/** Time to the target and to full as ranges, each Estimated, or why there are none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Times(
    now: ChargingNow,
    formats: Formats,
) {
    val type = CoulombTheme.typography
    val colors = CoulombTheme.colors
    if (now.phase == ChargePhase.FULL || now.phase == ChargePhase.DRAWING) return
    if (now.phase == ChargePhase.HOLDING) {
        StandIn(stringResource(R.string.charging_times_holding), top = CoulombSpacing.Space16)
        return
    }
    val wantsTarget = now.levelPct < now.targetPct
    // With a 100% target, time to full is the time to target: one line.
    val wantsFull = now.levelPct < FULL && now.targetPct < FULL
    val target = now.toTarget?.takeIf { wantsTarget }
    val full = now.toFull?.takeIf { wantsFull }
    val lines =
        listOfNotNull(
            target?.let { stringResource(R.string.charging_to_level, formats.percent(now.targetPct), span(it, formats)) },
            full?.let { stringResource(R.string.charging_full_in, span(it, formats)) },
        )
    val missing = (wantsTarget && target == null) || (wantsFull && full == null)
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16)) {
        if (lines.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space24)) {
                lines.forEach { MarkedText(it, Provenance.ESTIMATED, type.dataLead, colors.textPrimary) }
            }
        }
        if (missing) {
            if (lines.isEmpty()) {
                StandIn(stringResource(R.string.charging_times_need), top = 0.dp)
            } else {
                Note(stringResource(R.string.charging_times_need))
            }
        }
    }
}

private const val FULL = 100

private fun span(
    time: ChargeTime,
    formats: Formats,
): String {
    val (shortest, longest) = minutes(time)
    return formats.range(formats.duration(shortest), formats.duration(longest))
}

/** Current, voltage and temperature, each Measured (SPEC 10.9's secondary figures). */
@Composable
internal fun Readings(
    now: ChargingNow,
    formats: Formats,
) {
    val type = CoulombTheme.typography
    val colors = CoulombTheme.colors
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.charging_readings), provenance = Provenance.MEASURED)
        CoulombFigureStrip {
            val current = now.currentMa
            CoulombFigure(
                stringResource(R.string.charging_current),
                current?.let { stringResource(if (it >= 0) R.string.charging_current_in else R.string.charging_current_out) },
            ) {
                if (current != null) {
                    FigureText(formats.current(current).affixed(), numberStyle = type.title, unitStyle = type.label)
                } else {
                    Text(DASH, color = colors.textSecondary, style = type.title)
                }
            }
            CoulombFigure(stringResource(R.string.charging_voltage), stringResource(R.string.charging_voltage_at)) {
                FigureText(formats.volts(now.voltageMv).affixed(), numberStyle = type.title, unitStyle = type.label)
            }
            CoulombFigure(stringResource(R.string.charging_temperature), riseWords(now.tempRiseDeciC, formats)) {
                FigureText(formats.celsius(now.tempDeciC.toDouble()).affixed(), numberStyle = type.title, unitStyle = type.label)
            }
        }
    }
}

/** "up 4.1 °C" since the charge began, "down 0.3 °C", or "steady" within 0.1 °C. */
@Composable
private fun riseWords(
    riseDeciC: Int?,
    formats: Formats,
): String? {
    val rise = riseDeciC ?: return null
    val size = formats.celsius(abs(rise).toDouble()).joined()
    return when {
        rise > STEADY_DECI_C -> stringResource(R.string.charging_temp_up, size)
        rise < -STEADY_DECI_C -> stringResource(R.string.charging_temp_down, size)
        else -> stringResource(R.string.charging_temp_steady)
    }
}

/** The session so far: level and power, updating with each sample, with "View as table". */
@Composable
internal fun Session(
    now: ChargingNow,
    formats: Formats,
    zone: ZoneId,
    onViewTable: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(
            stringResource(R.string.charging_session),
            provenance = null,
            caption = stringResource(R.string.charging_since, formats.clock(now.startWallMs, zone, now.wallMs)),
        )
        if (now.curve.size < 2) {
            StandIn(stringResource(R.string.charging_no_curve), top = 0.dp)
            return@Column
        }
        val from = formats.percent(now.startLevelPct)
        val to = formats.percent(now.levelPct)
        val start = formats.clock(now.startWallMs, zone)
        val summary =
            now.watts?.let { stringResource(R.string.charging_curve_summary_power, from, to, start, formats.watts(it).joined()) }
                ?: stringResource(R.string.charging_curve_summary, from, to, start)
        val readings = remember(now.curve) { now.curve.map { CurveReading(it.wallMs, it.levelPct.toFloat(), it.watts?.toFloat()) } }
        val labels =
            remember(formats, zone, now.startWallMs) {
                CurveLabels(
                    percent = formats::percent,
                    // Axis steps are whole watts or halves of them: "20 W", "2.5 W".
                    watts = { w ->
                        val figure = formats.watts(w)
                        if (w % 1.0 == 0.0) figure.copy(number = formats.integer(w.toLong())).joined() else figure.joined()
                    },
                    time = { formats.clock(it, zone, now.startWallMs) },
                    taper = null,
                    fullSpeed = null,
                )
            }
        ChargeCurveChart(
            readings = readings,
            taperLevel = null,
            fullSpeedW = null,
            labels = labels,
            summary = summary,
            viewTableLabel = stringResource(UiR.string.page_view_table),
            onViewTable = onViewTable,
        )
        ViewTableButton(onViewTable)
    }
}

/** The 80/85/90/100% group (ADR-0026), which sets the tick and the time to target (ADR-0046 §4). */
@Composable
internal fun Target(
    targetPct: Int,
    formats: Formats,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.charging_target), provenance = null)
        CoulombChoiceGroup(
            options = TARGETS.map(formats::percent),
            selected = TARGETS.indexOf(targetPct),
            onSelect = { onSelect(TARGETS[it]) },
        )
        Note(stringResource(R.string.charging_target_note))
    }
}

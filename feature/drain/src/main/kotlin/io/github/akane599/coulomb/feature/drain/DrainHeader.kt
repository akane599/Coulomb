// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.data.LevelEstimate
import io.github.akane599.coulomb.core.data.WindowDrains
import io.github.akane599.coulomb.core.designsystem.component.CoulombWindowSelector
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.FigureText
import io.github.akane599.coulomb.core.ui.MarkedText
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.affixed
import io.github.akane599.coulomb.core.ui.format.Affixed
import io.github.akane599.coulomb.core.ui.format.Formats
import java.time.LocalDate
import java.time.ZoneId

/** The window selector (DESIGN.md): three presets and More, which names a sheet window while one is chosen. */
@Composable
internal fun WindowChoice(
    chosen: DrainSpan,
    formats: Formats,
    onChoose: (DrainSpan) -> Unit,
    onMore: () -> Unit,
) {
    val presets =
        listOf(
            stringResource(R.string.drain_since_unplugged),
            stringResource(R.string.drain_today),
            stringResource(R.string.drain_last_24h),
        )
    val selected = DrainSpan.PRESETS.indexOf(chosen)
    val more =
        when (chosen) {
            DrainSpan.LastNight -> stringResource(R.string.drain_last_night)
            is DrainSpan.Days -> formats.days(chosen.first, chosen.last)
            else -> stringResource(R.string.drain_more)
        }
    CoulombWindowSelector(
        presets = presets,
        selected = selected,
        onSelect = { onChoose(DrainSpan.PRESETS[it]) },
        more = more,
        moreChosen = selected < 0,
        onMore = onMore,
        modifier = Modifier.padding(top = CoulombSpacing.Space8),
    )
}

/**
 * The Caption note under the selector (DESIGN.md): how long the window ran and the levels at its
 * ends. Last night also says when it ran, since the selector cannot.
 */
@Composable
internal fun WindowNote(
    report: DrainReport,
    formats: Formats,
    zone: ZoneId,
    today: LocalDate,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val drains = report.drains
    val duration = formats.duration((report.durationMs + MS_PER_MINUTE / 2) / MS_PER_MINUTE)
    val start = report.startLevel
    val end = report.endLevel
    val levels =
        if (start != null && end != null) {
            stringResource(R.string.drain_note_levels, duration, formats.percent(start), formats.percent(end))
        } else {
            duration
        }
    val note =
        if (report.span == DrainSpan.LastNight) {
            val clock = formats.range(formats.clock(drains.fromWallMs, zone, drains.toWallMs), formats.clock(drains.toWallMs, zone))
            stringResource(R.string.drain_detail_separator, clock, levels)
        } else {
            // The window ends at the latest heavy snapshot, which may be a while ago: say when.
            val end = formats.clock(drains.toWallMs, zone, today.atStartOfDay(zone).toInstant().toEpochMilli())
            stringResource(R.string.drain_detail_separator, levels, stringResource(R.string.drain_as_of, end))
        }
    Column(Modifier.padding(top = CoulombSpacing.Space8)) {
        Text(note, Modifier.fillMaxWidth(), color = colors.textSecondary, style = type.caption)
        if (report.chargedInSpan) {
            Text(stringResource(R.string.drain_note_charged), Modifier.fillMaxWidth(), color = colors.textSecondary, style = type.caption)
        }
    }
}

/**
 * The window's total (lab `.total`): the measured drain in Display with its mark, a line on how it
 * was measured, and the reconcile bar. An Estimated drain is a range, set smaller, with the bracket
 * (ADR-0036 §17). Without a measured change the modeled total stands in, and says so.
 */
@Composable
internal fun Total(
    report: DrainReport,
    formats: Formats,
    onViewTable: () -> Unit,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val drains = report.drains
    val measured = drains.measuredMah?.takeIf { it > 0 }
    val provenance = if (measured != null) drains.measuredProvenance ?: Provenance.MODELED else Provenance.MODELED
    val estimate = drains.levelEstimate
    Column(Modifier.padding(top = CoulombSpacing.Space24, bottom = CoulombSpacing.Space8)) {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Row {
                val low = drains.measuredLowMah?.takeIf { measured != null && estimate != null }
                val high = drains.measuredHighMah
                if (low != null && high != null) {
                    val unit = formats.mah(high)
                    FigureText(
                        Affixed("", formats.range(formats.mah(low).number, unit.number), " " + unit.unit),
                        numberStyle = type.headline,
                        unitStyle = type.body,
                        modifier = Modifier.alignByBaseline(),
                    )
                } else {
                    FigureText(
                        formats.mah(measured ?: drains.modeledMah).affixed(),
                        numberStyle = type.display,
                        unitStyle = type.displayUnit,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                // The mark sits on the numeral's baseline, as the level's on Now.
                // The caption under the figure names its provenance in words, so TalkBack reads it once.
                Box(Modifier.alignBy { it.measuredHeight }.padding(start = MarkGap).clearAndSetSemantics {}) { ProvenanceMark(provenance) }
            }
            Text(totalCaption(measured, provenance, estimate), Modifier.fillMaxWidth(), color = colors.textSecondary, style = type.body)
        }
        if (measured != null && drains.modeledMah > 0) Reconcile(drains, measured, formats, onViewTable)
    }
}

/** How the total was measured: by the counter, from the level (in how many stretches), or not at all. */
@Composable
private fun totalCaption(
    measured: Double?,
    provenance: Provenance,
    estimate: LevelEstimate?,
): String =
    when {
        measured == null -> stringResource(R.string.drain_modeled_only)
        provenance == Provenance.MEASURED -> stringResource(R.string.drain_measured_counter)
        estimate == null -> stringResource(R.string.drain_estimated_level)
        estimate.withCounter -> pluralStringResource(R.plurals.drain_estimated_mixed, estimate.stretches, estimate.stretches)
        else -> pluralStringResource(R.plurals.drain_estimated_levels, estimate.stretches, estimate.stretches)
    }

/**
 * SPEC 6.5 reconciliation: the modeled part of the measured total as a solid segment and the
 * unattributed rest hatched (lab `.reconcile`), with a key that carries each figure's mark. When
 * the model counts more than the battery measured there is no gap to draw, and a sentence says so;
 * an Estimated total has a range of gaps, and a sentence gives its ends.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Reconcile(
    drains: WindowDrains,
    measured: Double,
    formats: Formats,
    onViewTable: () -> Unit,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val modeled = drains.modeledMah
    val low = drains.measuredLowMah
    val high = drains.measuredHighMah
    val sentence =
        when {
            // A range has no single gap to draw: a sentence gives its ends (ADR-0036 §17).
            drains.estimated && drains.levelEstimate != null && low != null && high != null && high > modeled -> {
                val unit = formats.mah(high - modeled)
                val gap =
                    if (low > modeled) {
                        formats.range(formats.mah(low - modeled).number, unit.number) + "\u00A0" + unit.unit
                    } else {
                        stringResource(R.string.drain_up_to, unit.joined())
                    }
                stringResource(R.string.drain_reconcile_estimated, formats.mah(modeled).joined(), gap)
            }

            modeled > (if (drains.estimated) high ?: measured else measured) -> {
                stringResource(R.string.drain_model_over, formats.mah(modeled).joined())
            }

            // An estimate with no known error has no gap to show either (ADR-0036 §17, §30).
            drains.estimated -> {
                stringResource(R.string.drain_reconcile_unknown, formats.mah(modeled).joined())
            }

            else -> {
                null
            }
        }
    if (sentence != null) {
        Text(sentence, Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16), color = colors.textSecondary, style = type.caption)
        return
    }
    val gap = drains.unattributedMah
    val description =
        stringResource(
            R.string.drain_reconcile_description,
            formats.mah(measured).joined(),
            formats.mah(modeled).joined(),
            formats.mah(gap).joined(),
            formats.share(gap / measured),
        )
    Column(Modifier.padding(top = CoulombSpacing.Space16)) {
        ReconcileBar(
            modeledFraction = (modeled / measured).toFloat(),
            solid = colors.textSecondary,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(
                        BarBox,
                    ).chartSemantics(description, stringResource(R.string.drain_view_table), onViewTable),
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space4),
        ) {
            MarkedText(
                stringResource(R.string.drain_modeled, formats.mah(modeled).joined()),
                Provenance.MODELED,
                type.caption,
                colors.textSecondary,
                Modifier.padding(end = CoulombSpacing.Space16),
            )
            MarkedText(
                stringResource(R.string.drain_unattributed, formats.mah(gap).joined()),
                Provenance.DERIVED,
                type.captionEmphasis,
                colors.textPrimary,
            )
        }
    }
}

/** A 14 dp bar: [modeledFraction] solid from the start, the rest hatched in a 1 dp outline, 2 dp apart. */
@Composable
private fun ReconcileBar(
    modeledFraction: Float,
    solid: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val top = (size.height - BarHeight.toPx()) / 2
        val height = BarHeight.toPx()
        val gapPx = SegmentGap.toPx()
        val corner = CornerRadius(BarEnd.toPx())
        val fraction = modeledFraction.coerceIn(0f, 1f)
        // A gap too thin to see is not drawn; the key still states it.
        val hatched = (size.width - gapPx) * (1 - fraction) >= MinHatch.toPx()
        val solidWidth = if (hatched) (size.width - gapPx) * fraction else size.width
        val rtl = layoutDirection == LayoutDirection.Rtl

        fun x(
            start: Float,
            width: Float,
        ) = if (rtl) size.width - start - width else start
        if (solidWidth > 0) drawRoundRect(solid, Offset(x(0f, solidWidth), top), Size(solidWidth, height), corner)
        if (hatched) {
            val start = solidWidth + if (solidWidth > 0) gapPx else 0f
            val rect = Rect(Offset(x(start, size.width - start), top), Size(size.width - start, height))
            hatch(rect, solid)
            val stroke = Outline.toPx()
            drawRoundRect(
                solid,
                rect.topLeft + Offset(stroke / 2, stroke / 2),
                Size(rect.width - stroke, rect.height - stroke),
                corner,
                style = Stroke(stroke),
            )
        }
    }
}

/** 45° lines every 5 dp across [rect] (lab `#hatch`). */
private fun DrawScope.hatch(
    rect: Rect,
    color: Color,
) {
    val step = HatchStep.toPx()
    val stroke = HatchStroke.toPx()
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        var x = rect.left - rect.height
        while (x < rect.right) {
            drawLine(color, Offset(x, rect.bottom), Offset(x + rect.height, rect.top), stroke)
            x += step
        }
    }
}

private const val MS_PER_MINUTE = 60_000L
private val MarkGap = 2.dp
private val Outline = 1.dp
private val HatchStep = 5.dp
private val HatchStroke = 1.5.dp
private val MinHatch = 3.dp

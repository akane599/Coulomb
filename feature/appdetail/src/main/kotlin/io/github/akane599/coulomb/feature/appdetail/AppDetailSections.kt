// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombTerm
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentSectionGap
import io.github.akane599.coulomb.core.designsystem.theme.sharedElement
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.AppIconTile
import io.github.akane599.coulomb.core.ui.FigureText
import io.github.akane599.coulomb.core.ui.IconStandIn
import io.github.akane599.coulomb.core.ui.MarkedText
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.affixed
import io.github.akane599.coulomb.core.ui.appIconKey
import io.github.akane599.coulomb.core.ui.breakable
import io.github.akane599.coulomb.core.ui.breakdown.BarShape
import io.github.akane599.coulomb.core.ui.breakdown.StackedBar
import io.github.akane599.coulomb.core.ui.breakdown.Swatch
import io.github.akane599.coulomb.core.ui.breakdown.barSegments
import io.github.akane599.coulomb.core.ui.breakdown.chartSemantics
import io.github.akane599.coulomb.core.ui.breakdown.hue
import io.github.akane599.coulomb.core.ui.breakdown.label
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import io.github.akane599.coulomb.core.ui.R as UiR

/**
 * The app's icon, package and window, then its figure (lab `.app-head`, `.app-total`). The figure
 * is the Drain row's, with its share of the same total; an app with no use says so (ADR-0041 §6).
 */
@Composable
internal fun Header(
    view: AppDetailView,
    span: DrainSpan,
    formats: Formats,
    icon: ImageBitmap?,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val name = formats.appName(view.detail.label, view.detail.uid)
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconTile(icon, IconStandIn.initialOf(name), sharedElement(appIconKey(view.detail.uid)))
            Column(Modifier.padding(start = CoulombSpacing.Space16).weight(1f)) {
                view.detail.iconPackage?.let { Text(breakable(it), color = colors.textSecondary, style = type.mono) }
                Text(
                    stringResource(R.string.appdetail_window_note, windowName(span, formats)),
                    color = colors.textSecondary,
                    style = type.caption,
                )
            }
        }
        val mah = view.mah
        if (mah == null) {
            Text(
                stringResource(R.string.appdetail_no_use),
                Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space24),
                color = colors.textSecondary,
                style = type.body,
            )
            return@Column
        }
        Column(Modifier.padding(top = CoulombSpacing.Space16).semantics(mergeDescendants = true) {}) {
            Row {
                FigureText(
                    formats.mah(mah).affixed(),
                    numberStyle = type.display,
                    unitStyle = type.displayUnit,
                    modifier = Modifier.alignByBaseline(),
                    rolling = true,
                )
                // On the numeral's baseline, as Drain's total; the caption says where it came from.
                Box(Modifier.alignBy { it.measuredHeight }.padding(start = CoulombSpacing.Space4)) { ProvenanceMark(view.provenance) }
            }
            Text(shareCaption(view, formats), Modifier.fillMaxWidth(), color = colors.textSecondary, style = type.body)
        }
        val report = view.detail.report
        val notes =
            listOfNotNull(
                stringResource(R.string.appdetail_charged).takeIf { report.chargedInSpan },
                stringResource(R.string.appdetail_lower_bound).takeIf { report.lowerBound },
            )
        notes.forEach { Note(it, top = CoulombSpacing.Space4) }
    }
}

/** "33% of the 1,204 mAh measured", naming the share's denominator as Drain's caption does. */
@Composable
private fun shareCaption(
    view: AppDetailView,
    formats: Formats,
): String {
    val share = formats.share(view.share ?: 0.0)
    val total = formats.mah(view.denominator).joined()
    return when {
        view.scaled -> stringResource(R.string.appdetail_share_scaled, share, total)
        view.detail.report.drains.sharesOfMeasured -> stringResource(R.string.appdetail_share_of_measured, share, total)
        else -> stringResource(R.string.appdetail_share_of_modeled, share, total)
    }
}

/** The chosen window's name, as Drain's selector shows it. */
@Composable
internal fun windowName(
    span: DrainSpan,
    formats: Formats,
): String =
    when (span) {
        DrainSpan.SinceUnplugged -> stringResource(R.string.appdetail_window_since_unplugged)
        DrainSpan.Today -> stringResource(R.string.appdetail_window_today)
        DrainSpan.Last24Hours -> stringResource(R.string.appdetail_window_last_24h)
        DrainSpan.LastNight -> stringResource(R.string.appdetail_window_last_night)
        is DrainSpan.Days -> stringResource(R.string.appdetail_window_days, formats.days(span.first, span.last))
    }

/**
 * Where it went (SPEC 10.4 item 4): S4's components as one stacked bar with its parts listed under
 * it, then its process states as a table, each with what S4 did not split (ADR-0041 §2, §3).
 */
@Composable
internal fun WhereItWent(
    view: AppDetailView,
    formats: Formats,
    onViewTable: () -> Unit,
) {
    val colors = CoulombTheme.colors
    val detail = view.detail
    Column(Modifier.fillMaxWidth()) {
        val components = detail.components
        val total = detail.app?.mah ?: 0.0
        val split = components != null && total > 0.0
        // A mark only over figures: a sentence in their place carries none.
        SectionTitle(stringResource(R.string.appdetail_where), view.provenance.takeIf { split })
        if (components == null || !split) {
            val off = detail.report.breakdownOff
            Text(
                stringResource(if (off) R.string.appdetail_components_off else R.string.appdetail_components_none),
                Modifier.fillMaxWidth(),
                color = colors.textSecondary,
                style = CoulombTheme.typography.body,
            )
            return@Column
        }
        val names = components.parts.map { stringResource(it.component.label) }
        val notBrokenDown = stringResource(UiR.string.component_not_broken_down)
        val description =
            stringResource(
                R.string.appdetail_components_chart,
                formats.appName(detail.label, detail.uid),
                (names.zip(components.parts.map { it.mah }) + listOfNotNull(components.notBrokenDownMah?.let { notBrokenDown to it }))
                    .joinToString(", ") { (name, mah) -> "$name ${formats.mah(mah * view.factor).joined()}" },
            )
        val segments =
            remember(components, colors) {
                barSegments(
                    components.parts,
                    { it.component },
                    { it.mah },
                    { colors.hue(it) },
                    colors.dataOther,
                    components.notBrokenDownMah,
                )
            }
        StackedBar(
            segments,
            segments.sumOf { it.second },
            Modifier
                .fillMaxWidth()
                .height(BarShape.Box)
                .chartSemantics(description, stringResource(UiR.string.page_view_table), onViewTable),
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8).clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16),
            verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space4),
        ) {
            components.parts.forEachIndexed { index, part ->
                Chip(names[index], formats.mah(part.mah * view.factor).joined(), colors.hue(part.component))
            }
            components.notBrokenDownMah?.let { Chip(notBrokenDown, formats.mah(it * view.factor).joined(), hue = null) }
        }
        if (detail.report.breakdownOff) Note(stringResource(R.string.appdetail_components_off_part))
        detail.processStates?.let { ProcessStateTable(it, view.factor, formats) }
    }
}

/** One component under the bar (DESIGN.md, Breakdown): swatch, name in Label, mAh in `text-secondary`. */
@Composable
private fun Chip(
    name: String,
    value: String,
    hue: Color?,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Row(Modifier.heightIn(min = ChipHeight), verticalAlignment = Alignment.CenterVertically) {
        Swatch(hue)
        Box(Modifier.width(SwatchGap))
        Text(name, color = colors.textPrimary, style = type.label)
        Box(Modifier.width(SwatchGap))
        Text(value, color = colors.textSecondary, style = type.label.copy(fontWeight = type.body.fontWeight))
    }
}

private val ChipHeight = 24.dp
private val SwatchGap = 6.dp

/**
 * The App info row (lab `.link-row` in `.rows`): a row between hairlines, its label in Data and a
 * 20 dp icon, that opens the system's page for the app, outside Coulomb.
 */
@Composable
internal fun AppInfoRow(onClick: () -> Unit) {
    val colors = CoulombTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = currentSectionGap())
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(colors.hairline, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
                drawLine(colors.hairline, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            }.heightIn(min = LinkRowHeight)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.appdetail_app_info),
            // Wrapped, so the label starts the row in either direction whatever its script.
            Modifier.weight(1f).wrapContentWidth(Alignment.Start),
            color = colors.textPrimary,
            style = CoulombTheme.typography.data,
        )
        Icon(
            painterResource(CoulombIcons.OpenInNew),
            contentDescription = null,
            modifier = Modifier.size(LinkIconSize),
            tint = colors.textSecondary,
        )
    }
}

private val LinkRowHeight = 56.dp
private val LinkIconSize = 20.dp

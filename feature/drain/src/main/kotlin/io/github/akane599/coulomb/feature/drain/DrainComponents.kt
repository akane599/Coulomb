// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.ComponentDrain
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.designsystem.component.ShareBar
import io.github.akane599.coulomb.core.designsystem.theme.CoulombColors
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.stats.PowerComponent
import io.github.akane599.coulomb.core.ui.FigureText
import io.github.akane599.coulomb.core.ui.NameBesideValue
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.affixed
import io.github.akane599.coulomb.core.ui.breakdown.BarShape
import io.github.akane599.coulomb.core.ui.breakdown.StackedBar
import io.github.akane599.coulomb.core.ui.breakdown.Swatch
import io.github.akane599.coulomb.core.ui.breakdown.barSegments
import io.github.akane599.coulomb.core.ui.breakdown.chartSemantics
import io.github.akane599.coulomb.core.ui.breakdown.hue
import io.github.akane599.coulomb.core.ui.breakdown.label
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.R as UiR

/**
 * The Components lens (SPEC 10.3): the device's modeled power by component, as one stacked bar in
 * the data hues and a ranked list with a swatch on each row. Components with no hue of their own
 * share the neutral Other hue and sit together at the bar's end (DESIGN.md, Data palette). With
 * S4's components, each row names its top apps, and the energy of the windows S4 did not break
 * down closes the bar and the list, hollow, so the parts add up to the whole (ADR-0035 §7). The
 * head holds the caption and the bar; each row is its own keyed item, so a reorder after a new
 * report slides (SPEC 11.6).
 */
internal fun LazyListScope.componentItems(
    report: DrainReport,
    formats: Formats,
    onViewTable: () -> Unit,
) {
    val components = report.components
    val rest = report.componentsNotBrokenDownMah?.takeIf { it >= MIN_SHOWN_MAH }
    val total = components.sumOf { it.mah } + (rest ?: 0.0)
    item(key = "components-head", contentType = "components-head") { ComponentsHead(report, rest, total, formats, onViewTable) }
    if (components.isEmpty()) return
    item(key = "components-gap", contentType = "gap") { Spacer(Modifier.height(CoulombSpacing.Space16)) }
    itemsIndexed(components, key = { _, part -> "component-${part.component}" }, contentType = { _, _ -> "component" }) { index, part ->
        val colors = CoulombTheme.colors
        Box(rowMotion()) {
            ComponentRow(
                name = stringResource(part.component.label),
                detail = topApps(part, formats),
                mah = part.mah,
                share = part.mah / total,
                first = index == 0,
                hue = colors.hue(part.component),
                formats = formats,
            )
        }
    }
    if (rest != null) {
        item(key = "component-rest", contentType = "component") {
            val detail = if (report.breakdownOff) R.string.drain_not_broken_down_off else R.string.drain_not_broken_down_detail
            Box(rowMotion()) {
                ComponentRow(
                    stringResource(UiR.string.component_not_broken_down),
                    stringResource(detail),
                    rest,
                    rest / total,
                    false,
                    null,
                    formats,
                )
            }
        }
    }
}

/** A component's top apps with their mAh, "Halcyon 188 mAh · Wayline 40 mAh"; null without S4. */
@Composable
private fun topApps(
    part: ComponentDrain,
    formats: Formats,
): String? {
    val resources = LocalResources.current
    return part.topApps
        .map { resources.getString(R.string.drain_component_app, formats.appName(it.label, it.uid), formats.mah(it.mah).joined()) }
        .reduceOrNull { a, b -> resources.getString(R.string.drain_detail_separator, a, b) }
}

/** Less than this is rounding, not energy worth a row (as [io.github.akane599.coulomb.core.data.SpanBreakdown]'s remainder). */
private const val MIN_SHOWN_MAH = 0.05

/**
 * The lens's caption, the coarse note where Android names only some components (naming the
 * setting when it was off, ADR-0035 §2), and the stacked bar, which ends in [rest] when S4's
 * components leave some of the window unsplit.
 */
@Composable
private fun ComponentsHead(
    report: DrainReport,
    rest: Double?,
    total: Double,
    formats: Formats,
    onViewTable: () -> Unit,
) {
    val components = report.components
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Column(Modifier.padding(top = CoulombSpacing.Space16)) {
        if (components.isEmpty()) {
            Text(stringResource(R.string.drain_components_empty), Modifier.fillMaxWidth(), color = colors.textSecondary, style = type.body)
            return@Column
        }
        // DESIGN.md: the caption names the denominator of every share, here Android's model.
        Text(
            stringResource(R.string.drain_components_caption, formats.mah(total).joined()),
            Modifier.fillMaxWidth(),
            color = colors.textSecondary,
            style = type.caption,
        )
        // S5's coarse rows: with S4's, the hollow row says what is not split.
        val coarse = report.componentsNotBrokenDownMah == null
        val note =
            when {
                coarse && report.breakdownOff -> R.string.drain_components_off
                components.any { it.component == PowerComponent.OTHER } -> R.string.drain_components_coarse
                else -> null
            }
        if (note != null) {
            Text(stringResource(note), Modifier.fillMaxWidth(), color = colors.textSecondary, style = type.caption)
        }
        val parts =
            components.map { stringResource(it.component.label) to it.mah } +
                listOfNotNull(rest?.let { stringResource(UiR.string.component_not_broken_down) to it })
        val description =
            stringResource(
                R.string.drain_components_chart,
                parts.joinToString(", ") { (name, mah) -> "$name ${formats.share(mah / total)}" },
            )
        val segments =
            remember(
                components,
                rest,
                colors,
            ) { barSegments(components, { it.component }, { it.mah }, { colors.hue(it) }, colors.dataOther, rest) }
        StackedBar(
            segments,
            total,
            Modifier
                .padding(top = CoulombSpacing.Space16)
                .fillMaxWidth()
                .height(BarShape.Box)
                .chartSemantics(description, stringResource(R.string.drain_view_table), onViewTable),
        )
    }
}

/**
 * One component: a 10 dp swatch in its hue (hollow without one), the name with [detail] in Caption
 * under it, the mAh with the Modeled mark and its share under it, and a neutral share bar
 * (DESIGN.md, Breakdown and Rows). Hairlines divide rows; TalkBack reads one item.
 */
@Composable
@Suppress("LongParameterList") // One row's content.
private fun ComponentRow(
    name: String,
    detail: String?,
    mah: Double,
    share: Double,
    first: Boolean,
    hue: Color?,
    formats: Formats,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .hairlines(colors.hairline, top = first, bottom = true)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = RowHeight)
            .padding(vertical = CoulombSpacing.Space12),
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space12),
    ) {
        // The swatch centres on the name's first line; the value shares that line, as on Apps rows.
        val nameLine = with(LocalDensity.current) { type.bodyName.lineHeight.toDp() }
        Swatch(hue, Modifier.padding(top = (nameLine - SwatchSize) / 2))
        Column(Modifier.weight(1f)) {
            NameBesideValue(
                name = {
                    Column {
                        Text(name, color = colors.textPrimary, style = type.bodyName)
                        if (detail != null) Text(detail, color = colors.textSecondary, style = type.caption)
                    }
                },
                value = {
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FigureText(
                                formats.mah(mah).affixed(),
                                numberStyle = type.data,
                                unitStyle = type.data.copy(fontWeight = type.body.fontWeight),
                            )
                            ProvenanceMark(Provenance.MODELED)
                        }
                        Text(formats.share(share), color = colors.textSecondary, style = type.caption, textAlign = TextAlign.End)
                    }
                },
            )
            ShareBar(share.toFloat(), Modifier.padding(top = CoulombSpacing.Space8))
        }
    }
}

private val SwatchSize = 10.dp
private val RowHeight = 56.dp

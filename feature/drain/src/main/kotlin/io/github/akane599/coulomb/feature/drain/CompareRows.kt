// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.PowerComponent
import io.github.akane599.coulomb.core.data.WakeBasis
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombSwitchRow
import io.github.akane599.coulomb.core.designsystem.component.CoulombTerm
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.component.PairedBars
import io.github.akane599.coulomb.core.designsystem.component.PairedBarsKey
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import io.github.akane599.coulomb.core.ui.AppIconTile
import io.github.akane599.coulomb.core.ui.FigureText
import io.github.akane599.coulomb.core.ui.IconStandIn
import io.github.akane599.coulomb.core.ui.MarkedText
import io.github.akane599.coulomb.core.ui.NameBesideValue
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.rememberAppIcon
import kotlin.math.abs

/**
 * Compare's lens tabs and the chosen lens's rows, each its own keyed item (SPEC 15). With no
 * records in a period there is nothing to compare, and the totals say so.
 */
@Suppress("LongParameterList") // One list's inputs.
internal fun LazyListScope.compareLensItems(
    state: CompareUiState,
    lens: Lens,
    choose: (Lens) -> Unit,
    formats: Formats,
    actions: CompareActions,
    lists: CompareLists,
    besideSummary: Boolean = false,
) {
    val comparison = state.comparison
    if (comparison == null) {
        if (!state.failed) item(key = "lens-skeleton", contentType = "skeleton") { LensSkeleton(besideSummary) }
        return
    }
    if (comparison.first.report == null || comparison.second.report == null) return
    item(key = "tabs", contentType = "tabs") { LensTabs(lens, choose, besideSummary) }
    when (lens) {
        Lens.APPS -> appItems(state, comparison, formats, actions, lists)
        Lens.COMPONENTS -> componentItems(comparison, formats, lists)
        Lens.WAKEUPS -> wakeItems(comparison, formats, lists)
    }
}

private fun LazyListScope.appItems(
    state: CompareUiState,
    comparison: Comparison,
    formats: Formats,
    actions: CompareActions,
    lists: CompareLists,
) {
    // Without rates in both periods there are no rows, so only the reason shows.
    if (!comparison.ratesCounted) {
        item(key = "apps-head", contentType = "list-head") { BatteryNotes(comparison, formats) }
        return
    }
    item(key = "apps-head", contentType = "list-head") {
        Column(Modifier.padding(top = CoulombSpacing.Space8)) {
            CoulombSwitchRow(stringResource(R.string.drain_include_system), state.includeSystem, actions.includeSystem)
            ListCaption(R.string.drain_compare_caption_model, comparison, formats) {
                if (comparison.lowerBound) Caption(stringResource(R.string.drain_lower_bound))
            }
        }
    }
    rowItems("apps", comparison.apps, RateUnit.MAH, Lens.APPS.ordinal, formats, lists) { row, first ->
        AppCompareRow(row, comparison, formats, actions, first)
    }
}

private fun LazyListScope.componentItems(
    comparison: Comparison,
    formats: Formats,
    lists: CompareLists,
) {
    if (!comparison.ratesCounted) {
        item(key = "components-head", contentType = "list-head") { BatteryNotes(comparison, formats) }
        return
    }
    item(key = "components-head", contentType = "list-head") {
        Column(Modifier.padding(top = CoulombSpacing.Space16)) {
            // As on Drain: a device that reports some power only as "Other" says so.
            val coarse =
                listOf(comparison.first, comparison.second).any { period ->
                    period.report?.components?.any { it.component == PowerComponent.OTHER } == true
                }
            ListCaption(R.string.drain_compare_caption_model, comparison, formats) {
                if (coarse) Caption(stringResource(R.string.drain_compare_components_coarse))
            }
        }
    }
    val scale = comparison.componentsScale
    rowItems("components", comparison.components, RateUnit.MAH, Lens.COMPONENTS.ordinal, formats, lists) { row, first ->
        val component = (row.subject as CompareSubject.Component).component
        CompareRow(stringResource(component.label), detail = null, row, RateUnit.MAH, scale, first, formats)
    }
}

private fun LazyListScope.wakeItems(
    comparison: Comparison,
    formats: Formats,
    lists: CompareLists,
) {
    // A period no wake source covered says so once. Rows need both periods, so the groups then wait.
    val uncollected =
        listOf(Slot.FIRST to comparison.first, Slot.SECOND to comparison.second)
            .filter { (_, period) -> period.report != null && !period.wakesCollected }
            .map { it.first }
            .toSet()
    item(key = "wakes-head", contentType = "list-head") {
        Column(Modifier.padding(top = CoulombSpacing.Space16)) {
            if (uncollected.isEmpty()) {
                ListCaption(R.string.drain_compare_caption_wakes, comparison, formats)
                Caption(stringResource(R.string.drain_compare_wake_lower))
            }
            uncollected.forEach { slot -> Note(stringResource(R.string.drain_compare_note_no_wakes, stringResource(slot.label))) }
        }
    }
    if (uncollected.isNotEmpty()) return
    comparison.wakes.forEachIndexed { index, group ->
        item(key = "group-${group.key}", contentType = "group-head") { GroupTop(group, formats) }
        if (group.comparable && group.firstCounted && group.secondCounted) {
            rowItems("group-${group.key}", group.rows, group.unit, GROUP_BITS + index, formats, lists) { row, first ->
                WakeCompareRow(row, group, formats, first)
            }
        }
    }
}

/**
 * A list's rows: its first [SHOWN] by the size of their change, or all once opened, then "Show
 * all" or "Show fewer". With nothing in either period, one line says so.
 */
@Suppress("LongParameterList") // One list's inputs.
private fun LazyListScope.rowItems(
    key: String,
    rows: List<ComparedRow>,
    unit: RateUnit,
    bit: Int,
    formats: Formats,
    lists: CompareLists,
    row: @Composable (ComparedRow, first: Boolean) -> Unit,
) {
    if (rows.isEmpty()) {
        item(key = "$key-none", contentType = "note") { Note(stringResource(R.string.drain_compare_none), rowMotion()) }
        return
    }
    val open = lists.isOpen(bit)
    item(key = "$key-gap", contentType = "gap") { Spacer(Modifier.padding(top = CoulombSpacing.Space12)) }
    itemsIndexed(
        if (open) rows else rows.take(SHOWN),
        key = { _, shown -> "$key-${shown.key}" },
        contentType = { _, _ -> "row-$unit" },
    ) { at, shown ->
        Column(rowMotion()) { row(shown, at == 0) }
    }
    if (rows.size > SHOWN) {
        item(key = "$key-more", contentType = "button") {
            val state = stringResource(if (open) R.string.drain_state_expanded else R.string.drain_state_collapsed)
            CoulombTextButton(
                text =
                    if (open) {
                        stringResource(
                            R.string.drain_show_fewer,
                        )
                    } else {
                        stringResource(R.string.drain_show_all, formats.integer(rows.size.toLong()))
                    },
                onClick = { lists.toggle(bit) },
                // The button stays the same item as it moves, so TalkBack keeps its focus on it.
                modifier = rowMotion().padding(top = CoulombSpacing.Space4).semantics { stateDescription = state },
                edge = ButtonEdge.Start,
            )
        }
    }
}

/**
 * What a list's figures are and where they come from, with the Derived mark that covers its rows
 * (DESIGN.md: one source, one mark) right after its last word, then any [notes], then the key to
 * the bars, last, so it sits next to the rows it explains.
 */
@Composable
private fun ListCaption(
    text: Int,
    comparison: Comparison,
    formats: Formats,
    notes: @Composable () -> Unit = {},
) = Column {
    MarkedText(
        stringResource(text),
        Provenance.DERIVED,
        style = CoulombTheme.typography.caption,
        color = CoulombTheme.colors.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8),
    )
    notes()
    PairedBarsKey(
        formats.days(comparison.first.days.first, comparison.first.days.last),
        formats.days(comparison.second.days.first, comparison.second.days.last),
        Modifier.padding(top = CoulombSpacing.Space8),
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space4),
        color = CoulombTheme.colors.textSecondary,
        style = CoulombTheme.typography.caption,
    )
}

/**
 * Why a period has no apps' or components' rates: S5 was not collected, or too little time on
 * battery was counted (SPEC 6.2).
 */
@Composable
private fun BatteryNotes(
    comparison: Comparison,
    formats: Formats,
) = Column(Modifier.padding(top = CoulombSpacing.Space4)) {
    listOf(Slot.FIRST to comparison.first, Slot.SECOND to comparison.second)
        .filter { (_, period) -> period.report != null && !period.ratesCounted }
        .forEach { (slot, period) ->
            val name = stringResource(slot.label)
            Note(
                if (period.statsCollected) {
                    stringResource(R.string.drain_compare_note_short_battery, name, formats.held(MIN_RATE_MS))
                } else {
                    stringResource(R.string.drain_compare_note_no_stats, name)
                },
            )
        }
}

/**
 * A Wakeups group's top: its name, when its counter ran in each period, and why it is not compared
 * or what limits it.
 */
@Composable
private fun LazyItemScope.GroupTop(
    group: ComparedWakeGroup,
    formats: Formats,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Column(rowMotion().padding(top = CoulombSpacing.Space24)) {
        val title = stringResource(group.title)
        Row(Modifier.semantics { heading() }) {
            val definition = group.definition
            if (definition != null) {
                CoulombTerm(title, stringResource(definition), stringResource(R.string.drain_term_action), type.label, colors.textSecondary)
            } else {
                Text(title, color = colors.textSecondary, style = type.label)
            }
        }
        val basis = group.basis
        val firstMs = group.firstBasisMs
        val secondMs = group.secondBasisMs
        if (basis != null && firstMs != null && secondMs != null) {
            Caption(stringResource(basis.compareLine, formats.held(firstMs), formats.held(secondMs)))
        }
        when {
            !group.comparable -> {
                Note(stringResource(R.string.drain_compare_sources_differ))
            }

            else -> {
                listOf(
                    Triple(Slot.FIRST, group.firstCounted, group.firstCoverage),
                    Triple(Slot.SECOND, group.secondCounted, group.secondCoverage),
                ).forEach { (slot, counted, coverage) ->
                    when {
                        !counted -> {
                            Note(groupNote(slot, coverage, basis, formats))
                        }

                        // Batterystats' rates divide by the time its windows covered; say that it was part.
                        coverage == Coverage.PARTIAL -> {
                            Qualifier(stringResource(R.string.drain_compare_note_partial, stringResource(slot.label)))
                        }
                    }
                }
            }
        }
    }
}

/** Why a period gives a group no rates: not collected, collected for part of it, or counted too briefly. */
@Composable
private fun groupNote(
    slot: Slot,
    coverage: Coverage?,
    basis: WakeBasis?,
    formats: Formats,
): String {
    val name = stringResource(slot.label)
    return when {
        coverage == null || coverage == Coverage.NONE -> stringResource(R.string.drain_compare_note_not_collected, name)
        coverage == Coverage.PARTIAL && basis == WakeBasis.WHOLE_SPAN -> stringResource(R.string.drain_compare_note_part_root, name)
        else -> stringResource(R.string.drain_compare_note_short_count, name, formats.held(MIN_RATE_MS))
    }
}

/** When a group's counter ran, in each period (ADR-0036 §34). */
private val WakeBasis.compareLine: Int
    get() =
        when (this) {
            WakeBasis.SCREEN_OFF_ON_BATTERY -> R.string.drain_compare_basis_screen_off
            WakeBasis.ON_BATTERY -> R.string.drain_compare_basis_on_battery
            WakeBasis.WHOLE_SPAN -> R.string.drain_compare_basis_whole
        }

/** An Apps row: an app with its icon, the phone itself, the small apps or the hidden system apps. */
@Composable
private fun AppCompareRow(
    row: ComparedRow,
    comparison: Comparison,
    formats: Formats,
    actions: CompareActions,
    first: Boolean,
) {
    val scale = comparison.appsScale
    when (val subject = row.subject) {
        is CompareSubject.App -> {
            val app = subject.app
            val name = formats.appName(app.label, app.uid)
            val icon = rememberAppIcon(app.iconPackage, actions.icon, actions.peekIcon)
            // A bare UID has no name to take an initial from.
            val standIn = if (app.label == null && !app.isSystem) IconStandIn.Initial("#") else IconStandIn.initialOf(name)
            val open = actions.openApp
            CompareRow(name, null, row, RateUnit.MAH, scale, first, formats, icon = {
                AppIconTile(icon, standIn)
            }, onClick = open?.let { { it(app.uid) } })
        }

        CompareSubject.Device -> {
            CompareRow(
                stringResource(R.string.drain_device),
                stringResource(R.string.drain_device_detail),
                row,
                RateUnit.MAH,
                scale,
                first,
                formats,
                icon = { AppIconTile(null, IconStandIn.Device) },
            )
        }

        CompareSubject.SmallApps -> {
            CompareRow(stringResource(R.string.drain_small_apps), null, row, RateUnit.MAH, scale, first, formats, icon = {
                AppIconTile(null, IconStandIn.Group("+"))
            })
        }

        CompareSubject.HiddenSystem -> {
            val count = comparison.hiddenSystem
            CompareRow(
                pluralStringResource(
                    R.plurals.drain_hidden_system,
                    count,
                    formats.integer(count.toLong()),
                ),
                null,
                row,
                RateUnit.MAH,
                scale,
                first,
                formats,
                icon = {
                    AppIconTile(null, IconStandIn.Group("+${formats.integer(count.toLong())}"))
                },
            )
        }

        // Not an app or a wake record: no other lens's row lands here.
        else -> {}
    }
}

/** A Wakeups row: a wakelock by its app with its tag, a kernel record by its name, or an app's alarms. */
@Composable
private fun WakeCompareRow(
    row: ComparedRow,
    group: ComparedWakeGroup,
    formats: Formats,
    first: Boolean,
) {
    val scale = group.scale
    when (val subject = row.subject) {
        is CompareSubject.Wake -> {
            val entry = subject.entry
            val uid = entry.uid
            if (entry.kind == WakeKind.PARTIAL && uid != null) {
                CompareRow(formats.appName(entry.label, uid), entry.tag, row, group.unit, scale, first, formats, monoDetail = true)
            } else {
                CompareRow(entry.tag, null, row, group.unit, scale, first, formats, monoName = true)
            }
        }

        is CompareSubject.Alarm -> {
            val entry = subject.entry
            val name = if (entry.smallApps) stringResource(R.string.drain_small_apps) else formats.appName(entry.label, entry.uid)
            CompareRow(name, null, row, group.unit, scale, first, formats)
        }

        // Not an app or a wake record: no other lens's row lands here.
        else -> {}
    }
}

/**
 * A compared row (DESIGN.md, Rows with share bars; ADR-0038 §8): the icon where it has one, the
 * name, the change in the rate's unit at the end, and the paired bars with each period's rate at
 * their end. The list caption carries the Derived mark. TalkBack reads one sentence: the name, both
 * rates and whether it went up or down. Kernel identifiers are set in Mono; a wakelock's tag is cut
 * at two lines.
 */
@Composable
@Suppress("LongParameterList") // One row's content.
private fun CompareRow(
    name: String,
    detail: String?,
    row: ComparedRow,
    unit: RateUnit,
    scale: Double,
    first: Boolean,
    formats: Formats,
    icon: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    monoName: Boolean = false,
    monoDetail: Boolean = false,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val before = row.first ?: 0.0
    val after = row.second ?: 0.0
    val change = after - before
    val figure = rowFigure(before, after, formats)
    val spoken = spokenRow(detail?.let { stringResource(R.string.drain_compare_row_name_tag, name, it) } ?: name, row, unit, formats)
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = Hairline.toPx()
                if (first) drawLine(colors.hairline, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
                drawLine(colors.hairline, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            }.then(clickable)
            .clearAndSetSemantics {
                contentDescription = spoken
                if (onClick != null) role = Role.Button
            }.heightIn(min = if (icon != null) IconRowHeight else RowHeight)
            .padding(vertical = CoulombSpacing.Space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(CoulombSpacing.Space16))
        }
        Column(Modifier.weight(1f)) {
            NameBesideValue(
                name = { RowName(name, detail, monoName, monoDetail) },
                value = {
                    FigureText(
                        rate(unit, formats.signedAmount(change)),
                        numberStyle = type.data,
                        unitStyle = type.data.copy(fontWeight = type.body.fontWeight),
                    )
                },
            )
            val fraction = { rate: Double -> if (scale > 0) (rate / scale).toFloat() else 0f }
            PairedBars(
                first = fraction(before),
                second = fraction(after),
                firstText = figure(before),
                secondText = figure(after),
                modifier = Modifier.padding(top = CoulombSpacing.Space8),
            )
        }
    }
}

/** A row's name, and its detail under it; kernel identifiers in Mono, a wakelock's tag cut at [tagLines]. */
@Composable
private fun RowName(
    name: String,
    detail: String?,
    monoName: Boolean,
    monoDetail: Boolean,
) = Column {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Text(
        if (monoName) breakable(name) else name,
        color = colors.textPrimary,
        style = if (monoName) type.mono else type.bodyName,
    )
    if (detail != null) {
        Text(
            if (monoDetail) breakable(detail) else detail,
            color = colors.textSecondary,
            style = if (monoDetail) type.mono else type.caption,
            maxLines = if (monoDetail) tagLines() else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * How a row prints its rates: as [Formats.amount], or one digit finer where the two round alike
 * around a change that shows, so the row never reads "0.6, 0.6, up 0.1".
 */
private fun rowFigure(
    before: Double,
    after: Double,
    formats: Formats,
): (Double) -> String {
    val shows = !formats.roundsToZero(after - before)
    return if (shows && formats.amount(before) == formats.amount(after)) formats::fineAmount else formats::amount
}

/** A row as TalkBack reads it: the name (with its tag), both rates and the direction of the change. */
@Composable
private fun spokenRow(
    name: String,
    row: ComparedRow,
    unit: RateUnit,
    formats: Formats,
): String {
    val before = row.first ?: 0.0
    val after = row.second ?: 0.0
    val change = after - before
    val step = formats.amount(abs(change))
    val figure = rowFigure(before, after, formats)
    val direction =
        when {
            formats.roundsToZero(change) -> stringResource(R.string.drain_compare_same)
            change > 0 -> stringResource(R.string.drain_compare_up, stringResource(unit.template, step))
            else -> stringResource(R.string.drain_compare_down, stringResource(unit.template, step))
        }
    return stringResource(
        R.string.drain_compare_row_spoken,
        name,
        stringResource(unit.template, figure(before)),
        stringResource(unit.template, figure(after)),
        direction,
    )
}

private val Hairline = 1.dp
private val IconRowHeight: Dp = 72.dp
private val RowHeight: Dp = 56.dp

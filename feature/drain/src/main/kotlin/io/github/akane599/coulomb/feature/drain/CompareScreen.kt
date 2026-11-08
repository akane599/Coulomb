// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigure
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigureStrip
import io.github.akane599.coulomb.core.designsystem.component.CoulombOutlinedButton
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.designsystem.theme.currentSectionGap
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.FigureText
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.format.Affixed
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats

/**
 * Compare two periods (ADR-0038): two ranges of days side by side, each figure per hour of the time
 * its counter ran.
 *
 * @param onOpenApp opens App detail for a UID; null while there is none to open.
 */
@Composable
public fun CompareScreen(
    onOpenApp: ((uid: Int) -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: CompareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions =
        remember(viewModel, onOpenApp) {
            CompareActions(
                chooseFirst = viewModel::chooseFirst,
                chooseSecond = viewModel::chooseSecond,
                includeSystem = viewModel::includeSystem,
                retry = viewModel::retry,
                openApp = onOpenApp,
                icon = viewModel::icon,
            )
        }
    CompareContent(state, actions, modifier)
}

/** What Compare's controls do; the screen itself holds no logic. */
internal class CompareActions(
    val chooseFirst: (DrainSpan.Days) -> Unit,
    val chooseSecond: (DrainSpan.Days) -> Unit,
    val includeSystem: (Boolean) -> Unit,
    val retry: () -> Unit,
    val openApp: ((Int) -> Unit)?,
    val icon: suspend (packageName: String, sizePx: Int) -> ImageBitmap?,
)

/** Which period a picker or a note is about. */
internal enum class Slot(
    @param:StringRes val label: Int,
    @param:StringRes val pickerTitle: Int,
) {
    FIRST(R.string.drain_compare_first, R.string.drain_compare_choose_first),
    SECOND(R.string.drain_compare_second, R.string.drain_compare_choose_second),
}

/**
 * One list on compact widths; where the window fits two columns, the periods and totals scroll in
 * the first and the lens in the second, as on Drain (ADR-0036 §23).
 */
@Composable
internal fun CompareContent(
    state: CompareUiState,
    actions: CompareActions,
    modifier: Modifier = Modifier,
    initialLens: Lens = Lens.APPS,
) {
    val formats = rememberFormats()
    val gutter = currentGutter()
    var lens by rememberSaveable { mutableStateOf(initialLens) }
    var picking by rememberSaveable { mutableStateOf<Slot?>(null) }
    // The lists showing all their rows, one bit each (CompareLists); closed again for new periods.
    var open by rememberSaveable(state.first, state.second) { mutableIntStateOf(0) }
    val lists = CompareLists(open) { bit -> open = open xor (1 shl bit) }
    val bottom = CoulombSpacing.Space48
    FadeInOnce(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (!fitsTwoColumns(maxWidth, gutter)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = gutter, end = gutter, bottom = bottom)) {
                    item(key = "summary", contentType = "summary") { CompareSummary(state, formats, actions, onPick = { picking = it }) }
                    compareLensItems(state, lens, { lens = it }, formats, actions, lists)
                }
            } else {
                Row(
                    Modifier.widthIn(max = MaxTwoColumnWidth).fillMaxSize().padding(horizontal = gutter),
                    horizontalArrangement = Arrangement.spacedBy(gutter),
                ) {
                    Column(
                        Modifier
                            .weight(SUMMARY_WEIGHT)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = bottom),
                    ) {
                        CompareSummary(state, formats, actions, onPick = { picking = it })
                    }
                    LazyColumn(Modifier.weight(LENS_WEIGHT).fillMaxHeight(), contentPadding = PaddingValues(bottom = bottom)) {
                        compareLensItems(state, lens, { lens = it }, formats, actions, lists, besideSummary = true)
                    }
                }
            }
        }
    }
    picking?.let { slot ->
        RangeDialog(
            initial = if (slot == Slot.FIRST) state.first else state.second,
            today = state.today,
            title = stringResource(slot.pickerTitle),
            onChoose = {
                if (slot == Slot.FIRST) actions.chooseFirst(it) else actions.chooseSecond(it)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** What Compare's lists keep open: bit [Lens.ordinal] for a lens, [GROUP_BITS] plus its index for a wake group. */
internal class CompareLists(
    val open: Int,
    val toggle: (Int) -> Unit,
) {
    fun isOpen(bit: Int): Boolean = open and (1 shl bit) != 0
}

internal const val GROUP_BITS = 3

/** The two period pickers, then the totals, or why there are none yet. */
@Composable
private fun CompareSummary(
    state: CompareUiState,
    formats: Formats,
    actions: CompareActions,
    onPick: (Slot) -> Unit,
) = Column {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8),
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space24),
        verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space12),
    ) {
        PeriodPicker(Slot.FIRST, state.first, formats, onPick)
        PeriodPicker(Slot.SECOND, state.second, formats, onPick)
    }
    val comparison = state.comparison
    when {
        state.failed -> ErrorState(actions.retry)
        comparison == null -> SummarySkeleton()
        else -> Totals(comparison, formats)
    }
}

/** A period's name over a button with its days, which opens the picker. TalkBack reads them as one. */
@Composable
private fun PeriodPicker(
    slot: Slot,
    days: DrainSpan.Days,
    formats: Formats,
    onPick: (Slot) -> Unit,
) {
    val name = stringResource(slot.label)
    val range = formats.days(days.first, days.last)
    val spoken = stringResource(R.string.drain_compare_period_button, name, range)
    Column {
        Text(
            name,
            Modifier.clearAndSetSemantics {},
            color = CoulombTheme.colors.textSecondary,
            style = CoulombTheme.typography.label,
        )
        CoulombOutlinedButton(
            text = range,
            onClick = { onPick(slot) },
            modifier = Modifier.padding(top = CoulombSpacing.Space4).semantics { contentDescription = spoken },
        )
    }
}

/**
 * The figure strip: each period's measured drain per hour, then the change between them when both
 * were measured by the counter. Under the strip, a period with no records says so, and an estimated
 * period says why there is no change.
 */
@Composable
private fun Totals(
    comparison: Comparison,
    formats: Formats,
) = Column {
    CoulombFigureStrip(Modifier.padding(top = currentSectionGap())) {
        PeriodFigure(comparison.first, formats)
        PeriodFigure(comparison.second, formats)
        comparison.drainDifference?.let { difference ->
            CoulombFigure(stringResource(R.string.drain_compare_change), comparison.drainChange?.let(formats::signedShare)) {
                StripFigure(rate(RateUnit.MAH, formats.signedAmount(difference)))
                ProvenanceMark(Provenance.DERIVED)
            }
        }
    }
    listOf(Slot.FIRST to comparison.first, Slot.SECOND to comparison.second)
        .filter { (_, period) -> period.report == null }
        .forEach { (slot, _) -> Note(stringResource(R.string.drain_compare_note_no_records, stringResource(slot.label))) }
    if (comparison.changeEstimated) Note(stringResource(R.string.drain_compare_note_estimated))
}

/**
 * A period's drain per hour (Derived; Estimated from the level, as a range), with the time it was
 * counted over; or why it has none.
 */
@Composable
private fun PeriodFigure(
    period: Period,
    formats: Formats,
) {
    val label = formats.days(period.days.first, period.days.last)
    val report = period.report
    val perHour = period.drainPerHour
    when {
        report == null -> {
            CoulombFigure(label, caption = null) { Missing(stringResource(R.string.drain_compare_no_records)) }
        }

        perHour == null -> {
            val (text, caption) =
                when {
                    period.tooShort -> {
                        R.string.drain_compare_too_short to stringResource(R.string.drain_compare_under, formats.held(MIN_RATE_MS))
                    }

                    period.noDrain -> {
                        R.string.drain_compare_no_drain to stringResource(R.string.drain_compare_no_drain_caption)
                    }

                    else -> {
                        R.string.drain_compare_not_measured to stringResource(R.string.drain_compare_not_measured_caption)
                    }
                }
            CoulombFigure(label, caption) { Missing(stringResource(text)) }
        }

        else -> {
            val onBattery = formats.held(report.onBatteryMs)
            val caption =
                when {
                    // Without S5 there is no time on battery; the counter's windows give the time instead.
                    !period.statsCollected -> {
                        stringResource(R.string.drain_compare_measured_over, formats.held(report.measuredMs))
                    }

                    period.estimated -> {
                        stringResource(
                            R.string.drain_compare_on_battery_estimated,
                            onBattery,
                            formats.held(period.screenOnMs),
                        )
                    }

                    else -> {
                        stringResource(R.string.drain_compare_on_battery, onBattery, formats.held(period.screenOnMs))
                    }
                }
            val range = period.drainRangePerHour
            CoulombFigure(label, caption) {
                val number =
                    range?.let { (low, high) -> formats.range(formats.amount(low), formats.amount(high)) } ?: formats.amount(perHour)
                StripFigure(rate(RateUnit.MAH, number))
                ProvenanceMark(if (period.estimated) Provenance.ESTIMATED else Provenance.DERIVED)
            }
        }
    }
}

/** A strip value: the number in Title, its unit in Label (as on Now). */
@Composable
private fun StripFigure(figure: Affixed) {
    FigureText(figure, numberStyle = CoulombTheme.typography.title, unitStyle = CoulombTheme.typography.label)
}

/** In place of a strip figure: why there is none, in Body. */
@Composable
private fun Missing(text: String) {
    Text(text, color = CoulombTheme.colors.textSecondary, style = CoulombTheme.typography.body)
}

/** A line under a section saying what it lacks and why, in Body. */
@Composable
internal fun Note(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier.fillMaxWidth().padding(top = CoulombSpacing.Space12),
        color = CoulombTheme.colors.textSecondary,
        style = CoulombTheme.typography.body,
    )
}

/** [number] in its unit's per-hour template, split around the number so the parts can be styled apart. */
@Composable
internal fun rate(
    unit: RateUnit,
    number: String,
): Affixed {
    val text = stringResource(unit.template, number)
    val at = text.indexOf(number)
    return if (at < 0) Affixed("", text, "") else Affixed(text.take(at), number, text.drop(at + number.length))
}

/** The per-hour template of a unit: "%1$s mAh/h", "%1$s min/h", "%1$s/h". */
@get:StringRes
internal val RateUnit.template: Int
    get() =
        when (this) {
            RateUnit.MAH -> R.string.drain_compare_rate_mah
            RateUnit.MINUTES -> R.string.drain_compare_rate_minutes
            RateUnit.COUNT -> R.string.drain_compare_rate_count
        }

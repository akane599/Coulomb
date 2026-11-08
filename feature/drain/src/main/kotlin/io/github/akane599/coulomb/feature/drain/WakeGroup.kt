// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.AlarmEntry
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.WakeEntry
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombTerm
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.format.Formats

/** The Wakeups lens's groups, in order (SPEC 10.3), each with its SPEC 19.3 glossary line. */
internal enum class WakeGroup(
    @param:StringRes val title: Int,
    @param:StringRes val definition: Int,
) {
    PARTIAL(R.string.drain_wake_partial, R.string.drain_wake_partial_definition),
    KERNEL(R.string.drain_wake_kernel, R.string.drain_wake_kernel_definition),
    REASONS(R.string.drain_wake_reasons, R.string.drain_wake_reasons_definition),
}

/** One group of the Wakeups lens: its records, and how much of the window their source covered. */
@Immutable
internal data class WakeGroupView(
    val group: WakeGroup,
    val entries: List<WakeEntry>,
    val coverage: Coverage,
)

/**
 * The wake records of [report] by group, longest held first, then most frequent. S9 wakeup sources
 * overlap S5's kernel wakelocks and S27 resume causes overlap wakeup reasons, so each group shows one
 * source: S5's where it has rows, the other otherwise (`WakeKind`). Every group is listed, with the
 * coverage of the source it shows; an empty group takes the better of its two sources' coverage, so
 * it says "none" only where a source covered the window (ADR-0028 §3).
 */
internal fun wakeGroups(report: DrainReport): List<WakeGroupView> {
    val byKind = report.wakes.groupBy { it.kind }
    val cover = report.wakeCoverage

    fun of(
        group: WakeGroup,
        first: Pair<WakeKind, Coverage>,
        second: Pair<WakeKind, Coverage>? = null,
    ): WakeGroupView {
        val (kind, coverage) =
            listOfNotNull(first, second).firstOrNull { !byKind[it.first].isNullOrEmpty() }
                // Coverage levels are declared from none to whole.
                ?: (first.first to maxOf(first.second, second?.second ?: Coverage.NONE))
        val entries =
            byKind[kind]
                .orEmpty()
                .sortedWith(compareByDescending<WakeEntry> { it.totalMs }.thenByDescending { it.count })
        return WakeGroupView(group, entries, coverage)
    }
    return listOf(
        of(WakeGroup.PARTIAL, WakeKind.PARTIAL to cover.batteryStats),
        of(WakeGroup.KERNEL, WakeKind.KERNEL to cover.batteryStats, WakeKind.WAKEUP_SOURCE to cover.wakeupSources),
        of(WakeGroup.REASONS, WakeKind.WAKEUP_REASON to cover.batteryStats, WakeKind.RESUME_CAUSE to cover.kernelLog),
    )
}

/**
 * The Wakeups lens as list items: the caption, then each group's heading and rows, each row keyed by
 * its record (SPEC 15). When no source covered the window, one line says so.
 */
internal fun LazyListScope.wakeupItems(
    report: DrainReport,
    formats: Formats,
    lists: ListState,
) {
    val groups = wakeGroups(report)
    // S5 counts the wakeup alarms (UidKeys.ALARM_WAKEUPS).
    val alarmsCoverage = report.wakeCoverage.batteryStats
    val coverages = groups.map { it.coverage } + alarmsCoverage
    val empty = groups.all { it.entries.isEmpty() } && report.alarms.isEmpty()
    val only =
        when {
            coverages.all { it == Coverage.NONE } -> R.string.drain_wake_not_collected
            empty && coverages.all { it == Coverage.WHOLE } -> R.string.drain_wake_none
            else -> null
        }
    if (only != null) {
        item(key = "wake-only", contentType = "note") {
            Text(
                stringResource(only),
                Modifier.padding(top = CoulombSpacing.Space16),
                color = CoulombTheme.colors.textSecondary,
                style = CoulombTheme.typography.body,
            )
        }
        return
    }
    item(key = "wake-caption", contentType = "note") {
        Text(
            stringResource(R.string.drain_wakeups_caption),
            Modifier.padding(top = CoulombSpacing.Space16),
            color = CoulombTheme.colors.textSecondary,
            style = CoulombTheme.typography.caption,
        )
    }
    groups.forEach { view ->
        group(
            key = view.group.name,
            head = { GroupHead(stringResource(view.group.title), stringResource(view.group.definition)) },
            count = view.entries.size,
            coverage = view.coverage,
            open = lists.openGroups and (1 shl view.group.ordinal) != 0,
            toggle = { lists.toggleGroup(view.group.ordinal) },
            formats = formats,
            rowKey = { view.entries[it].let { e -> "wake-${e.kind}-${e.uid}-${e.tag}" } },
        ) { first, index -> WakeRow(view.entries[index], first, formats) }
    }
    group(
        key = "ALARMS",
        head = { GroupHead(stringResource(R.string.drain_wake_alarms), definition = null) },
        count = report.alarms.size,
        coverage = alarmsCoverage,
        open = lists.openGroups and (1 shl ALARMS_BIT) != 0,
        toggle = { lists.toggleGroup(ALARMS_BIT) },
        formats = formats,
        rowKey = { "alarm-${report.alarms[it].uid}" },
    ) { first, index -> AlarmRow(report.alarms[index], first, formats) }
}

/**
 * A titled group: its heading, then the first [SHOWN] rows, then "Show all" for the rest and "Show
 * fewer" once they are out. An empty group says whether its source covered the window; a partly
 * covered one says its counts may be low.
 */
@Suppress("LongParameterList") // One group's inputs.
private fun LazyListScope.group(
    key: String,
    head: @Composable () -> GroupHead,
    count: Int,
    coverage: Coverage,
    open: Boolean,
    toggle: () -> Unit,
    formats: Formats,
    rowKey: (Int) -> String,
    row: @Composable (first: Boolean, index: Int) -> Unit,
) {
    item(key = "group-$key", contentType = "group-head") {
        val colors = CoulombTheme.colors
        val type = CoulombTheme.typography
        Column(rowMotion().padding(top = CoulombSpacing.Space24, bottom = if (count > 0) CoulombSpacing.Space12 else 0.dp)) {
            GroupHeading(head())
            if (count == 0) {
                val text =
                    when (coverage) {
                        Coverage.NONE -> R.string.drain_wake_group_not_collected
                        Coverage.PARTIAL -> R.string.drain_wake_group_none_partial
                        Coverage.WHOLE -> R.string.drain_wake_group_none
                    }
                Text(stringResource(text), Modifier.padding(top = CoulombSpacing.Space8), color = colors.textSecondary, style = type.body)
            } else if (coverage == Coverage.PARTIAL) {
                Text(
                    stringResource(R.string.drain_wake_group_partial),
                    Modifier.padding(top = CoulombSpacing.Space4),
                    color = colors.textSecondary,
                    style = type.caption,
                )
            }
        }
    }
    val shown = if (open) count else minOf(count, SHOWN)
    items(shown, key = rowKey, contentType = { "record" }) { index ->
        Box(rowMotion()) { row(index == 0, index) }
    }
    if (count > SHOWN) {
        item(key = "group-$key-more", contentType = "button") {
            CoulombTextButton(
                text =
                    if (open) {
                        stringResource(
                            R.string.drain_show_fewer,
                        )
                    } else {
                        stringResource(R.string.drain_show_all, formats.integer(count.toLong()))
                    },
                onClick = toggle,
                modifier = rowMotion().padding(top = CoulombSpacing.Space4),
                edge = ButtonEdge.Start,
            )
        }
    }
}

/** A group's heading: its name, and the glossary line that explains it where SPEC 19.3 has one. */
private class GroupHead(
    val title: String,
    val definition: String?,
)

/**
 * A list-group heading in Label (DESIGN.md), the name as a glossary term where it has one, and the
 * Measured mark that covers every value in the group: each group has one source (DESIGN.md,
 * Provenance marks).
 */
@Composable
private fun GroupHeading(head: GroupHead) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Row(Modifier.semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
        if (head.definition != null) {
            CoulombTerm(head.title, head.definition, stringResource(R.string.drain_term_action), type.label, colors.textSecondary)
        } else {
            Text(head.title, color = colors.textSecondary, style = type.label)
        }
        ProvenanceMark(Provenance.MEASURED)
    }
}

/** A wakelock, kernel source or wakeup reason: its holder or name, how often, and the time held. */
@Composable
private fun WakeRow(
    entry: WakeEntry,
    first: Boolean,
    formats: Formats,
) {
    val resources = LocalResources.current
    val count = formats.integer(entry.count)
    val times = pluralStringResource(R.plurals.drain_times, entry.count.toQuantity(), count)
    val partial = entry.kind == WakeKind.PARTIAL && entry.uid != null
    val name = if (partial) formats.appName(entry.label, entry.uid ?: 0) else entry.tag
    val woke = entry.wakeups?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.drain_woke, it.toQuantity(), formats.integer(it)) }
    val screenOff = entry.screenOffShare?.let { stringResource(R.string.drain_screen_off_share, formats.share(it)) }
    // A record with no time held, such as a resume cause, is ranked and shown by its count.
    val held = entry.totalMs > 0
    val facts =
        listOfNotNull(if (held) times else null, screenOff, woke)
            .reduceOrNull { a, b -> resources.getString(R.string.drain_detail_separator, a, b) }
    RecordRow(name, if (partial) entry.tag else null, facts, if (held) formats.held(entry.totalMs) else times, first, rawName = !partial)
}

@Composable
private fun AlarmRow(
    entry: AlarmEntry,
    first: Boolean,
    formats: Formats,
) {
    val value = pluralStringResource(R.plurals.drain_alarm_wakeups, entry.wakeups.toQuantity(), formats.integer(entry.wakeups))
    RecordRow(formats.appName(entry.label, entry.uid), null, null, value, first)
}

/**
 * A counter row: the name in Body name (in Mono when it is a kernel identifier, [rawName]), a
 * wakelock's [tag] in Mono and the [facts] in Caption, and the
 * [value] at the end, its mark on the group's heading (DESIGN.md, Rows, without an icon or share bar: times held do not add
 * up to a whole). Only the tag, an identifier that can run to a hundred characters, is cut at two
 * lines; the name and the facts wrap at any text size.
 */
@Composable
private fun RecordRow(
    name: String,
    tag: String?,
    facts: String?,
    value: String,
    first: Boolean,
    rawName: Boolean = false,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = Hairline.toPx()
                if (first) drawLine(colors.hairline, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
                drawLine(colors.hairline, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            }.semantics(mergeDescendants = true) {}
            .heightIn(min = RowHeight)
            .padding(vertical = CoulombSpacing.Space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = colors.textPrimary, style = if (rawName) type.mono else type.bodyName)
            if (tag != null) Text(tag, color = colors.textSecondary, style = type.mono, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (facts != null) Text(facts, color = colors.textSecondary, style = type.caption)
        }
        // The group's heading carries the value's Measured mark.
        Text(value, Modifier.widthIn(max = ValueMax), color = colors.textPrimary, style = type.data.copy(textAlign = TextAlign.End))
    }
}

/** A count as a plural quantity; counts past Int's range take its largest. */
private fun Long.toQuantity(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** Rows shown before "Show all" (SPEC 10.3: top five). */
private const val SHOWN = 5

private val Hairline = 1.dp
private val RowHeight = 56.dp
private val ValueMax = 160.dp

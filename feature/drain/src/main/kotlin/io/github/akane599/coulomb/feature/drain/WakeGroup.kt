// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.AlarmEntry
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.WakeEntry
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombSectionHead
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.ui.MarkedText
import io.github.akane599.coulomb.core.ui.format.Formats

/** The Wakeups lens's groups, in order (SPEC 10.3). */
internal enum class WakeGroup(
    @param:StringRes val title: Int,
) {
    PARTIAL(R.string.drain_wake_partial),
    KERNEL(R.string.drain_wake_kernel),
    REASONS(R.string.drain_wake_reasons),
}

/**
 * The wake records of [report] by group, longest held first, then most frequent. S9 wakeup sources
 * overlap S5's kernel wakelocks and S27 resume causes overlap wakeup reasons, so each group shows one
 * source: S5's where it has rows, the other otherwise (`WakeKind`). Empty groups are left out.
 */
internal fun wakeGroups(report: DrainReport): List<Pair<WakeGroup, List<WakeEntry>>> {
    val byKind = report.wakes.groupBy { it.kind }

    fun of(vararg kinds: WakeKind) =
        kinds
            .firstNotNullOfOrNull { byKind[it]?.takeIf(List<WakeEntry>::isNotEmpty) }
            .orEmpty()
            .sortedWith(compareByDescending<WakeEntry> { it.totalMs }.thenByDescending { it.count })
    return listOf(
        WakeGroup.PARTIAL to of(WakeKind.PARTIAL),
        WakeGroup.KERNEL to of(WakeKind.KERNEL, WakeKind.WAKEUP_SOURCE),
        WakeGroup.REASONS to of(WakeKind.WAKEUP_REASON, WakeKind.RESUME_CAUSE),
    ).filter { it.second.isNotEmpty() }
}

@Composable
internal fun WakeupsLens(
    report: DrainReport,
    formats: Formats,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val groups = wakeGroups(report)
    Column(Modifier.padding(top = CoulombSpacing.Space16)) {
        if (groups.isEmpty() && report.alarms.isEmpty()) {
            Text(stringResource(R.string.drain_wake_none), color = colors.textSecondary, style = type.body)
            return@Column
        }
        Text(stringResource(R.string.drain_wakeups_caption), color = colors.textSecondary, style = type.caption)
        groups.forEach { (group, entries) ->
            Group(stringResource(group.title), entries.size, report, formats) { first, entry -> WakeRow(entries[entry], first, formats) }
        }
        if (report.alarms.isNotEmpty()) {
            Group(stringResource(R.string.drain_wake_alarms), report.alarms.size, report, formats) { first, entry ->
                AlarmRow(report.alarms[entry], first, formats)
            }
        }
    }
}

/** A titled group of rows: the first [SHOWN], then "Show all" for the rest. */
@Composable
private fun Group(
    title: String,
    count: Int,
    report: DrainReport,
    formats: Formats,
    row: @Composable (first: Boolean, index: Int) -> Unit,
) {
    var all by rememberSaveable(report.span, title) { mutableStateOf(false) }
    Column(Modifier.padding(top = CoulombSpacing.Space24)) {
        CoulombSectionHead(title)
        Column(Modifier.padding(top = CoulombSpacing.Space12)) {
            val shown = if (all) count else minOf(count, SHOWN)
            repeat(shown) { row(it == 0, it) }
        }
        if (!all && count > SHOWN) {
            CoulombTextButton(
                text = stringResource(R.string.drain_show_all, formats.integer(count.toLong())),
                onClick = { all = true },
                modifier = Modifier.padding(top = CoulombSpacing.Space4),
                edge = ButtonEdge.Start,
            )
        }
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
    RecordRow(name, if (partial) entry.tag else null, facts, if (held) formats.held(entry.totalMs) else times, first)
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
 * A counter row: the name in Body name, a wakelock's [tag] and the [facts] in Caption, and the
 * Measured [value] at the end (DESIGN.md, Rows, without an icon or share bar: times held do not add
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
            Text(name, color = colors.textPrimary, style = type.bodyName)
            if (tag != null) Text(tag, color = colors.textSecondary, style = type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (facts != null) Text(facts, color = colors.textSecondary, style = type.caption)
        }
        MarkedText(
            value,
            Provenance.MEASURED,
            type.data.copy(textAlign = TextAlign.End),
            colors.textPrimary,
            Modifier.widthIn(max = ValueMax),
        )
    }
}

/** A count as a plural quantity; counts past Int's range take its largest. */
private fun Long.toQuantity(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** Rows shown before "Show all" (SPEC 10.3: top five). */
private const val SHOWN = 5

private val Hairline = 1.dp
private val RowHeight = 56.dp
private val ValueMax = 160.dp

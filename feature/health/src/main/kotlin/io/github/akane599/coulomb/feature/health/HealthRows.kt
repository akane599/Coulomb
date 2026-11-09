// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.analytics.StressLevel
import io.github.akane599.coulomb.core.data.ChargeHabits
import io.github.akane599.coulomb.core.data.ChargeSessionRow
import io.github.akane599.coulomb.core.data.Charger
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigure
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigureStrip
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.component.ShareBar
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.FigureText
import io.github.akane599.coulomb.core.ui.NameBesideValue
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.affixed
import io.github.akane599.coulomb.core.ui.format.Affixed
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import io.github.akane599.coulomb.core.ui.page.StandIn
import io.github.akane599.coulomb.core.ui.page.Subhead
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Charging habits (SPEC 10.8 item 5, ADR-0044 §2) over the last 30 days: depth of discharge, time
 * at 80% or more a day and the charge temperature as figures, then the sessions by wear stress.
 */
@Composable
internal fun Habits(
    habits: ChargeHabits?,
    formats: Formats,
    first: Boolean = false,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.health_habits), provenance = null, first = first) {
            Text(stringResource(R.string.health_habits_window), color = colors.textSecondary, style = type.caption)
        }
        if (habits == null) {
            StandIn(stringResource(R.string.health_no_habits), top = 0.dp)
            return@Column
        }
        CoulombFigureStrip {
            CoulombFigure(stringResource(R.string.health_depth), stringResource(R.string.health_depth_note)) {
                Figure(formats.percentParts(habits.depthPct.roundToInt()))
            }
            val high = habits.highMinutesPerDay
            CoulombFigure(
                stringResource(R.string.health_high, formats.percent(HIGH_LEVEL)),
                stringResource(if (high != null) R.string.health_high_note else R.string.health_high_unknown),
            ) {
                if (high != null) {
                    Text(formats.duration(high.roundToLong()), color = colors.textPrimary, style = type.title)
                    ProvenanceMark(Provenance.DERIVED)
                } else {
                    Text(DASH, color = colors.textSecondary, style = type.title)
                }
            }
            habits.chargeTempDeciC?.let { temp ->
                CoulombFigure(stringResource(R.string.health_charge_temp), stringResource(R.string.health_charge_temp_note)) {
                    Figure(formats.celsius(temp).affixed())
                }
            }
        }
        val counted = habits.stress.values.sum()
        if (counted > 0) {
            Subhead(stringResource(R.string.health_stress), definition = stringResource(R.string.health_stress_meaning))
            StressLevel.entries.forEachIndexed { index, level ->
                val count = habits.stress[level] ?: 0
                DataRow(
                    name = stringResource(name(level)),
                    detail = null,
                    value = pluralStringResource(R.plurals.health_sessions_count, count, formats.integer(count.toLong())),
                    mark = null,
                    share = count.toDouble() / counted,
                    first = index == 0,
                )
            }
        }
    }
}

/** A figure in the strip with its unit set apart and the Derived mark. */
@Composable
private fun Figure(figure: Affixed) {
    val type = CoulombTheme.typography
    FigureText(figure, numberStyle = type.title, unitStyle = type.label)
    ProvenanceMark(Provenance.DERIVED)
}

private const val DASH = "–"

/** The level the habits count time at or above (ADR-0044 §2). */
internal const val HIGH_LEVEL = 80

/**
 * The latest charge sessions (SPEC 10.8 item 6): when each started, its levels, length, cycles
 * and stress, the points gained with a share bar; a tap opens the session. "See all" opens every
 * session where there are more.
 */
@Composable
internal fun Sessions(
    sessions: List<ChargeSessionRow>,
    more: Boolean,
    selected: Long?,
    nowMs: Long,
    zone: ZoneId,
    formats: Formats,
    onOpen: (Long) -> Unit,
    onSeeAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        val action: (@Composable () -> Unit)? =
            if (more) {
                { CoulombTextButton(stringResource(R.string.health_see_all), onSeeAll, edge = ButtonEdge.End) }
            } else {
                null
            }
        SectionTitle(stringResource(R.string.health_sessions), Provenance.MODELED.takeIf { sessions.isNotEmpty() }, action = action)
        if (sessions.isEmpty()) {
            StandIn(stringResource(R.string.health_no_sessions), top = 0.dp)
            return@Column
        }
        sessions.forEachIndexed { index, row ->
            SessionRow(row, index == 0, row.id == selected, nowMs, zone, formats) { onOpen(row.id) }
        }
    }
}

/** One charge session as a row (lab `.data-row`); also the rows of the full list. */
@Composable
internal fun SessionRow(
    row: ChargeSessionRow,
    first: Boolean,
    selected: Boolean,
    nowMs: Long,
    zone: ZoneId,
    formats: Formats,
    onClick: () -> Unit,
) {
    val gained = (row.toPct - row.fromPct).coerceAtLeast(0)
    DataRow(
        name = startedAt(row.startWallMs, nowMs, zone, formats),
        detail = sessionLine(row, formats),
        value = gainedText(gained, formats),
        mark = null,
        share = gained / FULL,
        first = first,
        selected = selected,
        onClick = onClick,
    )
}

/** "+62%"; Turkish "+%62", the sign kept before its figure in a right-to-left layout. */
private fun gainedText(
    points: Int,
    formats: Formats,
): String = formats.signedShare(points / FULL)

private const val FULL = 100.0

/**
 * Chargers (SPEC 10.8 item 7): each profile's name, when it was last seen and its baseline power
 * with a bar against the strongest, and Rename.
 */
@Composable
internal fun Chargers(
    chargers: List<Charger>,
    nowMs: Long,
    zone: ZoneId,
    formats: Formats,
    onRename: (Charger) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.health_chargers), provenance = null)
        if (chargers.isEmpty()) {
            StandIn(stringResource(R.string.health_no_chargers), top = 0.dp)
            return@Column
        }
        val strongest = chargers.maxOf { it.baselineW }.takeIf { it > 0 } ?: 1.0
        chargers.forEachIndexed { index, charger ->
            val name = chargerName(charger)
            DataRow(
                name = name,
                detail = stringResource(R.string.health_charger_line, lastSeen(charger.lastSeenWallMs, nowMs, zone, formats)),
                value = formats.watts(charger.baselineW).joined(),
                mark = Provenance.DERIVED,
                share = charger.baselineW / strongest,
                first = index == 0,
                action = {
                    val spoken = stringResource(R.string.health_rename_named, name)
                    CoulombTextButton(
                        stringResource(R.string.health_rename),
                        { onRename(charger) },
                        Modifier.semantics { contentDescription = spoken },
                        edge = ButtonEdge.End,
                    )
                },
            )
        }
    }
}

/**
 * A row with a share bar without an icon (DESIGN.md, Rows with share bars; lab `.data-row`): the
 * name in Body 500 with [detail] in Caption under it, the figure at the end with its mark, an
 * optional [action] under the figure, the 4 dp bar across where there is a [share]. Hairlines divide rows; the [first] row
 * draws one above itself too. A row with [onClick] opens something; TalkBack reads it as one item.
 */
@Composable
@Suppress("LongParameterList") // One row's content.
internal fun DataRow(
    name: String,
    detail: String?,
    value: String,
    mark: Provenance?,
    share: Double?,
    first: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                if (selected) {
                    val bleed = CoulombSpacing.Space8.toPx()
                    drawRoundRect(
                        colors.surfaceRaised,
                        topLeft = Offset(-bleed, 0f),
                        size = Size(size.width + bleed * 2, size.height),
                        cornerRadius = CornerRadius(SelectedCorner.toPx()),
                    )
                }
                val stroke = 1.dp.toPx()
                if (first) drawLine(colors.hairline, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
                drawLine(colors.hairline, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            }.then(clickable)
            .semantics(mergeDescendants = action == null) { if (selected) this.selected = true }
            .heightIn(min = RowMinHeight)
            .padding(vertical = CoulombSpacing.Space12),
    ) {
        NameBesideValue(
            name = {
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(name, color = colors.textPrimary, style = type.bodyName)
                    detail?.let { Text(it, color = colors.textSecondary, style = type.caption) }
                }
            },
            value = {
                Column(horizontalAlignment = Alignment.End) {
                    Row(
                        Modifier.semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(value, color = colors.textPrimary, style = type.data, textAlign = TextAlign.End)
                        mark?.let { ProvenanceMark(it) }
                    }
                    action?.invoke()
                }
            },
        )
        share?.let { ShareBar(it.toFloat(), Modifier.padding(top = CoulombSpacing.Space8)) }
    }
}

private val RowMinHeight = 64.dp
private val SelectedCorner = 12.dp

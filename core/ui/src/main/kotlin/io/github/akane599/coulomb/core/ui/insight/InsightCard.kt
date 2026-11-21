// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.insight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombFilledButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombIconButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.component.hangVertically
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.ui.R

/** What the user can do with an insight from its card (SPEC 9.4). */
public class InsightCardActions(
    public val why: () -> Unit,
    public val snoozeDay: () -> Unit,
    public val snoozeWeek: () -> Unit,
    public val dismiss: () -> Unit,
)

/** The card's primary action: a button that names what it does (SPEC 11.10). */
public class InsightAction(
    public val label: String,
    public val run: () -> Unit,
)

private val ChipHeight = 28.dp
private val ChipPadding = 10.dp

/** The 48 dp overflow target takes the chip's 28 dp in the head, 10 dp hanging out above and below. */
private val OverflowHang = 10.dp

/** About 70 characters of Body. */
private val TextMeasure = 560.dp

/** A severity as a chip: Act is `critical`, Watch `caution`, Info `text-primary` on `surface-raised` (DESIGN.md, Chips). */
@Composable
public fun SeverityChip(
    severity: InsightSeverity,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    val (background, content, label) =
        when (severity) {
            InsightSeverity.ACT -> Triple(colors.critical, colors.onCritical, R.string.insight_severity_act)
            InsightSeverity.WATCH -> Triple(colors.caution, colors.onCaution, R.string.insight_severity_watch)
            InsightSeverity.INFO -> Triple(colors.surfaceRaised, colors.textPrimary, R.string.insight_severity_info)
        }
    Box(
        modifier
            // At least 28 dp: large text makes the chip taller instead of cutting its label.
            .heightIn(min = ChipHeight)
            .clip(RoundedCornerShape(CoulombRadius.Chip))
            .background(background)
            .padding(horizontal = ChipPadding, vertical = CoulombSpacing.Space4),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(label), color = content, style = CoulombTheme.typography.label, maxLines = 1, softWrap = false)
    }
}

/**
 * An insight as a card (DESIGN.md, Insight cards): the severity and an overflow menu in the head,
 * what happened, what can be done, then the action and "Why am I seeing this?". A card because an
 * insight moves as a unit; nothing else on a page is one (SPEC 11.5).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun InsightCard(
    severity: InsightSeverity,
    text: InsightText,
    actions: InsightCardActions,
    modifier: Modifier = Modifier,
    action: InsightAction? = null,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CoulombRadius.Row))
            .background(colors.surface)
            .padding(
                start = CoulombSpacing.Space16,
                top = CoulombSpacing.Space16,
                end = CoulombSpacing.Space16,
                bottom = CoulombSpacing.Space8,
            ),
    ) {
        // The chip sets the head's height; the 48 dp overflow target hangs into the card's padding.
        Row(Modifier.fillMaxWidth().heightIn(min = ChipHeight), verticalAlignment = Alignment.CenterVertically) {
            SeverityChip(severity)
            Spacer(Modifier.weight(1f))
            Overflow(actions, text.title, Modifier.hangVertically(OverflowHang).offset(x = CoulombSpacing.Space12))
        }
        // On a wide window the card is wider than a line should be long (SPEC 11.3).
        Text(
            text.title,
            Modifier.widthIn(max = TextMeasure).padding(top = CoulombSpacing.Space12).semantics { heading() },
            color = colors.textPrimary,
            style = type.bodySubhead,
        )
        Text(
            text.body,
            Modifier.widthIn(max = TextMeasure).padding(top = CoulombSpacing.Space4),
            color = colors.textSecondary,
            style = type.body,
        )
        FlowRow(
            Modifier.padding(top = CoulombSpacing.Space8),
            horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (action != null) CoulombFilledButton(action.label, action.run)
            // Alone, the question starts where the text above it starts; after a button it keeps its own padding.
            CoulombTextButton(
                stringResource(R.string.insight_why),
                actions.why,
                edge = if (action == null) ButtonEdge.Start else ButtonEdge.None,
            )
        }
    }
}

@Composable
private fun Overflow(
    actions: InsightCardActions,
    title: String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        CompositionLocalProvider(LocalContentColor provides CoulombTheme.colors.textSecondary) {
            // Named with its card: with two cards, "More options" twice says nothing.
            CoulombIconButton(CoulombIcons.MoreVert, stringResource(R.string.insight_more_for, title), onClick = { open = true })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((label, run) in listOf(
                R.string.insight_snooze_day to actions.snoozeDay,
                R.string.insight_snooze_week to actions.snoozeWeek,
                R.string.insight_dismiss to actions.dismiss,
            )) {
                DropdownMenuItem(
                    text = { Text(stringResource(label), style = CoulombTheme.typography.body) },
                    onClick = {
                        open = false
                        run()
                    },
                )
            }
        }
    }
}

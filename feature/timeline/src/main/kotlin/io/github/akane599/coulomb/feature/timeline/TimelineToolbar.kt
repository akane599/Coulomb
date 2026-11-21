// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.TimelineTrack
import io.github.akane599.coulomb.core.designsystem.component.CoulombIconButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombSwitchRow
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter

private val Target = 48.dp
private val RangeRadius = 24.dp
private val RangePadding = 10.dp
private val CheckSize = 18.dp
private val SeparatorHeight = 24.dp
private val ButtonGap = 2.dp
private val FloatNear = 6.dp
private val FloatFar = 24.dp

/** The toolbar's range presets by length; each resource names one of [TimeWindow.PRESETS_MS]. */
private val RANGE_LABELS =
    listOf(R.string.timeline_range_15_min, R.string.timeline_range_6_h, R.string.timeline_range_24_h, R.string.timeline_range_7_d)

/**
 * The Timeline's floating toolbar (DESIGN.md, Components): the range presets, the chosen one
 * filled with a check, then the tracks and "Now" behind a hairline. The only element that floats
 * over content. At large text the ranges scroll inside it, so the tracks and "Now" stay in reach.
 */
@Composable
internal fun TimelineToolbar(
    spanMs: Long,
    onRange: (Long) -> Unit,
    onTracks: () -> Unit,
    onNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    val shape = RoundedCornerShape(CoulombRadius.Large)
    val name = stringResource(R.string.timeline_toolbar)
    Row(
        modifier
            // Two shadows, near and far (DESIGN.md, Shadow Vocabulary: Floating).
            .shadow(FloatFar, shape, clip = false)
            .shadow(FloatNear, shape, clip = false)
            .background(colors.surfaceRaised, shape)
            .padding(CoulombSpacing.Space4)
            .semantics { contentDescription = name },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ButtonGap),
    ) {
        Row(
            Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(ButtonGap),
        ) {
            TimeWindow.PRESETS_MS.forEachIndexed { index, preset ->
                RangeButton(stringResource(RANGE_LABELS[index]), chosen = spanMs == preset) { onRange(preset) }
            }
        }
        Box(
            Modifier
                .padding(horizontal = CoulombSpacing.Space4)
                .width(1.dp)
                .height(SeparatorHeight)
                .background(colors.hairline),
        )
        CoulombIconButton(CoulombIcons.Layers, stringResource(R.string.timeline_choose_tracks), onTracks)
        CoulombTextButton(stringResource(R.string.timeline_now), onNow)
    }
}

@Composable
private fun RangeButton(
    text: String,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    val colors = CoulombTheme.colors
    val content = if (chosen) colors.onAccentFill else colors.textPrimary
    Row(
        Modifier
            .defaultMinSize(minWidth = Target, minHeight = Target)
            .clip(RoundedCornerShape(RangeRadius))
            .background(if (chosen) colors.accentFill else Color.Transparent)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = RangePadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space4, Alignment.CenterHorizontally),
    ) {
        if (chosen) Icon(painterResource(CoulombIcons.Check), contentDescription = null, Modifier.size(CheckSize), tint = content)
        Text(
            text,
            color = content,
            style = CoulombTheme.typography.label.copy(fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium),
            maxLines = 1,
        )
    }
}

/** The tracks to show, one switch each (SPEC 10.6: each band toggleable). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TracksSheet(
    shown: Set<TimelineTrack>,
    onSet: (TimelineTrack, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CoulombTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = CoulombRadius.Sheet, topEnd = CoulombRadius.Sheet),
        containerColor = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = currentGutter(), end = currentGutter(), bottom = CoulombSpacing.Space24),
        ) {
            Text(
                stringResource(R.string.timeline_tracks_title),
                Modifier.padding(bottom = CoulombSpacing.Space8).semantics { heading() },
                color = colors.textPrimary,
                style = CoulombTheme.typography.title,
            )
            TimelineTrack.entries.forEach { track ->
                CoulombSwitchRow(stringResource(track.label), checked = track in shown, onCheckedChange = { onSet(track, it) })
            }
        }
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.TimelineTrack
import io.github.akane599.coulomb.core.data.TrackSpan
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.page.InlineTable
import io.github.akane599.coulomb.core.ui.page.TableRow
import java.time.ZoneId

/** The names table of a run of marks shows this many rows before "Show all". */
private const val SHOWN = 5

/** A tapped mark's details in a bottom sheet (SPEC 10.6), on widths without the side panel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // What was tapped, the names and formats to say it with, and where it leads.
internal fun EventSheet(
    picked: Picked,
    labels: Map<Int, String>,
    zone: ZoneId,
    formats: Formats,
    onOpenApp: (Int) -> Unit,
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
            EventDetails(picked, labels, zone, formats) { uid ->
                onDismiss()
                onOpenApp(uid)
            }
        }
    }
}

/**
 * What a tap landed on: one stretch with its times, its length and who or what it was, or a run of
 * stretches too close to tell apart, with their count, their time in all and the names behind them.
 * A stretch that belongs to an app links to that app.
 */
@Composable
internal fun EventDetails(
    picked: Picked,
    labels: Map<Int, String>,
    zone: ZoneId,
    formats: Formats,
    onOpenApp: (Int) -> Unit,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val one = picked.spans.singleOrNull()
    Column(Modifier.fillMaxWidth()) {
        Text(title(picked.track, one), Modifier.semantics { heading() }, color = colors.textPrimary, style = type.title)
        if (one != null) {
            Text(
                stringResource(
                    R.string.timeline_event_one,
                    formats.clockWithSeconds(one.fromWallMs, zone),
                    formats.clockWithSeconds(one.toWallMs, zone),
                    formats.held(one.durationMs),
                ),
                Modifier.padding(top = CoulombSpacing.Space4),
                color = colors.textSecondary,
                style = type.body,
            )
            val uid = one.uid
            val app = uid?.let { formats.appName(labels[it], it) }
            one.tag?.let { tag ->
                Text(
                    nameLabel(picked.track),
                    Modifier.padding(top = CoulombSpacing.Space16),
                    color = colors.textSecondary,
                    style = type.label,
                )
                Text(tag, color = colors.textPrimary, style = type.mono)
            }
            if (uid != null && app != null) {
                CoulombTextButton(
                    stringResource(R.string.timeline_event_open_app, app),
                    { onOpenApp(uid) },
                    Modifier.padding(top = CoulombSpacing.Space8),
                    trailingIcon = CoulombIcons.ChevronRight,
                    edge = ButtonEdge.Start,
                )
            }
        } else {
            Text(
                pluralStringResource(
                    R.plurals.timeline_event_many,
                    picked.spans.size,
                    formats.integer(picked.spans.size.toLong()),
                    formats.clock(picked.fromMs, zone),
                    formats.clock(picked.toMs, zone),
                    formats.held(picked.totalMs),
                ),
                Modifier.padding(top = CoulombSpacing.Space4),
                color = colors.textSecondary,
                style = type.body,
            )
            val names = picked.names()
            if (names.isNotEmpty()) {
                InlineTable(
                    columns = listOf(nameLabel(picked.track), stringResource(R.string.night_column_count)),
                    rows =
                        names.map { (name, count) ->
                            TableRow(name = name, values = listOf(formats.integer(count.toLong())), mono = true)
                        },
                    modifier = Modifier.padding(top = CoulombSpacing.Space12),
                    shown = SHOWN,
                )
            }
        }
    }
}

/** The track's name; one Doze stretch says which Doze. */
@Composable
private fun title(
    track: TimelineTrack,
    one: TrackSpan?,
): String =
    when {
        track == TimelineTrack.DOZE && one?.value == TrackSpan.DOZE_LIGHT -> stringResource(R.string.timeline_doze_light)
        track == TimelineTrack.DOZE && one?.value == TrackSpan.DOZE_DEEP -> stringResource(R.string.timeline_doze_deep)
        else -> stringResource(track.label)
    }

/** What a stretch's name is on this track: the reason the phone woke, a wakelock's name, an app's package. */
@Composable
private fun nameLabel(track: TimelineTrack): String =
    stringResource(
        when (track) {
            TimelineTrack.AWAKE -> R.string.night_column_reason
            TimelineTrack.WAKELOCKS -> R.string.night_column_wakelock
            TimelineTrack.TOP_APP -> R.string.night_column_app
            else -> R.string.timeline_event_name
        },
    )

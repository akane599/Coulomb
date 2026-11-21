// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.dumpsys.PowerDump
import io.github.akane599.coulomb.core.model.dumpsys.WakeLockLevel
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.InlineTable
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.Subhead
import io.github.akane599.coulomb.core.ui.page.TableRow

/** One partial wakelock held now: whose work it is, its tag and how long it has been held. */
@Immutable
internal data class HeldRow(
    val uid: Int,
    val tag: String,
    val heldMs: Long?,
    val long: Boolean,
    val ignored: Boolean,
)

/** What the sheet shows of S12: the partial wakelocks, longest first, and battery saver. */
@Immutable
internal data class HeldNow(
    val rows: List<HeldRow>,
    /** Null when the dump did not say, or the list could not be trusted. */
    val listed: Boolean,
    val saverOn: Boolean?,
) {
    companion object {
        /**
         * Only partial wakelocks keep the processor awake with the screen off; the screen's own
         * locks, Coulomb's among them while it is open, are not the question here. The work source,
         * where there is one, is the app the work is done for.
         */
        fun of(dump: PowerDump): HeldNow =
            HeldNow(
                rows =
                    dump.wakeLocks
                        .orEmpty()
                        .filter { it.level == WakeLockLevel.PARTIAL }
                        .map { HeldRow(it.workSourceUid ?: it.uid, it.tag, it.heldUptimeMs, it.long, it.disabled) }
                        .sortedByDescending { it.heldMs ?: 0 },
                listed = dump.wakeLocks != null,
                saverOn = dump.batterySaver?.enabled,
            )
    }
}

/** The Wakeups lens's way to the wakelocks held right now (S12, on demand). */
internal fun LazyListScope.heldNowItem(onOpen: () -> Unit) {
    item(key = "wake-held-now", contentType = "action") {
        CoulombTextButton(
            stringResource(R.string.drain_held_now_open),
            onOpen,
            Modifier.padding(top = CoulombSpacing.Space8),
            edge = ButtonEdge.Start,
        )
    }
}

/**
 * Reads S12 once when it opens, and again on Read again. Nothing is kept: a tag is the app's own
 * text and may be personal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HeldNowSheet(
    read: suspend () -> PowerDump?,
    name: @Composable (uid: Int) -> String,
    onDismiss: () -> Unit,
) {
    val colors = CoulombTheme.colors
    val formats = rememberFormats()
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<HeldState>(HeldState.Reading) }
    val latestRead by rememberUpdatedState(read)
    LaunchedEffect(attempt) {
        state = HeldState.Reading
        state = latestRead()?.let { HeldState.Read(HeldNow.of(it)) } ?: HeldState.Failed
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
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
                stringResource(R.string.drain_held_now),
                Modifier.semantics { heading() },
                color = colors.textPrimary,
                style = CoulombTheme.typography.title,
            )
            HeldBody(state, name, formats)
            if (state != HeldState.Reading) {
                CoulombTextButton(
                    stringResource(R.string.drain_held_now_again),
                    { attempt++ },
                    Modifier.padding(top = CoulombSpacing.Space8),
                    edge = ButtonEdge.Start,
                )
            }
        }
    }
}

internal sealed interface HeldState {
    data object Reading : HeldState

    data object Failed : HeldState

    data class Read(
        val now: HeldNow,
    ) : HeldState
}

@Composable
internal fun HeldBody(
    state: HeldState,
    name: @Composable (uid: Int) -> String,
    formats: Formats,
) {
    when (state) {
        HeldState.Reading -> Note(stringResource(R.string.drain_held_now_reading))
        HeldState.Failed -> Note(stringResource(R.string.drain_held_now_failed))
        is HeldState.Read -> HeldList(state.now, name, formats)
    }
}

@Composable
private fun HeldList(
    now: HeldNow,
    name: @Composable (uid: Int) -> String,
    formats: Formats,
) {
    Note(stringResource(R.string.drain_held_now_caption))
    when {
        !now.listed -> {
            Note(stringResource(R.string.drain_held_now_unlisted))
        }

        now.rows.isEmpty() -> {
            Note(stringResource(R.string.drain_held_now_none))
        }

        else -> {
            Subhead(stringResource(R.string.drain_held_now_partial), definition = null, provenance = Provenance.MEASURED)
            InlineTable(
                columns = listOf(stringResource(R.string.drain_held_now_column_app), stringResource(R.string.drain_held_now_column_held)),
                rows =
                    now.rows.map { row ->
                        val flags =
                            listOfNotNull(
                                stringResource(R.string.drain_held_now_long).takeIf { row.long },
                                stringResource(R.string.drain_held_now_ignored).takeIf { row.ignored },
                            )
                        TableRow(
                            (listOf(name(row.uid), row.tag) + flags).joinToString("\n"),
                            listOf(row.heldMs?.let { formats.held(it) } ?: stringResource(R.string.drain_held_now_just_now)),
                        )
                    },
            )
        }
    }
    now.saverOn?.let { Note(stringResource(if (it) R.string.drain_held_now_saver_on else R.string.drain_held_now_saver_off)) }
}

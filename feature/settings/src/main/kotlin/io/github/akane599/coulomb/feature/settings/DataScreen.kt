// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.data.Archive
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombOutlinedButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import io.github.akane599.coulomb.core.ui.page.Subhead
import java.time.Instant
import java.time.ZoneId

/** "Data and export" (SPEC 14.5, ADR-0062): the three exports, a bundle in as an archive, and "Delete all data". */
@Composable
public fun DataScreen(
    modifier: Modifier = Modifier,
    onOpenActionCenter: () -> Unit = {},
    viewModel: DataViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The kind the system's picker was opened for; saved, because the picker may outlive this activity.
    var picking by rememberSaveable { mutableStateOf<ExportKind?>(null) }
    val create =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP)) { uri ->
            picking?.let { viewModel.export(it, uri) }
            picking = null
        }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> viewModel.import(uri) }
    val actions =
        remember(viewModel, onOpenActionCenter) {
            DataActions(
                export = { kind ->
                    picking = kind
                    create.launch(viewModel.fileName(kind))
                },
                import = { open.launch(BUNDLE_TYPES) },
                cancel = viewModel::cancel,
                useSettings = viewModel::applyOfferedSettings,
                keepSettings = viewModel::declineOfferedSettings,
                deleteArchive = viewModel::deleteArchive,
                askDeleteAll = viewModel::askDeleteAll,
                deleteAll = viewModel::deleteAll,
                dismissDeleteAsk = viewModel::dismissDeleteAsk,
                openActionCenter = {
                    viewModel.dismissDeleteAsk()
                    onOpenActionCenter()
                },
            )
        }
    FadeInOnce { DataContent(state, actions, modifier) }
}

/** What the screen's controls do. */
@Immutable
internal class DataActions(
    val export: (ExportKind) -> Unit = {},
    val import: () -> Unit = {},
    val cancel: () -> Unit = {},
    val useSettings: () -> Unit = {},
    val keepSettings: () -> Unit = {},
    val deleteArchive: (id: String) -> Unit = {},
    val askDeleteAll: () -> Unit = {},
    val deleteAll: () -> Unit = {},
    val dismissDeleteAsk: () -> Unit = {},
    val openActionCenter: () -> Unit = {},
)

@Composable
internal fun DataContent(
    state: DataUiState,
    actions: DataActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    nowMs: Long = System.currentTimeMillis(),
) {
    val formats = rememberFormats()
    val gutter = currentGutter()
    val idle = state.work == DataWork.Idle
    // The archive a "Delete" was tapped on, by id, while its question is open.
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        modifier
            .fillMaxSize()
            .background(CoulombTheme.colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(start = gutter, end = gutter, bottom = CoulombSpacing.Space48)
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = MaxWidth),
    ) {
        SectionTitle(stringResource(R.string.data_export), provenance = null, first = true)
        Offer(R.string.data_bundle, R.string.data_bundle_note, R.string.data_export_action, idle, first = true) {
            actions.export(ExportKind.BUNDLE)
        }
        Offer(R.string.data_csv, R.string.data_csv_note, R.string.data_export_action, idle) { actions.export(ExportKind.CSV) }
        Offer(R.string.data_raw, R.string.data_raw_note, R.string.data_export_action, idle) { actions.export(ExportKind.RAW) }
        HorizontalDivider(thickness = Hairline, color = CoulombTheme.colors.hairline)
        (state.work as? DataWork.Exporting)?.let { Working(exportingWords(it, formats), actions.cancel) }
        state.message?.takeIf { !it.aboutImport && !it.aboutDelete }?.let { Said(it) }
        Note(stringResource(R.string.data_export_note), top = CoulombSpacing.Space12)

        SectionTitle(stringResource(R.string.data_import), provenance = null)
        Text(
            stringResource(R.string.data_import_about),
            Modifier.fillMaxWidth().padding(bottom = CoulombSpacing.Space12),
            color = CoulombTheme.colors.textSecondary,
            style = CoulombTheme.typography.body,
        )
        CoulombOutlinedButton(stringResource(R.string.data_import_action), actions.import, enabled = idle)
        (state.work as? DataWork.Importing)?.let { work ->
            Working(
                if (work.rows > 0) {
                    stringResource(R.string.data_importing_rows, formats.integer(work.rows))
                } else {
                    stringResource(R.string.data_importing)
                },
                actions.cancel,
            )
        }
        state.message?.takeIf { it.aboutImport }?.let { Said(it) }
        if (state.offeredSettings != null) OfferedSettings(actions)
        Archives(state.archives.orEmpty(), formats, zone, nowMs, idle) { deleting = it }

        SectionTitle(stringResource(R.string.data_delete), provenance = null)
        Text(
            stringResource(R.string.data_delete_about),
            Modifier.fillMaxWidth().padding(bottom = CoulombSpacing.Space12),
            color = CoulombTheme.colors.textSecondary,
            style = CoulombTheme.typography.body,
        )
        CoulombOutlinedButton(stringResource(R.string.data_delete_action), actions.askDeleteAll, enabled = idle)
        // Back waits while the erase runs: the process restarts when it ends (audit CONC-002).
        BackHandler(enabled = state.work == DataWork.Deleting) {}
        if (state.work == DataWork.Deleting) Working(stringResource(R.string.data_deleting), cancel = null)
        state.message?.takeIf { it.aboutDelete }?.let { Said(it) }
    }
    Questions(state, deleting, actions) { deleting = null }
}

/** The dialogs: each stands before something that cannot be taken back. */
@Composable
private fun Questions(
    state: DataUiState,
    deleting: String?,
    actions: DataActions,
    onArchiveClose: () -> Unit,
) {
    val formats = rememberFormats()
    state.archives?.firstOrNull { it.id == deleting }?.let { archive ->
        Question(
            title = stringResource(R.string.data_archive_delete_title, archive.model),
            body = stringResource(R.string.data_archive_delete_body),
            confirm = stringResource(R.string.data_archive_delete),
            onConfirm = {
                actions.deleteArchive(archive.id)
                onArchiveClose()
            },
            onDismiss = onArchiveClose,
        )
    }
    when (val ask = state.deleteAsk) {
        DeleteAsk.Confirm -> {
            Question(
                title = stringResource(R.string.data_delete_title),
                body = stringResource(R.string.data_delete_body),
                confirm = stringResource(R.string.data_delete_action),
                onConfirm = actions.deleteAll,
                onDismiss = actions.dismissDeleteAsk,
            )
        }

        is DeleteAsk.Blocked -> {
            Question(
                title = stringResource(R.string.data_delete_blocked_title),
                body = pluralStringResource(R.plurals.data_delete_blocked_body, ask.inForce, formats.integer(ask.inForce.toLong())),
                confirm = stringResource(R.string.data_delete_blocked_open),
                onConfirm = actions.openActionCenter,
                onDismiss = actions.dismissDeleteAsk,
            )
        }

        null -> {}
    }
}

@Composable
private fun Question(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CoulombTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { CoulombTextButton(confirm, onConfirm) },
        dismissButton = { CoulombTextButton(stringResource(R.string.data_cancel), onDismiss) },
        title = { Text(title, color = colors.textPrimary, style = CoulombTheme.typography.title) },
        text = {
            Text(
                body,
                Modifier.verticalScroll(rememberScrollState()),
                color = colors.textSecondary,
                style = CoulombTheme.typography.body,
            )
        },
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(CoulombRadius.Large),
        // Raised like the sheets and menus (DESIGN.md, Raised Slate).
        containerColor = colors.surfaceRaised,
    )
}

/** One export: what it is and who it is for, with its button at the end. A hairline above, as in a list. */
@Composable
private fun Offer(
    @StringRes title: Int,
    @StringRes note: Int,
    @StringRes action: Int,
    enabled: Boolean,
    first: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = CoulombTheme.colors
    Column(Modifier.fillMaxWidth()) {
        if (!first) HorizontalDivider(thickness = Hairline, color = colors.hairline)
        Row(
            Modifier.fillMaxWidth().padding(vertical = CoulombSpacing.Space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = CoulombSpacing.Space16)) {
                Text(stringResource(title), color = colors.textPrimary, style = CoulombTheme.typography.bodyName)
                Text(stringResource(note), color = colors.textSecondary, style = CoulombTheme.typography.caption)
            }
            CoulombTextButton(stringResource(action), onClick, enabled = enabled, edge = ButtonEdge.End)
        }
    }
}

/** What is running, read out as it changes, with the way to stop it. */
@Composable
private fun Working(
    text: String,
    cancel: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            color = CoulombTheme.colors.textPrimary,
            style = CoulombTheme.typography.body,
        )
        if (cancel != null) CoulombTextButton(stringResource(R.string.data_cancel), cancel, edge = ButtonEdge.End)
    }
}

/** How the last thing ended: the problem and what to do next (SPEC 11.10). */
@Composable
private fun Said(message: DataMessage) {
    Text(
        stringResource(message.words()),
        Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space12).semantics { liveRegion = LiveRegionMode.Polite },
        color = CoulombTheme.colors.textPrimary,
        style = CoulombTheme.typography.body,
    )
}

/** After an import: the bundle's settings are used only when asked for, and only the neutral ones (ADR-0062 §12). */
@Composable
private fun OfferedSettings(actions: DataActions) {
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16)) {
        Text(
            stringResource(R.string.data_settings_offer),
            color = CoulombTheme.colors.textPrimary,
            style = CoulombTheme.typography.bodyName,
        )
        Note(stringResource(R.string.data_settings_offer_note), top = CoulombSpacing.Space4)
        Row(Modifier.padding(top = CoulombSpacing.Space4)) {
            CoulombTextButton(stringResource(R.string.data_settings_use), actions.useSettings, edge = ButtonEdge.Start)
            CoulombTextButton(stringResource(R.string.data_settings_keep), actions.keepSettings)
        }
    }
}

/** The imported histories, each with where it is from, what it spans and what it takes. Nothing when there is none. */
@Composable
private fun ColumnScope.Archives(
    archives: List<Archive>,
    formats: Formats,
    zone: ZoneId,
    nowMs: Long,
    enabled: Boolean,
    onDelete: (id: String) -> Unit,
) {
    if (archives.isEmpty()) return
    val colors = CoulombTheme.colors
    Subhead(stringResource(R.string.data_archives))
    for (archive in archives) {
        HorizontalDivider(thickness = Hairline, color = colors.hairline)
        Row(Modifier.fillMaxWidth().padding(vertical = CoulombSpacing.Space12), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = CoulombSpacing.Space16)) {
                Text(
                    stringResource(R.string.data_archive_name, archive.model),
                    color = colors.textPrimary,
                    style = CoulombTheme.typography.bodyName,
                )
                val (origin, holds) = archiveDetail(archive, formats, zone, nowMs)
                Text(origin, color = colors.textSecondary, style = CoulombTheme.typography.caption)
                Text(holds, color = colors.textSecondary, style = CoulombTheme.typography.caption)
            }
            CoulombTextButton(
                stringResource(R.string.data_archive_delete),
                { onDelete(archive.id) },
                enabled = enabled,
                edge = ButtonEdge.End,
            )
        }
    }
    HorizontalDivider(thickness = Hairline, color = colors.hairline)
}

/** "Imported from a file · Oct 12 – Nov 20", then "Sessions on battery: 41 · Charges: 38 · 14 MB". */
@Composable
private fun archiveDetail(
    archive: Archive,
    formats: Formats,
    zone: ZoneId,
    nowMs: Long,
): Pair<String, String> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    fun day(wallMs: Long) = formats.day(Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate(), today)
    val first = archive.firstWallMs
    val last = archive.lastWallMs
    val span =
        when {
            first == null || last == null -> stringResource(R.string.data_archive_empty)
            day(first) == day(last) -> day(first)
            else -> formats.range(day(first), day(last))
        }
    val separator = stringResource(R.string.data_separator)
    val holds =
        listOfNotNull(
            archive.discharges?.let { stringResource(R.string.data_archive_sessions, formats.integer(it)) },
            archive.charges?.let { stringResource(R.string.data_archive_charges, formats.integer(it)) },
            formats.bytes(archive.sizeBytes).joined(),
        )
    return listOf(stringResource(R.string.data_archive_origin), span).joinToString(separator) to holds.joinToString(separator)
}

@Composable
private fun exportingWords(
    work: DataWork.Exporting,
    formats: Formats,
): String =
    when {
        work.kind == ExportKind.RAW -> stringResource(R.string.data_exporting_raw)
        work.total > 0 -> stringResource(R.string.data_exporting_rows, formats.integer(work.done), formats.integer(work.total))
        else -> stringResource(R.string.data_exporting)
    }

@StringRes
private fun DataMessage.words(): Int =
    when (this) {
        DataMessage.EXPORTED -> R.string.data_said_exported
        DataMessage.EXPORT_FAILED -> R.string.data_said_export_failed
        DataMessage.RAW_PARTIAL -> R.string.data_said_raw_partial
        DataMessage.RAW_UNAVAILABLE -> R.string.data_said_raw_unavailable
        DataMessage.EXPORT_LEFT -> R.string.data_said_export_left
        DataMessage.IMPORTED -> R.string.data_said_imported
        DataMessage.SETTINGS_APPLIED -> R.string.data_said_settings_applied
        DataMessage.SETTINGS_FAILED -> R.string.data_said_settings_failed
        DataMessage.ARCHIVE_DELETED -> R.string.data_said_archive_deleted
        DataMessage.IMPORT_NOT_A_BUNDLE -> R.string.data_said_not_a_bundle
        DataMessage.IMPORT_NEWER -> R.string.data_said_newer
        DataMessage.IMPORT_TOO_OLD -> R.string.data_said_too_old
        DataMessage.IMPORT_INCOMPLETE -> R.string.data_said_incomplete
        DataMessage.IMPORT_DAMAGED -> R.string.data_said_damaged
        DataMessage.IMPORT_TOO_LARGE -> R.string.data_said_too_large
        DataMessage.IMPORT_ALREADY -> R.string.data_said_already
        DataMessage.IMPORT_FAILED -> R.string.data_said_import_failed
        DataMessage.DELETE_FAILED -> R.string.data_said_delete_failed
    }

private const val ZIP = "application/zip"

/** Zip files as the providers name them; a bundle sent through a chat app often arrives as plain bytes. */
private val BUNDLE_TYPES = arrayOf(ZIP, "application/x-zip-compressed", "application/octet-stream")
private val Hairline = 1.dp

/** One column on wide windows, as Settings (ADR-0034). */
private val MaxWidth = 720.dp

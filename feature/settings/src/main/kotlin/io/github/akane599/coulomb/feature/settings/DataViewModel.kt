// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akane599.coulomb.core.collect.actions.ActionCenter
import io.github.akane599.coulomb.core.collect.android.CollectorService
import io.github.akane599.coulomb.core.collect.export.RawBundle
import io.github.akane599.coulomb.core.collect.export.RawBundleResult
import io.github.akane599.coulomb.core.collect.export.RawOrigin
import io.github.akane599.coulomb.core.collect.export.RawPart
import io.github.akane599.coulomb.core.data.Archive
import io.github.akane599.coulomb.core.data.DataTransfer
import io.github.akane599.coulomb.core.data.ImportProblem
import io.github.akane599.coulomb.core.data.ImportResult
import io.github.akane599.coulomb.core.datastore.SettingsTransfer
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/** SPEC 14.5's three exports. [word] is the kind's part of the file name. */
internal enum class ExportKind(
    val word: String,
) {
    BUNDLE("bundle"),
    CSV("csv"),
    RAW("raw"),
}

/** What is running now; one thing at a time. */
internal sealed interface DataWork {
    data object Idle : DataWork

    /** [total] is 0 while the size is not known: the raw bundle, and a table export before its first rows. */
    data class Exporting(
        val kind: ExportKind,
        val done: Long = 0,
        val total: Long = 0,
    ) : DataWork

    data class Importing(
        val rows: Long = 0,
    ) : DataWork

    data object Deleting : DataWork
}

/** How the last thing ended, said once under the section it belongs to. */
internal enum class DataMessage {
    EXPORTED,
    EXPORT_FAILED,

    /** The raw bundle was saved with a dump cut short or missing; its README says which. */
    RAW_PARTIAL,

    /** Neither dump could be read: no file was kept. */
    RAW_UNAVAILABLE,
    IMPORTED,
    SETTINGS_APPLIED,
    ARCHIVE_DELETED,
    IMPORT_NOT_A_BUNDLE,
    IMPORT_NEWER,
    IMPORT_TOO_OLD,
    IMPORT_INCOMPLETE,
    IMPORT_DAMAGED,
    IMPORT_TOO_LARGE,
    IMPORT_ALREADY,
    IMPORT_FAILED,
    DELETE_FAILED,
    ;

    val aboutImport: Boolean get() = name.startsWith("IMPORT") || this == SETTINGS_APPLIED || this == ARCHIVE_DELETED
    val aboutDelete: Boolean get() = this == DELETE_FAILED
}

/** The question "Delete all data" asks first. */
internal sealed interface DeleteAsk {
    data object Confirm : DeleteAsk

    /** [inForce] changes made through the Action Center would be left with nothing to revert them (SPEC 1.2). */
    data class Blocked(
        val inForce: Int,
    ) : DeleteAsk
}

@Immutable
internal data class DataUiState(
    /** Null until the archive folders are read. */
    val archives: List<Archive>? = null,
    val work: DataWork = DataWork.Idle,
    val message: DataMessage? = null,
    /** An imported bundle's `settings.json`, held while the user decides whether to use it. */
    val offeredSettings: String? = null,
    val deleteAsk: DeleteAsk? = null,
)

/** What the screen needs from the phone; a fake stands in for it in tests. */
internal interface DataHost {
    fun now(): Long

    fun output(uri: Uri): OutputStream?

    fun input(uri: Uri): InputStream?

    /** Removes a document an export created and did not finish. */
    fun discard(uri: Uri)

    suspend fun settingsJson(): String

    suspend fun applySettings(json: String): Boolean

    suspend fun rawBundle(out: OutputStream): RawBundleResult

    suspend fun actionsInForce(): Int

    /** Stops the collector before a deletion; starts it again after one that failed, so it goes on as before. */
    fun collector(run: Boolean)

    /** Starts Coulomb again in a new process: the collector holds the deleted data's state in memory. */
    fun restart()
}

/**
 * "Data and export" (SPEC 14.5, ADR-0062). Everything runs in this view model's scope, so it stops
 * when the screen is left for good, and an export that did not finish leaves no file behind.
 */
@HiltViewModel
public class DataViewModel internal constructor(
    private val transfer: DataTransfer,
    private val host: DataHost,
) : ViewModel() {
    @Inject
    public constructor(
        @ApplicationContext context: Context,
        transfer: DataTransfer,
        settings: SettingsTransfer,
        raw: RawBundle,
        actions: ActionCenter,
    ) : this(transfer, AndroidDataHost(context, settings, raw, actions))

    private val ui = MutableStateFlow(DataUiState())
    internal val state: StateFlow<DataUiState> = ui.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            launch { transfer.archives.collect { archives -> ui.update { it.copy(archives = archives) } } }
            readArchives()
        }
    }

    /** The name the system's file picker starts with. */
    internal fun fileName(kind: ExportKind): String = transfer.fileName(kind.word, host.now())

    /** Exports to [uri], the document the picker created; null when the picker was left without one. */
    internal fun export(
        kind: ExportKind,
        uri: Uri?,
    ) {
        if (uri == null || ui.value.work != DataWork.Idle) return
        job =
            viewModelScope.launch {
                ui.update { it.copy(work = DataWork.Exporting(kind), message = null) }
                var kept = false
                try {
                    val message = write(kind, uri, coroutineContext[Job])
                    kept = message != DataMessage.RAW_UNAVAILABLE
                    ui.update { it.copy(message = message) }
                } catch (e: IOException) {
                    Log.w(TAG, "export failed", e)
                    ui.update { it.copy(message = DataMessage.EXPORT_FAILED) }
                } catch (e: SQLException) {
                    // No room for the copy the export reads from.
                    Log.w(TAG, "export failed", e)
                    ui.update { it.copy(message = DataMessage.EXPORT_FAILED) }
                } finally {
                    // Cancelled, failed or empty: a half-written file would pass for an export.
                    if (!kept) host.discard(uri)
                    ui.update { it.copy(work = DataWork.Idle) }
                }
            }
    }

    private suspend fun write(
        kind: ExportKind,
        uri: Uri,
        running: Job?,
    ): DataMessage {
        val out = host.output(uri) ?: throw IOException("The document could not be opened")
        val progress = { done: Long, total: Long ->
            // The writers do not suspend: this is where a cancelled export stops.
            running?.ensureActive()
            ui.update { it.copy(work = DataWork.Exporting(kind, done, total)) }
        }
        return when (kind) {
            ExportKind.BUNDLE -> {
                transfer.exportBundle(out, host.now(), host.settingsJson(), progress)
                DataMessage.EXPORTED
            }

            ExportKind.CSV -> {
                transfer.exportCsv(out, progress)
                DataMessage.EXPORTED
            }

            ExportKind.RAW -> {
                val result = host.rawBundle(out)
                when {
                    result.empty -> DataMessage.RAW_UNAVAILABLE
                    result.stats == RawPart.COMPLETE && result.history == RawPart.COMPLETE -> DataMessage.EXPORTED
                    else -> DataMessage.RAW_PARTIAL
                }
            }
        }
    }

    /** Imports the bundle at [uri] as an archive; null when the picker was left without a file. */
    internal fun import(uri: Uri?) {
        if (uri == null || ui.value.work != DataWork.Idle) return
        job =
            viewModelScope.launch {
                ui.update { it.copy(work = DataWork.Importing(), message = null, offeredSettings = null) }
                val running = coroutineContext[Job]
                try {
                    val input = host.input(uri)
                    val result =
                        if (input == null) {
                            ImportResult.Refused(ImportProblem.FAILED)
                        } else {
                            transfer.import(input) { rows ->
                                running?.ensureActive()
                                ui.update { it.copy(work = DataWork.Importing(rows)) }
                            }
                        }
                    ui.update {
                        when (result) {
                            is ImportResult.Imported -> it.copy(message = DataMessage.IMPORTED, offeredSettings = result.settings)
                            is ImportResult.Refused -> it.copy(message = result.problem.message())
                        }
                    }
                } finally {
                    ui.update { it.copy(work = DataWork.Idle) }
                }
            }
    }

    /** Uses the imported bundle's neutral settings on this phone (ADR-0062 §12). */
    internal fun applyOfferedSettings() {
        val json = ui.value.offeredSettings ?: return
        ui.update { it.copy(offeredSettings = null) }
        viewModelScope.launch {
            val applied =
                try {
                    host.applySettings(json)
                } catch (e: IOException) {
                    Log.w(TAG, "settings write failed", e)
                    false
                }
            if (applied) ui.update { it.copy(message = DataMessage.SETTINGS_APPLIED) }
        }
    }

    internal fun declineOfferedSettings() {
        ui.update { it.copy(offeredSettings = null) }
    }

    internal fun deleteArchive(id: String) {
        if (ui.value.work != DataWork.Idle) return
        viewModelScope.launch {
            transfer.deleteArchive(id)
            ui.update { it.copy(message = DataMessage.ARCHIVE_DELETED, offeredSettings = null) }
        }
    }

    /** Stops what is running; an export's file is removed. */
    internal fun cancel() {
        job?.cancel()
    }

    /** "Delete all data": asks first, and refuses while a change made through the Action Center is in force. */
    internal fun askDeleteAll() {
        if (ui.value.work != DataWork.Idle) return
        viewModelScope.launch {
            val inForce = host.actionsInForce()
            ui.update { it.copy(deleteAsk = if (inForce > 0) DeleteAsk.Blocked(inForce) else DeleteAsk.Confirm, message = null) }
        }
    }

    internal fun dismissDeleteAsk() {
        ui.update { it.copy(deleteAsk = null) }
    }

    /** After the confirmation. The check is made again here: the answer may be minutes old. */
    internal fun deleteAll() {
        if (ui.value.work != DataWork.Idle) return
        viewModelScope.launch {
            val inForce = host.actionsInForce()
            if (inForce > 0) {
                ui.update { it.copy(deleteAsk = DeleteAsk.Blocked(inForce)) }
                return@launch
            }
            ui.update { it.copy(deleteAsk = null, work = DataWork.Deleting, message = null) }
            host.collector(run = false)
            try {
                transfer.deleteAll()
            } catch (e: SQLException) {
                Log.w(TAG, "delete failed", e)
                host.collector(run = true)
                ui.update { it.copy(work = DataWork.Idle, message = DataMessage.DELETE_FAILED) }
                return@launch
            }
            host.restart()
        }
    }

    private suspend fun readArchives() {
        try {
            transfer.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "archives could not be read", e)
            ui.update { it.copy(archives = emptyList()) }
        }
    }

    private fun ImportProblem.message(): DataMessage =
        when (this) {
            ImportProblem.NOT_A_BUNDLE -> DataMessage.IMPORT_NOT_A_BUNDLE
            ImportProblem.NEWER -> DataMessage.IMPORT_NEWER
            ImportProblem.TOO_OLD -> DataMessage.IMPORT_TOO_OLD
            ImportProblem.INCOMPLETE -> DataMessage.IMPORT_INCOMPLETE
            ImportProblem.DAMAGED -> DataMessage.IMPORT_DAMAGED
            ImportProblem.TOO_LARGE -> DataMessage.IMPORT_TOO_LARGE
            ImportProblem.ALREADY_IMPORTED -> DataMessage.IMPORT_ALREADY
            ImportProblem.FAILED -> DataMessage.IMPORT_FAILED
        }

    private companion object {
        const val TAG = "Data"
    }
}

/** [DataHost] on the phone. */
private class AndroidDataHost(
    private val context: Context,
    private val settings: SettingsTransfer,
    private val raw: RawBundle,
    private val actions: ActionCenter,
) : DataHost {
    override fun now(): Long = System.currentTimeMillis()

    override fun output(uri: Uri): OutputStream? =
        try {
            // "wt": a document the picker reused is cut to this export's length.
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (e: IOException) {
            Log.w(TAG, "the document could not be opened", e)
            null
        }

    override fun input(uri: Uri): InputStream? =
        try {
            context.contentResolver.openInputStream(uri)
        } catch (e: IOException) {
            Log.w(TAG, "the file could not be opened", e)
            null
        }

    override fun discard(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: IOException) {
            Log.w(TAG, "an unfinished export could not be removed", e)
        } catch (e: SecurityException) {
            // A provider that lets a document be written and not deleted: the file stays, cut short.
            Log.w(TAG, "an unfinished export could not be removed", e)
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "an unfinished export could not be removed", e)
        }
    }

    override suspend fun settingsJson(): String = settings.toJson()

    override suspend fun applySettings(json: String): Boolean = settings.apply(json)

    override suspend fun rawBundle(out: OutputStream): RawBundleResult {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val origin = RawOrigin(now(), info.versionName.orEmpty(), Build.FINGERPRINT, Build.VERSION.SDK_INT)
        return raw.write(out, File(context.cacheDir, RAW_SCRATCH), origin)
    }

    override suspend fun actionsInForce(): Int = actions.records.first().count { it.active }

    override fun collector(run: Boolean) {
        if (run) {
            CollectorService.start(context, SnapshotTrigger.SERVICE_STARTED)
        } else {
            context.stopService(Intent(context, CollectorService::class.java))
        }
    }

    override fun restart() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch != null) context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        // The request to start is with the system before this process ends; the new one reads an empty database.
        Runtime.getRuntime().exit(0)
    }

    private companion object {
        const val TAG = "Data"
        const val RAW_SCRATCH = "raw-export"
    }
}

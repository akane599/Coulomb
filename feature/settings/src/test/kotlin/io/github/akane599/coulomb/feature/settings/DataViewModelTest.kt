// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import android.app.Application
import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.export.RawBundleResult
import io.github.akane599.coulomb.core.collect.export.RawPart
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.data.DataTransfer
import io.github.akane599.coulomb.core.database.CoulombDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** "Data and export" over the real transfer and a stand-in for the phone (SPEC 14.5, ADR-0062). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DataViewModelTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CoulombDatabase
    private val host = FakeHost()

    @Before
    fun open() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = CoulombDatabase.inMemory(context)
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO boot (bootSeq, rawBootCount, fingerprint, firstWallMs, firstElapsedNs, historyOffsetMs, imported) " +
                "VALUES (1, 7, 'lab', 1000, 5, NULL, 0)",
        )
    }

    @After
    fun close() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun model() = DataViewModel(DataTransfer(context, db, AppDispatchers.production()), host)

    private fun boots(): Long =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM boot").use {
            it.moveToFirst()
            it.getLong(0)
        }

    private suspend fun DataViewModel.settled(): DataUiState = state.first { it.work == DataWork.Idle && it.message != null }

    /** SPEC 1.2: a change that is still in force keeps its log, so that it can be reverted. */
    @Test
    fun deleteAllIsRefusedWhileAChangeIsInForce() =
        runTest {
            host.inForce = 2
            val model = model()

            model.askDeleteAll()
            assertThat(model.state.first { it.deleteAsk != null }.deleteAsk).isEqualTo(DeleteAsk.Blocked(2))
            // A stale confirmation gets the same answer.
            model.deleteAll()

            assertThat(model.state.value.deleteAsk).isEqualTo(DeleteAsk.Blocked(2))
            assertThat(boots()).isEqualTo(1)
            assertThat(host.stopped).isFalse()
            assertThat(host.restarted).isFalse()
        }

    @Test
    fun deleteAllStopsTheCollectorClearsEverythingAndRestarts() =
        runTest {
            val model = model()
            model.askDeleteAll()
            assertThat(model.state.first { it.deleteAsk != null }.deleteAsk).isEqualTo(DeleteAsk.Confirm)

            model.deleteAll()
            host.restartedSignal.first { it }

            assertThat(host.events).containsExactly("stop", "restart").inOrder()
            assertThat(boots()).isEqualTo(0)
        }

    @Test
    fun aBundleGoesOutAndComesBackAsAnArchiveWithItsSettingsOnOffer() =
        runTest {
            val model = model()
            model.export(ExportKind.BUNDLE, FILE)
            assertThat(model.settled().message).isEqualTo(DataMessage.EXPORTED)
            assertThat(host.discarded).isEmpty()
            val bundle = host.written.toByteArray()

            host.reading = bundle
            model.import(FILE)
            val imported = model.state.first { it.work == DataWork.Idle && it.message == DataMessage.IMPORTED }
            assertThat(imported.offeredSettings).isEqualTo("{\"theme\":\"DARK\"}")
            assertThat(
                model.state
                    .first { it.archives?.size == 1 }
                    .archives
                    ?.single()
                    ?.model,
            ).isNotEmpty()
            // The same file again is told apart, and nothing is offered.
            model.import(FILE)
            val again = model.state.first { it.work == DataWork.Idle && it.message == DataMessage.IMPORT_ALREADY }
            assertThat(again.offeredSettings).isNull()

            model.import(FILE.also { host.reading = "not a bundle".toByteArray() })
            assertThat(model.state.first { it.message == DataMessage.IMPORT_NOT_A_BUNDLE }.archives).hasSize(1)
            assertThat(boots()).isEqualTo(1)
        }

    @Test
    fun anExportThatFailsOrHoldsNothingLeavesNoFile() =
        runTest {
            val model = model()
            host.opens = false
            model.export(ExportKind.CSV, FILE)
            assertThat(model.settled().message).isEqualTo(DataMessage.EXPORT_FAILED)
            assertThat(host.discarded).containsExactly(FILE)

            host.opens = true
            host.raw = RawBundleResult(RawPart.MISSING, RawPart.MISSING)
            model.export(ExportKind.RAW, FILE)
            assertThat(model.state.first { it.work == DataWork.Idle && it.message == DataMessage.RAW_UNAVAILABLE }).isNotNull()
            assertThat(host.discarded).containsExactly(FILE, FILE)

            host.raw = RawBundleResult(RawPart.COMPLETE, RawPart.PARTIAL)
            model.export(ExportKind.RAW, FILE)
            assertThat(model.state.first { it.work == DataWork.Idle && it.message == DataMessage.RAW_PARTIAL }).isNotNull()
            assertThat(host.discarded).hasSize(2)
        }

    /** Audit BUG-039: a provider that keeps the unfinished file is not said to have kept nothing. */
    @Test
    fun anUnfinishedFileThatCouldNotBeRemovedIsSaid() =
        runTest {
            val model = model()
            host.opens = false
            host.deletes = false
            model.export(ExportKind.CSV, FILE)
            assertThat(model.settled().message).isEqualTo(DataMessage.EXPORT_LEFT)
        }

    private class FakeHost : DataHost {
        var inForce = 0
        var opens = true
        var raw = RawBundleResult(RawPart.COMPLETE, RawPart.COMPLETE)
        var reading = ByteArray(0)
        var written = ByteArrayOutputStream()
        val discarded = ArrayList<Uri>()
        val events = ArrayList<String>()
        val restartedSignal = kotlinx.coroutines.flow.MutableStateFlow(false)
        val stopped: Boolean get() = "stop" in events
        val restarted: Boolean get() = "restart" in events

        override fun now(): Long = 1_800_000_000_000

        var deletes = true

        override suspend fun output(uri: Uri): OutputStream? = if (opens) ByteArrayOutputStream().also { written = it } else null

        override suspend fun input(uri: Uri): InputStream = ByteArrayInputStream(reading)

        override suspend fun discard(uri: Uri): Boolean {
            discarded += uri
            return deletes
        }

        override suspend fun settingsJson(): String = "{\"theme\":\"DARK\"}"

        override suspend fun applySettings(json: String): Boolean = true

        override suspend fun rawBundle(out: OutputStream): RawBundleResult = raw.also { out.close() }

        override suspend fun actionsInForce(eraseIfNone: (suspend () -> Unit)?): Int = inForce.also { if (it == 0) eraseIfNone?.invoke() }

        override fun collector(run: Boolean) {
            events += if (run) "start" else "stop"
        }

        override fun restart() {
            events += "restart"
            restartedSignal.value = true
        }
    }

    private companion object {
        val FILE: Uri = Uri.parse("content://lab/export.zip")
    }
}

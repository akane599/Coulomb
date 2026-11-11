// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.history

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.model.HistoryGapReason
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import io.github.akane599.coulomb.core.model.history.HistoryRecord
import io.github.akane599.coulomb.core.parsing.batterystats.BatteryHistoryParser
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * ADR-0014's merged-record case on a real system_server: a state change within a second of a read's
 * last record can be merged into that record before the batch is committed, and no later read
 * prints it. The collector then compares the next read's restated state with the one it stored
 * and records a `STATE_MISMATCH` gap (ADR-0018).
 *
 * The test fakes plug changes (`dumpsys battery set ac`, emulator only) at least
 * [MIN_TOGGLE_GAP_MS] apart while history reads run back to back through the collector's own parser
 * and ingest. Every plug change must come out either as a `PLUGGED` transition or inside a
 * mismatch gap: none may vanish. How often a change was merged is logged under [TAG].
 *
 * Run: `am instrument -w -e class io.github.akane599.coulomb.core.collect.history.HistoryMergeExperimentTest
 * io.github.akane599.coulomb.core.collect.test/androidx.test.runner.AndroidJUnitRunner`.
 */
@RunWith(AndroidJUnit4::class)
class HistoryMergeExperimentTest {
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Before
    fun onlyOnAnEmulator() {
        // Faking the plug state is for emulators only (AGENTS.md); a phone skips the test.
        assumeTrue("needs an emulator", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
    }

    @After
    fun restoreTheBattery() {
        if (Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") shell("dumpsys battery reset")
    }

    @Test
    fun everyPlugChangeIsATransitionOrInsideAMismatchGap() {
        shell("dumpsys battery set ac 0")
        shell("dumpsys battery set usb 0")
        shell("dumpsys battery set wireless 0")
        SystemClock.sleep(SETTLE_MS)

        val reader = Reader()
        // The first read takes the whole history and places the boot; nothing is compared yet.
        reader.read()
        reader.read()
        reader.forget()

        val toggles = Collections.synchronizedList(ArrayList<Toggle>())
        val done = AtomicBoolean(false)
        val toggler =
            thread(name = "plug-toggler") {
                val random = Random(SEED)
                var plugged = false
                repeat(TOGGLES) {
                    SystemClock.sleep(MIN_TOGGLE_GAP_MS + random.nextLong(TOGGLE_JITTER_MS))
                    plugged = !plugged
                    val from = SystemClock.elapsedRealtime()
                    shell("dumpsys battery set ac ${if (plugged) 1 else 0}")
                    toggles += Toggle(from, SystemClock.elapsedRealtime())
                }
                done.set(true)
            }
        while (!done.get()) reader.read()
        toggler.join()
        // Past the merge window, so the last change is committed and read.
        SystemClock.sleep(SETTLE_MS)
        repeat(FINAL_READS) { reader.read() }

        // Each toggle, by the time its change could have been recorded: a transition then, or a
        // mismatch gap over it. Toggles are further apart than the window, so none can borrow
        // another's transition.
        val byTransition = toggles.filter { t -> reader.transitionsMs.any { it in t.window() } }
        val byGap = toggles.filter { t -> t !in byTransition && reader.mismatchSpansMs.any { it.overlaps(t.window()) } }
        val lost = toggles - byTransition.toSet() - byGap.toSet()
        Log.i(
            TAG,
            "reads=${reader.reads} toggles=${toggles.size} asTransitions=${byTransition.size} inMismatchGaps=${byGap.size} " +
                "lost=${lost.size} transitions=${reader.transitionsMs.size} mismatchGaps=${reader.mismatchSpansMs.size} " +
                "readMs p50=${reader.p50()} max=${reader.maxMs()}",
        )
        assertWithMessage("toggles neither read as a transition nor inside a mismatch gap").that(lost).isEmpty()
        assertWithMessage("plug transitions without a toggle").that(reader.transitionsMs.size).isAtMost(toggles.size)
        assertWithMessage("stored plug state at the end").that(reader.plugged()).isEqualTo(TOGGLES % 2 == 1)
    }

    /** A toggle's shell command ran from [fromMs] to [toMs], on the elapsed clock. */
    private data class Toggle(
        val fromMs: Long,
        val toMs: Long,
    ) {
        /** When the platform can have recorded the change; it may note it just after the command returns. */
        fun window(): LongRange = (fromMs - EARLY_MS)..(toMs + LATE_MS)
    }

    private fun LongRange.overlaps(other: LongRange): Boolean = first <= other.last && other.first <= last

    /** History reads as the collector makes them (CollectorEngine.readHistory), on the test's own clock. */
    private inner class Reader {
        private var cursor = 0L
        private var end: ReadEnd? = null
        private var offset: Long? = null
        val transitionsMs = ArrayList<Long>()
        val mismatchSpansMs = ArrayList<LongRange>()
        private val durations = ArrayList<Long>()
        var reads = 0
            private set

        fun read() {
            val records = ArrayList<HistoryRecord>()
            val started = SystemClock.elapsedRealtime()
            val parsed =
                ParcelFileDescriptor
                    .AutoCloseInputStream(
                        automation.executeShellCommand("dumpsys batterystats -c --history-start $cursor"),
                    ).use {
                        BatteryHistoryParser.parse(it, cursor) { batch -> records += batch }
                    }
            val now = SystemClock.elapsedRealtime()
            durations += now - started
            val result =
                HistoryIngest.ingest(
                    ReadInput(records, parsed.value, cursor, end, BOOT, offset, System.currentTimeMillis() - now, now, false),
                )
            result.events
                .filter { it.type == HistoryKind.PLUGGED && !it.baseline && it.change != HistoryChange.SET }
                .mapTo(transitionsMs) { it.elapsedMs }
            result.gaps
                .filter { it.reason == HistoryGapReason.STATE_MISMATCH }
                .mapTo(mismatchSpansMs) { (it.startElapsedNs / NS_PER_MS)..(it.endElapsedNs / NS_PER_MS) }
            result.nextCursorMs?.let { cursor = it }
            result.newOffsetMs?.let { offset = it }
            end = result.end
            reads++
        }

        /** Drops what the reads found so far: only changes during the toggling count. */
        fun forget() {
            transitionsMs.clear()
            mismatchSpansMs.clear()
        }

        fun plugged(): Boolean = (end?.state?.values?.get(HistoryKind.PLUGGED) ?: 0L) != 0L

        fun p50(): Long = durations.sorted()[durations.size / 2]

        fun maxMs(): Long = durations.max()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    private companion object {
        const val TAG = "CoulombMerge"
        const val BOOT = 1
        const val TOGGLES = 150
        const val SEED = 2026_11_11L

        /** Above the one-second merge window, so no two changes can fold into one record. */
        const val MIN_TOGGLE_GAP_MS = 1_200L
        const val TOGGLE_JITTER_MS = 600L
        const val SETTLE_MS = 2_500L
        const val FINAL_READS = 3
        const val EARLY_MS = 50L
        const val LATE_MS = 500L
        const val NS_PER_MS = 1_000_000L
    }
}

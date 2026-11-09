// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.collect.android.FileBlobStore
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity
import io.github.akane599.coulomb.core.database.entity.RecordCodes
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.history.HistoryRecord
import io.github.akane599.coulomb.core.parsing.batterystats.BatteryHistoryParser
import io.github.akane599.coulomb.core.parsing.batterystats.BatteryStatsProtoParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.PriorityQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The time-compressed stress test of SPEC 16.3 (ADR-0019, ADR-0020, ADR-0021): a month of triggers
 * on a simulated clock through the whole collector, on a file database, in under 10 minutes on the
 * API 37 emulator. It runs 31 days so that the 30-day retention cuts run once. Pass: no crash and no
 * ANR, heap growth after GC under 10% from day 1 to the last day, and the database within the 7.7
 * projection with root's kernel storage added (ADR-0029). Component breakdown is on, with an S4
 * dump at every heavy snapshot that S4 would read, so every window on battery stores component rows
 * (ADR-0035, Size): at most 33 a window.
 *
 * The synthetic phone follows the rates measured on the S25 Ultra at the 2026-11-07 checkpoint:
 * about 75 screen cycles, 160 heavy and 210 light snapshots and 66,000 stored history events a day.
 * Its days are a night on the charger (every third night on battery, 8 h with the screen off), a
 * short top-up, Coulomb opened once, package broadcasts in bursts, and a reboot every week.
 *
 * Instrumented, run on the emulator host (AGENTS.md): `adb install -r -t` the test APK, then
 * `am instrument -w -e class <this class> <test package>/androidx.test.runner.AndroidJUnitRunner`.
 * Results go to logcat under [TAG].
 */
@RunWith(AndroidJUnit4::class)
class CollectorStressTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var root: File
    private lateinit var db: CoulombDatabase

    @Before
    fun open() {
        // The previous run's files stay until the next run, for a look with run-as.
        root = File(context.noBackupFilesDir, "stress")
        root.deleteRecursively()
        root.mkdirs()
        db = Room.databaseBuilder(context, CoulombDatabase::class.java, File(root, CoulombDatabase.FILE_NAME).path).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun aMonthStaysWithinTheBudgets() =
        runBlocking {
            val started = SystemClock.elapsedRealtime()
            val watchdog = MainThreadWatchdog().also { it.start() }
            val world = world()
            db.deviceDao().upsertProfile(
                DeviceProfileEntity(world.identity.fingerprint, "stress", "stress", 37, 3_700_000, null, null, 1, 1, true, 0, 0.95),
            )
            val sim = Simulation(world, FileBlobStore(root))
            sim.run()
            watchdog.finish()
            val minutes = (SystemClock.elapsedRealtime() - started) / MINUTE_MS.toDouble()

            val heapGrowth = (sim.heapEnd - sim.heapDay1).toDouble() / sim.heapDay1
            val projectedMb = projection(checkNotNull(sim.sizesMid), checkNotNull(sim.sizesEnd)) / MB + ROOT_MB
            log("run: %.1f min; worst main-thread delay %d ms".format(minutes, watchdog.worstMs))
            log("heap after GC: day 1 %.1f MB, day $DAYS %.1f MB (%+.1f%%)".format(sim.heapDay1 / MB, sim.heapEnd / MB, heapGrowth * 100))
            log(
                "database: day $DAYS file %.1f MB, projected after a year %.1f MB with root's $ROOT_MB".format(
                    checkNotNull(sim.sizesEnd).fileBytes / MB,
                    projectedMb,
                ),
            )

            assertWithMessage("heavy snapshots in $DAYS days").that(sim.totals.heavy).isAtLeast(DAYS * MIN_HEAVY_PER_DAY)
            assertWithMessage("stored history events at day $DAYS").that(sim.historyRowsEnd).isAtLeast(MIN_HISTORY_ROWS)
            assertWithMessage("light snapshots of the first 6 h left at day $DAYS (the 30-day cut ran)")
                .that(sim.earlyLightEnd)
                .isEqualTo(0)
            val brokenDown = long("SELECT COUNT(*) FROM window_result WHERE kind = ${RecordCodes.WINDOW_CONSECUTIVE} AND breakdown = 0")
            val consecutive = long("SELECT COUNT(*) FROM window_result WHERE kind = ${RecordCodes.WINDOW_CONSECUTIVE}")
            val mostRows = long("SELECT COALESCE(MAX(n), 0) FROM (SELECT COUNT(*) n FROM window_component GROUP BY windowId)")
            log("S4: $brokenDown of $consecutive consecutive windows broken down; at most $mostRows component rows a window")
            assertWithMessage("broken-down windows left at day $DAYS (S4 ran)").that(brokenDown).isAtLeast(MIN_BROKEN_DOWN)
            assertWithMessage("component rows in one window (ADR-0035)").that(mostRows).isAtMost(MAX_COMPONENT_ROWS)
            assertWithMessage("worst main-thread delay, ms (ANR)").that(watchdog.worstMs).isLessThan(ANR_MS)
            assertWithMessage("heap growth after GC, day 1 to day 30").that(heapGrowth).isLessThan(MAX_HEAP_GROWTH)
            assertWithMessage("database projected after a year, MB (SPEC 7.7, ADR-0020)").that(projectedMb).isLessThan(BUDGET_MB)
            assertWithMessage("run time, minutes").that(minutes).isLessThan(MAX_MINUTES)
        }

    private fun world(): StressWorld {
        val assets = instrumentation.context.assets
        val proto = assets.open("stress/batterystats.proto.bin").use { it.readBytes() }
        val template = BatteryStatsProtoParser.parse(proto.inputStream()).value
        val records = ArrayList<HistoryRecord>()
        assets.open("stress/batterystats.checkin.txt").use { input -> BatteryHistoryParser.parse(input, 0) { records += it } }
        return StressWorld(SyntheticCounters(template), proto, HistoryPool(records), START_WALL_MS, ZONE, HISTORY_PER_DAY, NEW_TAGS_PER_DAY)
    }

    /** One run: the plan, the event loop, and the measurements at days 1, [MID_DAY] and [DAYS]. */
    private inner class Simulation(
        private val world: StressWorld,
        private val blobs: FileBlobStore,
    ) {
        private val random = Random(SEED)
        private val queue = PriorityQueue(compareBy<Planned>({ it.wallMs }, { it.seq }))
        private var seq = 0L
        private var engine: CollectorEngine? = null
        private var lastLevel = world.levelPct
        private var nextSample: Long? = null
        private var nextPeriodic: Long? = null
        private var day = Counts()
        val totals = Counts()
        var heapDay1 = 0L
        var heapEnd = 0L
        var historyRowsEnd = 0L
        var earlyLightEnd = -1L
        var sizesMid: Sizes? = null
        var sizesEnd: Sizes? = null

        suspend fun run() {
            plan().forEach { (at, act) -> add(at, act) }
            engine = newEngine()
            trigger(SnapshotTrigger.SERVICE_STARTED)
            nextPeriodic = world.wallMs + PERIODIC_MS
            var mark = 1
            while (true) {
                if (world.levelPct != lastLevel) {
                    levelChanged()
                    continue
                }
                val every = engine?.let { SamplerPolicy.intervalMs(world.plugged, world.screenOn, world.screen) }
                if (every == null) {
                    nextSample = null
                } else if (nextSample == null) {
                    nextSample = world.wallMs + every
                }
                val markAt = START_WALL_MS + mark * DAY_MS
                val levelAt = world.nextLevelChangeWallMs()
                val periodicAt = nextPeriodic.takeIf { engine != null && world.screenOn }
                val candidates = listOfNotNull(markAt, queue.peek()?.wallMs, levelAt, periodicAt, nextSample)
                val at = maxOf(world.wallMs, candidates.min())
                world.advanceTo(at)
                when {
                    at >= markAt -> {
                        mark(mark)
                        if (++mark > DAYS) return
                    }

                    queue.peek()?.let { it.wallMs <= at } == true -> {
                        dispatch(checkNotNull(queue.poll()).act)
                    }

                    // The level check at the top of the loop takes it.
                    levelAt != null && at >= levelAt -> {
                        continue
                    }

                    periodicAt != null && at >= periodicAt -> {
                        nextPeriodic = at + PERIODIC_MS
                        trigger(SnapshotTrigger.SCREEN_ON_PERIODIC)
                    }

                    else -> {
                        sample(every)
                    }
                }
            }
        }

        private suspend fun levelChanged() {
            lastLevel = world.levelPct
            world.levelChanged()
            trigger(SnapshotTrigger.LEVEL_CHANGED)
        }

        private suspend fun sample(every: Long?) {
            val engine = engine ?: return
            engine.sample(world.light.read())
            day.samples++
            totals.samples++
            nextSample = every?.let { world.wallMs + it }
        }

        @Suppress("CyclomaticComplexMethod")
        private suspend fun dispatch(act: Act) {
            val now = world.wallMs
            when (act) {
                Act.SCREEN_ON -> {
                    if (world.screenOn) return
                    world.setScreen(true)
                    resetTimers()
                    nextPeriodic = now + PERIODIC_MS
                    trigger(SnapshotTrigger.SCREEN_ON)
                    add(now + UNLOCK_MS, Act.USER_PRESENT)
                }

                Act.USER_PRESENT -> {
                    if (world.screenOn) trigger(SnapshotTrigger.USER_PRESENT)
                }

                Act.SCREEN_OFF -> {
                    if (!world.screenOn) return
                    world.setScreen(false)
                    world.screen = VisibleScreen.NONE
                    resetTimers()
                    // The CPU can suspend as soon as the screen is off on battery: some reads sleep.
                    if (!world.plugged && random.nextDouble() < SLEPT_SHARE) world.sleepInNextDump = true
                    trigger(SnapshotTrigger.SCREEN_OFF)
                }

                Act.PLUG -> {
                    if (world.plugged) return
                    world.setPlugged(true)
                    resetTimers()
                    trigger(SnapshotTrigger.POWER_CONNECTED)
                }

                Act.UNPLUG -> {
                    if (!world.plugged) return
                    world.setPlugged(false)
                    resetTimers()
                    trigger(SnapshotTrigger.POWER_DISCONNECTED)
                }

                Act.OPEN_APP -> {
                    if (!world.screenOn || engine == null) return
                    world.screen = VisibleScreen.COULOMB
                    resetTimers()
                    trigger(SnapshotTrigger.APP_OPENED)
                }

                Act.REFRESH -> {
                    if (world.screen != VisibleScreen.NONE) trigger(SnapshotTrigger.PULL_TO_REFRESH)
                }

                Act.CLOSE_APP -> {
                    world.screen = VisibleScreen.NONE
                    resetTimers()
                }

                Act.PACKAGE_CHANGED -> {
                    trigger(SnapshotTrigger.PACKAGE_CHANGED)
                }

                Act.PACKAGE_REPLACED -> {
                    // Coulomb itself updated: a new process.
                    engine = newEngine()
                    trigger(SnapshotTrigger.PACKAGE_REPLACED)
                }

                Act.REBOOT -> {
                    engine = null
                    world.screen = VisibleScreen.NONE
                    world.reboot(REBOOT_DOWN_MS)
                    resetTimers()
                    add(world.wallMs + BOOT_RECEIVER_MS, Act.BOOT_DONE)
                }

                Act.BOOT_DONE -> {
                    engine = newEngine()
                    trigger(SnapshotTrigger.BOOT_COMPLETED)
                    if (world.screenOn) nextPeriodic = world.wallMs + PERIODIC_MS
                }

                Act.KILL -> {
                    engine = null
                    world.screen = VisibleScreen.NONE
                    resetTimers()
                }

                Act.RESTART -> {
                    engine = newEngine()
                    trigger(SnapshotTrigger.SERVICE_STARTED)
                    if (world.screenOn) nextPeriodic = world.wallMs + PERIODIC_MS
                }
            }
        }

        private fun resetTimers() {
            nextSample = null
            if (!world.screenOn || engine == null) nextPeriodic = null
        }

        private suspend fun trigger(trigger: SnapshotTrigger) {
            val action = engine?.handle(trigger) ?: return
            day.count(action)
            totals.count(action)
        }

        private fun newEngine() =
            CollectorEngine(db, world.light, world.heavy, world.identity, world.clock, blobs, NoLabels, CollectorSettings { true })

        private fun add(
            wallMs: Long,
            act: Act,
        ) {
            queue += Planned(wallMs, seq++, act)
        }

        private fun mark(day: Int) {
            val history = long("SELECT COUNT(*) FROM history_event")
            val bytes = long("PRAGMA page_count") * long("PRAGMA page_size")
            log(
                "day %2d: heavy %d, light %d, level samples %d, debounced %d, sampler %d; history rows %d, blobs %d, db %.1f MB"
                    .format(
                        day,
                        this.day.heavy,
                        this.day.light,
                        this.day.levelSamples,
                        this.day.debounced,
                        this.day.samples,
                        history,
                        blobs.list().size,
                        bytes / MB,
                    ),
            )
            this.day = Counts()
            when (day) {
                1 -> {
                    heapDay1 = heapAfterGc()
                }

                MID_DAY -> {
                    sizesMid = sizes()
                }

                DAYS -> {
                    heapEnd = heapAfterGc()
                    historyRowsEnd = history
                    earlyLightEnd =
                        long(
                            "SELECT COUNT(*) FROM snapshot WHERE kind = ${RecordCodes.SNAPSHOT_LIGHT} AND wallMs < ${START_WALL_MS + EARLY_MS}",
                        )
                    sizesEnd = sizes()
                    detailPerWindow()
                }
            }
        }

        /** The days, as state changes; one that changes nothing is skipped when it comes. */
        private fun plan(): List<Pair<Long, Act>> {
            val out = ArrayList<Pair<Long, Act>>()
            val firstDay = Instant.ofEpochMilli(START_WALL_MS).atZone(ZONE).toLocalDate()
            for (d in 0..DAYS) {
                val midnight =
                    firstDay
                        .plusDays(d.toLong())
                        .atStartOfDay(ZONE)
                        .toInstant()
                        .toEpochMilli()

                fun at(
                    hour: Int,
                    minute: Int = 0,
                ) = midnight + (hour * 60L + minute) * MINUTE_MS
                out += at(7) to Act.UNPLUG
                var t = at(7) + UNLOCK_MS
                var opened = false
                while (t < at(22, 30)) {
                    var onMs = random.nextLong(MINUTE_MS, 8 * MINUTE_MS)
                    out += t to Act.SCREEN_ON
                    if (!opened && t >= at(12, 30)) {
                        opened = true
                        onMs = maxOf(onMs, 10 * MINUTE_MS)
                        out += t + 20_000 to Act.OPEN_APP
                        out += t + 2 * MINUTE_MS to Act.REFRESH
                        out += t + 9 * MINUTE_MS to Act.CLOSE_APP
                    }
                    out += t + onMs to Act.SCREEN_OFF
                    t += onMs + random.nextLong(2 * MINUTE_MS, 14 * MINUTE_MS)
                }
                when (d % 3) {
                    1 -> out += listOf(at(15) to Act.PLUG, at(15, 45) to Act.UNPLUG, at(23) to Act.PLUG)

                    2 -> out += listOf(at(19) to Act.PLUG, at(22, 30) to Act.UNPLUG)

                    // the night on battery
                    else -> out += at(23) to Act.PLUG
                }
                repeat(PACKAGE_BURSTS) {
                    val burst = midnight + random.nextLong(DAY_MS)
                    repeat(random.nextInt(6, 15)) { i -> out += burst + i * 3_000L to Act.PACKAGE_CHANGED }
                }
                if (d in REBOOT_DAYS) out += at(16, 20) to Act.REBOOT
                if (d == NIGHT_REBOOT_DAY) out += at(3) to Act.REBOOT
                if (d in UPDATE_DAYS) out += at(4) to Act.PACKAGE_REPLACED
                if (d == KILL_DAY) out += listOf(at(10, 10) to Act.KILL, at(10, 27) to Act.RESTART)
            }
            return out.filter { it.first > START_WALL_MS && it.first < START_WALL_MS + DAYS * DAY_MS }
        }

        private fun detailPerWindow() {
            val windows = long("SELECT COUNT(*) FROM window_result WHERE kind = ${RecordCodes.WINDOW_CONSECUTIVE} AND detailPruned = 0")
            val perWindow =
                DETAIL_TABLES.joinToString { table ->
                    val rows =
                        long(
                            "SELECT COUNT(*) FROM $table d JOIN window_result w ON w.id = d.windowId " +
                                "WHERE w.kind = ${RecordCodes.WINDOW_CONSECUTIVE} AND w.detailPruned = 0",
                        )
                    "$table %.1f".format(rows.toDouble() / windows)
                }
            log("detail rows per consecutive window ($windows windows): $perWindow")
        }
    }

    /** Main-thread latency, sampled every second: an ANR is the main thread stuck for 5 s. */
    private class MainThreadWatchdog : Thread("stress-watchdog") {
        private val main = Handler(Looper.getMainLooper())

        @Volatile private var running = true

        @Volatile var worstMs: Long = 0
            private set

        override fun run() {
            while (running) {
                val posted = SystemClock.uptimeMillis()
                val done = CountDownLatch(1)
                main.post { done.countDown() }
                done.await(2 * ANR_MS, TimeUnit.MILLISECONDS)
                worstMs = maxOf(worstMs, SystemClock.uptimeMillis() - posted)
                sleep(WATCHDOG_PERIOD_MS)
            }
        }

        fun finish() {
            running = false
            join()
        }
    }

    private fun heapAfterGc(): Long {
        val runtime = Runtime.getRuntime()
        repeat(GC_ROUNDS) {
            runtime.gc()
            System.runFinalization()
            Thread.sleep(GC_PAUSE_MS)
        }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** Bytes per table, its indexes included, from SQLite's `dbstat`; the file after a checkpoint. */
    private fun sizes(): Sizes {
        val sql = db.openHelper.writableDatabase
        sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val owner = HashMap<String, String>()
        sql.query("SELECT name, tbl_name FROM sqlite_master WHERE type IN ('table', 'index')").use { c ->
            while (c.moveToNext()) owner[c.getString(0)] = c.getString(1)
        }
        val bytes = HashMap<String, Long>()
        sql.query("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name").use { c ->
            while (c.moveToNext()) bytes.merge(owner[c.getString(0)] ?: c.getString(0), c.getLong(1), Long::plus)
        }
        val cappedShare =
            CAPPED_ROWS.mapValues { (table, capped) ->
                val all = long("SELECT COUNT(*) FROM $table")
                if (all == 0L) 0.0 else long("SELECT COUNT(*) FROM $table x WHERE $capped").toDouble() / all
            }
        val sizes = Sizes(long("PRAGMA page_count") * long("PRAGMA page_size"), bytes, cappedShare)
        // Free pages count in the file, as on the phone: retention vacuums only above 10% (ADR-0018).
        log("free pages: %.2f MB".format(long("PRAGMA freelist_count") * long("PRAGMA page_size") / MB))
        log(
            "tables: " +
                bytes.entries
                    .sortedByDescending { it.value }
                    .take(TOP_TABLES)
                    .joinToString { (name, b) -> "$name %.2f MB".format(b / MB) },
        )
        return sizes
    }

    /**
     * ADR-0020 as ADR-0021 corrects it: the last day's file plus the growth from [MID_DAY] to [DAYS]
     * of every part retention does not cap, extended to day 365.
     * - Capped whole: raw samples, history, and the snapshot counters (kept 2 h).
     * - Capped in part, a table's bytes split by row count ([CAPPED_ROWS]): consecutive windows,
     *   reference and charge-session detail, and the snapshots only those point to.
     * - Aggregates grow for 180 days, then turn hourly: a sixtieth of the rate.
     */
    private fun projection(
        mid: Sizes,
        end: Sizes,
    ): Double {
        val days = (DAYS - MID_DAY).toDouble()
        val growth = HashMap<String, Double>()
        for (table in end.tableBytes.keys + mid.tableBytes.keys) {
            if (table in CAPPED_TABLES) continue
            val late = (end.tableBytes[table] ?: 0) * (1 - (end.cappedShare[table] ?: 0.0))
            val early = (mid.tableBytes[table] ?: 0) * (1 - (mid.cappedShare[table] ?: 0.0))
            if (late <= early) continue
            val perDay = (late - early) / days
            growth[table] =
                if (table == AGGREGATES) {
                    perDay * (MINUTE_AGGREGATE_DAYS + (YEAR_DAYS - RAW_DAYS - MINUTE_AGGREGATE_DAYS) / 60.0) - late
                } else {
                    perDay * (YEAR_DAYS - DAYS)
                }
        }
        log(
            "growth to day 365: " +
                growth.entries
                    .sortedByDescending { it.value }
                    .take(TOP_TABLES)
                    .joinToString { (name, b) -> "$name %.2f MB".format(b / MB) },
        )
        return end.fileBytes + growth.values.sum()
    }

    private fun long(query: String): Long =
        db.openHelper.readableDatabase.query(query).use {
            it.moveToFirst()
            it.getLong(0)
        }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    private enum class Act {
        SCREEN_ON,
        USER_PRESENT,
        SCREEN_OFF,
        PLUG,
        UNPLUG,
        OPEN_APP,
        REFRESH,
        CLOSE_APP,
        PACKAGE_CHANGED,
        PACKAGE_REPLACED,
        REBOOT,
        BOOT_DONE,
        KILL,
        RESTART,
    }

    private data class Planned(
        val wallMs: Long,
        val seq: Long,
        val act: Act,
    )

    private class Counts {
        var heavy = 0
        var light = 0
        var levelSamples = 0
        var debounced = 0
        var samples = 0

        fun count(action: Action) {
            when (action) {
                Action.HEAVY -> heavy++
                Action.LIGHT -> light++
                Action.SAMPLE -> levelSamples++
                Action.NONE -> debounced++
            }
        }
    }

    private data class Sizes(
        val fileBytes: Long,
        val tableBytes: Map<String, Long>,
        val cappedShare: Map<String, Double>,
    )

    private companion object {
        const val TAG = "CoulombStress"
        const val SEED = 20_261_107L
        const val DAYS = 31
        const val MID_DAY = 16
        const val YEAR_DAYS = 365
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L
        const val EARLY_MS = 6 * 3_600_000L

        /** SPEC 7.7 says MB: decimal, the stricter reading (ADR-0021). */
        const val MB = 1_000_000.0

        /** 2026-11-01 12:00 at UTC+3: Coulomb is installed at noon, on battery, with the screen on. */
        const val START_WALL_MS = 1_793_491_200_000L + 9 * 3_600_000L
        val ZONE: ZoneOffset = ZoneOffset.ofHours(3)

        /** Stored history events a day at the 2026-11-07 checkpoint (an upper bound, docs/progress.md). */
        const val HISTORY_PER_DAY = 65_755.0

        /** New tags a day: about the 1,436 the phone stored in its first 17 h, less the ones seen before. */
        const val NEW_TAGS_PER_DAY = 600.0
        const val PACKAGE_BURSTS = 21
        const val SLEPT_SHARE = 0.2
        const val PERIODIC_MS = 30 * MINUTE_MS
        const val UNLOCK_MS = 3_000L
        const val REBOOT_DOWN_MS = 60_000L
        const val BOOT_RECEIVER_MS = 25_000L
        val REBOOT_DAYS = setOf(3, 10, 17, 24)
        const val NIGHT_REBOOT_DAY = 20
        val UPDATE_DAYS = setOf(8, 22)
        const val KILL_DAY = 13

        val CAPPED_TABLES =
            setOf(
                "battery_sample",
                "history_event",
                "history_gap",
                "history_tag",
                "uid_power",
                "wake_stat",
                "alarm_stat",
                "job_stat",
                "net_stat",
                "rail_energy",
                "uid_stat",
                "device_stat",
                "uid_component_power",
            )
        val DETAIL_TABLES = listOf("window_uid", "window_wake", "window_stat", "window_component")

        /** Rows that retention removes after 30 days, per table, as a condition on its row `x`. */
        val CAPPED_ROWS: Map<String, String> =
            DETAIL_TABLES.associateWith {
                "x.windowId IN (SELECT w.id FROM window_result w LEFT JOIN session s ON s.id = w.sessionId " +
                    "WHERE w.kind IN (${RecordCodes.WINDOW_CONSECUTIVE}, ${RecordCodes.WINDOW_REFERENCE}) OR " +
                    "(w.kind = ${RecordCodes.WINDOW_SESSION} AND s.kind = ${RecordCodes.SESSION_CHARGE}))"
            } +
                mapOf(
                    "window_result" to "x.kind = ${RecordCodes.WINDOW_CONSECUTIVE}",
                    "snapshot" to
                        "x.id NOT IN (SELECT startSnapshotId FROM session WHERE startSnapshotId IS NOT NULL) " +
                        "AND x.id NOT IN (SELECT endSnapshotId FROM session WHERE endSnapshotId IS NOT NULL) " +
                        "AND x.id NOT IN (SELECT snapshotId FROM reference) " +
                        "AND x.id NOT IN (SELECT fromSnapshotId FROM window_result WHERE kind <> ${RecordCodes.WINDOW_CONSECUTIVE}) " +
                        "AND x.id NOT IN (SELECT toSnapshotId FROM window_result WHERE kind <> ${RecordCodes.WINDOW_CONSECUTIVE})",
                )
        const val AGGREGATES = "sample_aggregate"
        const val RAW_DAYS = 14
        const val MINUTE_AGGREGATE_DAYS = 180
        const val TOP_TABLES = 12

        const val GC_ROUNDS = 3
        const val GC_PAUSE_MS = 200L
        const val WATCHDOG_PERIOD_MS = 1_000L
        const val ANR_MS = 5_000L

        // Pass marks (SPEC 16.3) and floors that keep the run from passing on too little load.
        const val MAX_HEAP_GROWTH = 0.10
        const val BUDGET_MB = 150.0
        const val MAX_MINUTES = 10.0

        /** 95% of the phone's 158 a day. */
        const val MIN_HEAVY_PER_DAY = 150
        const val MIN_HISTORY_ROWS = (0.8 * DAYS * HISTORY_PER_DAY).toLong()

        /** Root's kernel storage after a year, an upper bound (ADR-0029): the run is in shell mode. */
        const val ROOT_MB = 5.5

        /** 30 kept UIDs, Coulomb's own, OTHER_UID and DEVICE_UID (ADR-0035, Size). */
        const val MAX_COMPONENT_ROWS = 33L

        /** A third of the 30 days of consecutive windows kept at 150 a day: most of each day is on battery. */
        const val MIN_BROKEN_DOWN = 30 * 50L
    }
}

/** The stress test checks timing and storage, not names. */
private object NoLabels : AppLabels {
    override fun label(uid: Int): AppLabel? = null
}

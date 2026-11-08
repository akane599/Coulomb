// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.content.Context
import android.os.BatteryManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.CollectorClock
import io.github.akane599.coulomb.core.collect.snapshot.HistoryRead
import io.github.akane599.coulomb.core.collect.snapshot.LightReading
import io.github.akane599.coulomb.core.collect.snapshot.LightSource
import io.github.akane599.coulomb.core.collect.snapshot.SourceStatus
import io.github.akane599.coulomb.core.collect.snapshot.StatsRead
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.history.BatteryHistory
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import io.github.akane599.coulomb.core.model.history.HistoryRecord
import io.github.akane599.coulomb.core.model.stats.BatteryStatsDump
import io.github.akane599.coulomb.core.model.stats.DeviceMisc
import io.github.akane599.coulomb.core.model.stats.DeviceStats
import io.github.akane599.coulomb.core.model.stats.PowerSummary
import io.github.akane599.coulomb.core.model.stats.UidStats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Simulated days through the whole engine on an in-memory database (SPEC 16.3): snapshots,
 * sessions, windows and references, with fakes for the battery, the clock and the dumps.
 */
@RunWith(AndroidJUnit4::class)
class CollectorEngineTest {
    private lateinit var db: CoulombDatabase
    private val clock = FakeClock()
    private val device = FakeDevice(clock)
    private val stats = FakeStats(clock)
    private val identity = FakeIdentity()
    private val labels = FakeLabels(mutableMapOf(10_100 to AppLabel("Video", isSystem = false, iconPackage = "com.example.video")))

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CoulombDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun engine() = CollectorEngine(db, device, stats, identity, clock, MemoryBlobs(), labels)

    private suspend fun reliableCounter() =
        db.deviceDao().upsertProfile(
            DeviceProfileEntity(identity.fingerprint, "test", "test", 37, 3_700_000, null, null, 1, 1, true, 0, 0.95),
        )

    @Test
    fun anEightHourNightBecomesOneScreenOffSessionWithItsDrain() =
        runTest {
            reliableCounter()
            val engine = engine()
            device.set(hours = 22.0, level = 80, chargeMah = 4_000.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.SERVICE_STARTED)).isEqualTo(Action.HEAVY)

            device.set(hours = 22.5, level = 79, chargeMah = 3_980.0, screenOn = false)
            assertThat(engine.handle(SnapshotTrigger.SCREEN_OFF)).isEqualTo(Action.HEAVY)
            // The CPU sleeps 7.2 of the night's 8 hours.
            clock.sleep(hoursMs(7.2))
            for ((i, level) in (78 downTo 76).withIndex()) {
                device.set(hours = 24.0 + 2 * i, level = level, chargeMah = 3_980.0 - 50 * (i + 1), screenOn = false)
                assertThat(engine.handle(SnapshotTrigger.LEVEL_CHANGED)).isEqualTo(Action.SAMPLE)
            }
            device.set(hours = 30.5, level = 75, chargeMah = 3_820.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.SCREEN_ON)).isEqualTo(Action.HEAVY)

            val sessions = db.sessionDao().observeRecent(SessionKind.SCREEN_OFF, 10).first()
            val night = sessions.single()
            assertThat(night.closeReason).isEqualTo(SessionCloseReason.STATE_CHANGED)
            assertThat(night.startLevel).isEqualTo(79)
            assertThat(night.endLevel).isEqualTo(75)
            assertThat(night.chargeSource).isEqualTo(ChargeSource.CHARGE_COUNTER)
            assertThat(night.endElapsedNs!! - night.startElapsedNs).isEqualTo(hoursNs(8.0))
            // Uptime stops in suspend: awake 0.8 h of 8 h (ADR-0029 §3).
            assertThat(night.awakeRatio).isWithin(1e-9).of(0.1)

            val window = checkNotNull(db.windowDao().forSession(night.id))
            assertThat(window.kind).isEqualTo(WindowKind.SESSION)
            assertThat(window.measuredMah).isWithin(1e-6).of(160.0)
            assertThat(window.detailPruned).isTrue()
            // Three heavy snapshots, two consecutive windows; the level changes are samples only.
            assertThat(db.windowDao().observeRecent(WindowKind.CONSECUTIVE, 10).first()).hasSize(2)
            assertThat(count("SELECT COUNT(*) FROM snapshot")).isEqualTo(3)
            assertThat(count("SELECT COUNT(*) FROM battery_sample")).isEqualTo(3)
            val screenOff = checkNotNull(db.snapshotDao().autoReference(AutoReference.SINCE_SCREEN_OFF))
            assertThat(screenOff.snapshotId).isEqualTo(night.startSnapshotId)
        }

    @Test
    fun aChargeFromTwentyPercentAndTheResetAtUnplugAreRecorded() =
        runTest {
            reliableCounter()
            val engine = engine()
            device.set(hours = 10.0, level = 25, chargeMah = 1_250.0, screenOn = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            device.set(hours = 11.0, level = 20, chargeMah = 1_000.0, screenOn = true, plugged = true)
            engine.handle(SnapshotTrigger.POWER_CONNECTED)
            val plugIn = checkNotNull(db.snapshotDao().latestHeavy())
            for (minute in 1..60) {
                val level = (20 + minute * 80 / 60).coerceAtMost(100)
                device.set(
                    hours = 11.0 + minute / 60.0,
                    level = level,
                    chargeMah = 1_000.0 + minute * 66.0,
                    screenOn = true,
                    plugged = true,
                )
                engine.sample(device.read())
            }
            // The platform resets its statistics when unplugged this full (BatteryStatsImpl.shouldResetOnUnplugLocked).
            stats.resetAt(hours = 12.1)
            device.set(
                hours = 12.1,
                level = 100,
                chargeMah = 5_000.0,
                screenOn = true,
                plugged = false,
                status = BatteryManager.BATTERY_STATUS_FULL,
            )
            engine.handle(SnapshotTrigger.POWER_DISCONNECTED)

            val charge =
                db
                    .sessionDao()
                    .observeRecent(SessionKind.CHARGE, 10)
                    .first()
                    .single()
            assertThat(charge.closeReason).isEqualTo(SessionCloseReason.STATE_CHANGED)
            assertThat(charge.startLevel).isEqualTo(20)
            assertThat(charge.endLevel).isEqualTo(100)
            assertThat(charge.minutesAtOrAbove80).isIn(14..16)
            assertThat(charge.minutesAt100).isIn(0..1)
            val window = checkNotNull(db.windowDao().forSession(charge.id))
            assertThat(window.measuredMah).isWithin(1e-6).of(-4_000.0)
            assertThat(window.detailPruned).isFalse()

            val unplug = checkNotNull(db.snapshotDao().latestHeavy())
            assertThat(db.snapshotDao().autoReference(AutoReference.SINCE_UNPLUGGED)?.snapshotId).isEqualTo(unplug.id)
            assertThat(db.snapshotDao().autoReference(AutoReference.SINCE_FULL_CHARGE)?.snapshotId).isEqualTo(unplug.id)
            assertThat(db.snapshotDao().autoReference(AutoReference.BEFORE_RESET)?.snapshotId).isEqualTo(plugIn.id)
            assertThat(db.sessionDao().open().map { it.kind }).containsExactly(SessionKind.DISCHARGE, SessionKind.SCREEN_ON)
        }

    @Test
    fun aRebootClosesTheOpenSessionsWhereTheOldBootWasLastSeen() =
        runTest {
            val before = engine()
            device.set(hours = 5.0, level = 60, chargeMah = 3_000.0, screenOn = true)
            before.handle(SnapshotTrigger.SERVICE_STARTED)
            device.set(hours = 5.5, level = 59, chargeMah = 2_950.0, screenOn = true)
            before.sample(device.read())

            // A new process in a new boot: elapsed time restarts and BOOT_COUNT goes up.
            identity.bootCount = identity.bootCount + 1
            clock.wallOffsetMs += hoursMs(6.0)
            stats.reboot()
            val after = engine()
            device.set(hours = 0.1, level = 58, chargeMah = 2_900.0, screenOn = true)
            assertThat(after.handle(SnapshotTrigger.BOOT_COMPLETED)).isEqualTo(Action.HEAVY)

            val discharge = db.sessionDao().observeRecent(SessionKind.DISCHARGE, 10).first()
            val old = discharge.single { it.closeReason != null }
            assertThat(old.closeReason).isEqualTo(SessionCloseReason.REBOOT)
            assertThat(old.endLevel).isEqualTo(59)
            assertThat(old.endElapsedNs).isEqualTo(hoursNs(5.5))
            // The window still ends at the old boot's last heavy snapshot.
            assertThat(old.endSnapshotId).isEqualTo(old.startSnapshotId)
            assertThat(discharge.single { it.closeReason == null }.bootSeq).isEqualTo(old.bootSeq + 1)
            val sinceBoot = checkNotNull(db.snapshotDao().autoReference(AutoReference.SINCE_BOOT))
            assertThat(db.snapshotDao().snapshot(sinceBoot.snapshotId)?.bootSeq).isEqualTo(old.bootSeq + 1)
        }

    @Test
    fun aHeavySnapshotWithoutAccessIsLightAndOwedUntilDumpsWork() =
        runTest {
            stats.available = false
            val engine = engine()
            device.set(hours = 1.0, level = 50, chargeMah = 2_500.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.BOOT_COMPLETED)).isEqualTo(Action.LIGHT)
            assertThat(db.snapshotDao().latest()?.kind).isEqualTo(SnapshotKind.LIGHT)

            stats.available = true
            device.set(hours = 1.1, level = 50, chargeMah = 2_490.0, screenOn = true)
            engine.privilegesChanged()
            val heavy = checkNotNull(db.snapshotDao().latestHeavy())
            assertThat(heavy.trigger).isEqualTo(SnapshotTrigger.BOOT_COMPLETED)
            assertThat(heavy.sourceStatusJson).isEqualTo("""{"S5":"ok","S7":"failed"}""")
        }

    @Test
    fun appOpensWithinAMinuteShareOneHeavySnapshot() =
        runTest {
            val engine = engine()
            device.set(hours = 1.0, level = 50, chargeMah = 2_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.APP_OPENED)
            device.set(hours = 1.0 + 30.0 / 3600, level = 50, chargeMah = 2_499.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.APP_OPENED)).isEqualTo(Action.NONE)
            device.set(hours = 1.0 + 90.0 / 3600, level = 50, chargeMah = 2_498.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.PULL_TO_REFRESH)).isEqualTo(Action.HEAVY)
        }

    @Test
    fun aScreenThatAsksGetsReferenceWindowsAndOnlyTheNewestAreKept() =
        runTest {
            reliableCounter()
            val engine = engine()
            device.set(hours = 8.0, level = 70, chargeMah = 3_500.0, screenOn = true, plugged = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            device.set(hours = 9.0, level = 90, chargeMah = 4_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.POWER_DISCONNECTED)
            val unplug = checkNotNull(db.snapshotDao().latestHeavy())
            device.set(hours = 10.0, level = 85, chargeMah = 4_250.0, screenOn = false)
            engine.handle(SnapshotTrigger.SCREEN_OFF)
            // Background snapshots build none.
            assertThat(db.windowDao().ofKind(WindowKind.REFERENCE)).isEmpty()

            device.set(hours = 11.0, level = 80, chargeMah = 4_000.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.APP_OPENED)).isEqualTo(Action.HEAVY)
            val opened = checkNotNull(db.snapshotDao().latestHeavy())
            val first = db.windowDao().ofKind(WindowKind.REFERENCE)
            val today = checkNotNull(db.snapshotDao().autoReference(AutoReference.TODAY)).snapshotId
            assertThat(first.map { it.fromSnapshotId }).containsExactly(unplug.id, today)
            assertThat(first.map { it.toSnapshotId }.toSet()).containsExactly(opened.id)
            val sinceUnplug = first.single { it.fromSnapshotId == unplug.id }
            assertThat(sinceUnplug.measuredMah).isWithin(1e-6).of(500.0)
            assertThat(sinceUnplug.detailPruned).isFalse()

            // Debounced: no new snapshot, and the windows already end at the latest one.
            device.set(hours = 11.0 + 20.0 / 3600, level = 80, chargeMah = 3_995.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.APP_OPENED)).isEqualTo(Action.NONE)
            assertThat(db.windowDao().ofKind(WindowKind.REFERENCE).map { it.id }).isEqualTo(first.map { it.id })

            // A background snapshot moves the latest heavy snapshot on; a debounced refresh catches up to it.
            device.set(hours = 11.0 + 40.0 / 3600, level = 79, chargeMah = 3_950.0, screenOn = false)
            engine.handle(SnapshotTrigger.SCREEN_OFF)
            val screenOff = checkNotNull(db.snapshotDao().latestHeavy())
            device.set(hours = 11.0 + 50.0 / 3600, level = 79, chargeMah = 3_945.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.USER_PRESENT)).isEqualTo(Action.NONE)
            assertThat(engine.handle(SnapshotTrigger.PULL_TO_REFRESH)).isEqualTo(Action.NONE)
            val second = db.windowDao().ofKind(WindowKind.REFERENCE)
            assertThat(second).hasSize(2)
            assertThat(second.map { it.toSnapshotId }.toSet()).containsExactly(screenOff.id)
            assertThat(second.single { it.fromSnapshotId == unplug.id }.measuredMah).isWithin(1e-6).of(550.0)

            // Plugged in, "since unplugged" is the closed discharge session's window; only today remains.
            device.set(hours = 12.0, level = 78, chargeMah = 3_900.0, screenOn = true, plugged = true)
            engine.handle(SnapshotTrigger.POWER_CONNECTED)
            device.set(hours = 12.1, level = 79, chargeMah = 3_950.0, screenOn = true, plugged = true)
            engine.handle(SnapshotTrigger.PULL_TO_REFRESH)
            assertThat(db.windowDao().ofKind(WindowKind.REFERENCE).map { it.fromSnapshotId }).containsExactly(today)
        }

    @Test
    fun afterMidnightARefreshBuildsNoTodayWindowFromYesterday() =
        runTest {
            reliableCounter()
            val engine = engine()
            device.set(hours = 23.0, level = 90, chargeMah = 4_500.0, screenOn = true, plugged = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            device.set(hours = 23.5, level = 90, chargeMah = 4_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.POWER_DISCONNECTED)
            val unplug = checkNotNull(db.snapshotDao().latestHeavy())
            device.set(hours = 23.9, level = 86, chargeMah = 4_300.0, screenOn = true)
            engine.handle(SnapshotTrigger.APP_OPENED)
            device.set(hours = 23.99, level = 85, chargeMah = 4_250.0, screenOn = false)
            engine.handle(SnapshotTrigger.SCREEN_OFF)
            val lastOfDay = checkNotNull(db.snapshotDao().latestHeavy())

            // 46 s later, past midnight: debounced, so the windows are built to 23:59:24.
            device.set(hours = 24.0 + 10.0 / 3600, level = 85, chargeMah = 4_248.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.PULL_TO_REFRESH)).isEqualTo(Action.NONE)

            val windows = db.windowDao().ofKind(WindowKind.REFERENCE)
            assertThat(windows.map { it.fromSnapshotId }).containsExactly(unplug.id)
            assertThat(windows.single().toSnapshotId).isEqualTo(lastOfDay.id)
        }

    @Test
    fun appNamesAreWrittenWhenPackagesChangeAndKeptAfterAnUninstall() =
        runTest {
            val engine = engine()
            device.set(hours = 1.0, level = 50, chargeMah = 2_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            val meta = checkNotNull(db.deviceDao().uidMeta(10_100))
            assertThat(meta.label).isEqualTo("Video")
            assertThat(meta.isSystem).isFalse()
            assertThat(meta.iconCacheKey).isEqualTo("com.example.video")
            assertThat(meta.packagesJson).isEqualTo("""["com.example.video"]""")

            // Same day, same packages: no lookups.
            device.set(hours = 1.5, level = 49, chargeMah = 2_450.0, screenOn = false)
            engine.handle(SnapshotTrigger.SCREEN_OFF)
            assertThat(labels.lookups).isEqualTo(1)

            // The next day writes every UID again; one with nothing installed keeps its name.
            labels.installed.clear()
            device.set(hours = 25.0, level = 40, chargeMah = 2_000.0, screenOn = true)
            engine.handle(SnapshotTrigger.SCREEN_ON)
            assertThat(labels.lookups).isEqualTo(2)
            assertThat(db.deviceDao().uidMeta(10_100)?.label).isEqualTo("Video")
        }

    @Test
    fun aHistoryReadThatKeepsFailingGetsTheLongTimeoutOnlyEveryHalfHour() =
        runTest {
            val engine = engine()
            for (minutes in listOf(0, 10, 20, 31)) {
                device.set(hours = 1.0 + minutes / 60.0, level = 50, chargeMah = 2_500.0, screenOn = true)
                engine.handle(SnapshotTrigger.SCREEN_ON)
            }
            assertThat(stats.historyTimeoutsMs)
                .containsExactly(
                    CollectorEngine.CATCH_UP_TIMEOUT_MS,
                    CollectorEngine.SOURCE_TIMEOUT_MS,
                    CollectorEngine.SOURCE_TIMEOUT_MS,
                    CollectorEngine.CATCH_UP_TIMEOUT_MS,
                ).inOrder()
        }

    @Test
    fun aDumpDuringWhichTheCpuSleptIsStoredLightAndEndsNoWindow() =
        runTest {
            reliableCounter()
            val engine = engine()
            device.set(hours = 1.0, level = 50, chargeMah = 2_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            stats.sleepMs = 5_000
            device.set(hours = 1.5, level = 49, chargeMah = 2_450.0, screenOn = false)
            engine.handle(SnapshotTrigger.SCREEN_OFF)

            val slept = checkNotNull(db.snapshotDao().latest())
            assertThat(slept.kind).isEqualTo(SnapshotKind.LIGHT)
            assertThat(slept.sourceStatusJson).isEqualTo("""{"S5":"slept","S7":"failed"}""")
            assertThat(count("SELECT COUNT(*) FROM uid_power WHERE snapshotId = ${slept.id}")).isEqualTo(0)
            assertThat(db.windowDao().observeRecent(WindowKind.CONSECUTIVE, 10).first()).isEmpty()
            // The moment is still marked: the screen-off session and its reference start here.
            assertThat(db.snapshotDao().autoReference(AutoReference.SINCE_SCREEN_OFF)?.snapshotId).isEqualTo(slept.id)
        }

    @Test
    fun anIncompleteDumpIsLightAndCheckinSnapshotsNeverJoinProtoOnes() =
        runTest {
            val engine = engine()
            device.set(hours = 1.0, level = 50, chargeMah = 2_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            stats.statuses = mapOf("S5" to SourceStatus.PARTIAL)
            device.set(hours = 1.5, level = 49, chargeMah = 2_450.0, screenOn = true)
            engine.handle(SnapshotTrigger.SCREEN_ON_PERIODIC)
            val partial = checkNotNull(db.snapshotDao().latest())
            assertThat(partial.kind).isEqualTo(SnapshotKind.LIGHT)
            assertThat(partial.blobPath).isNotNull()

            stats.statuses = mapOf("S5" to SourceStatus.FAILED, "S6" to SourceStatus.OK)
            device.set(hours = 2.0, level = 48, chargeMah = 2_400.0, screenOn = true)
            engine.handle(SnapshotTrigger.SCREEN_ON_PERIODIC)
            assertThat(db.snapshotDao().latest()?.kind).isEqualTo(SnapshotKind.HEAVY)
            assertThat(db.windowDao().observeRecent(WindowKind.CONSECUTIVE, 10).first()).isEmpty()

            device.set(hours = 2.5, level = 47, chargeMah = 2_350.0, screenOn = true)
            engine.handle(SnapshotTrigger.SCREEN_ON_PERIODIC)
            assertThat(db.windowDao().observeRecent(WindowKind.CONSECUTIVE, 10).first()).hasSize(1)
        }

    @Test
    fun aHistoryReadCutShortGetsTheCatchUpAgainAndClaimsNoEvents() =
        runTest {
            val engine = engine()
            val placed = BatteryHistory(null, nextCursorMs = 1_000, firstRecordMs = null, recordCount = 0, gapBeforeFirstRecord = false)
            val cutShort = placed.copy(nextCursorMs = null)
            stats.history = { HistoryRead(emptyList(), placed, SourceStatus.OK) }
            device.set(hours = 1.0, level = 50, chargeMah = 2_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.SCREEN_ON)
            stats.history = { HistoryRead(emptyList(), cutShort, SourceStatus.PARTIAL) }
            for (minutes in listOf(10, 20, 31)) {
                device.set(hours = 1.0 + minutes / 60.0, level = 50, chargeMah = 2_500.0, screenOn = true)
                engine.handle(SnapshotTrigger.SCREEN_ON)
            }

            assertThat(stats.historyTimeoutsMs)
                .containsExactly(
                    CollectorEngine.CATCH_UP_TIMEOUT_MS,
                    CollectorEngine.SOURCE_TIMEOUT_MS,
                    CollectorEngine.SOURCE_TIMEOUT_MS,
                    CollectorEngine.CATCH_UP_TIMEOUT_MS,
                ).inOrder()
            val latest = checkNotNull(db.snapshotDao().latest())
            assertThat(latest.historyCursorMs).isNull()
            assertThat(latest.historyEventId).isNull()
        }

    @Test
    fun withoutAccessLevelChangesStaySamplesWhileTheHeavySnapshotIsOwed() =
        runTest {
            stats.available = false
            val engine = engine()
            device.set(hours = 1.0, level = 50, chargeMah = 2_500.0, screenOn = true)
            assertThat(engine.handle(SnapshotTrigger.SERVICE_STARTED)).isEqualTo(Action.LIGHT)
            for ((i, level) in (49 downTo 46).withIndex()) {
                device.set(hours = 1.1 + i / 10.0, level = level, chargeMah = 2_400.0 - 50 * i, screenOn = true)
                assertThat(engine.handle(SnapshotTrigger.LEVEL_CHANGED)).isEqualTo(Action.SAMPLE)
            }
            assertThat(count("SELECT COUNT(*) FROM snapshot")).isEqualTo(1)
            assertThat(count("SELECT COUNT(*) FROM battery_sample")).isEqualTo(4)
        }

    @Test
    fun aSessionClosedByARebootKeepsItsWindowWhenTheOldBootEndedOnALightSnapshot() =
        runTest {
            val before = engine()
            device.set(hours = 5.0, level = 60, chargeMah = 3_000.0, screenOn = true)
            before.handle(SnapshotTrigger.SERVICE_STARTED)
            device.set(hours = 5.5, level = 59, chargeMah = 2_950.0, screenOn = true)
            before.handle(SnapshotTrigger.SCREEN_ON_PERIODIC)
            val lastHeavy = checkNotNull(db.snapshotDao().latestHeavy())
            stats.available = false
            device.set(hours = 5.6, level = 59, chargeMah = 2_940.0, screenOn = true)
            assertThat(before.handle(SnapshotTrigger.APP_OPENED)).isEqualTo(Action.LIGHT)

            identity.bootCount = identity.bootCount + 1
            clock.wallOffsetMs += hoursMs(6.0)
            stats.reboot()
            stats.available = true
            val after = engine()
            device.set(hours = 0.1, level = 58, chargeMah = 2_900.0, screenOn = true)
            after.handle(SnapshotTrigger.BOOT_COMPLETED)

            val old =
                db
                    .sessionDao()
                    .observeRecent(SessionKind.DISCHARGE, 10)
                    .first()
                    .single { it.closeReason != null }
            assertThat(old.endElapsedNs).isEqualTo(hoursNs(5.6))
            assertThat(old.endSnapshotId).isEqualTo(lastHeavy.id)
            assertThat(db.windowDao().forSession(old.id)).isNotNull()
        }

    @Test
    fun referencesMoveOnlyOnARealUnplugOrScreenOff() =
        runTest {
            val before = engine()
            device.set(hours = 5.0, level = 60, chargeMah = 3_000.0, screenOn = true)
            before.handle(SnapshotTrigger.SERVICE_STARTED)
            // The first snapshot ever is no unplug.
            assertThat(db.snapshotDao().autoReference(AutoReference.SINCE_UNPLUGGED)).isNull()
            device.set(hours = 5.5, level = 59, chargeMah = 2_950.0, screenOn = true, plugged = true)
            before.handle(SnapshotTrigger.POWER_CONNECTED)
            device.set(hours = 6.0, level = 70, chargeMah = 3_500.0, screenOn = true)
            before.handle(SnapshotTrigger.POWER_DISCONNECTED)
            val unplug = checkNotNull(db.snapshotDao().latest())
            device.set(hours = 6.5, level = 69, chargeMah = 3_450.0, screenOn = false)
            before.handle(SnapshotTrigger.SCREEN_OFF)
            val screenOff = checkNotNull(db.snapshotDao().latest())

            // A reboot reopens both sessions, but nothing was plugged in and the screen stayed off.
            identity.bootCount = identity.bootCount + 1
            clock.wallOffsetMs += hoursMs(7.0)
            stats.reboot()
            device.set(hours = 0.1, level = 68, chargeMah = 3_400.0, screenOn = false)
            engine().handle(SnapshotTrigger.BOOT_COMPLETED)

            val references = db.snapshotDao()
            assertThat(references.autoReference(AutoReference.SINCE_UNPLUGGED)?.snapshotId).isEqualTo(unplug.id)
            assertThat(references.autoReference(AutoReference.SINCE_SCREEN_OFF)?.snapshotId).isEqualTo(screenOff.id)
            assertThat(references.autoReference(AutoReference.SINCE_BOOT)?.snapshotId).isEqualTo(references.latest()?.id)
        }

    @Test
    fun aChargeWhileCoulombWasNotRunningEndsTheDischargeSession() =
        runTest {
            device.set(hours = 1.0, level = 30, chargeMah = 1_500.0, screenOn = true)
            engine().handle(SnapshotTrigger.SERVICE_STARTED)

            // A new process in the same boot, unplugged again, with no history to show the charge.
            device.set(hours = 3.0, level = 80, chargeMah = 4_000.0, screenOn = true)
            engine().handle(SnapshotTrigger.SERVICE_STARTED)

            val discharges = db.sessionDao().observeRecent(SessionKind.DISCHARGE, 10).first()
            assertThat(discharges.single { it.closeReason != null }.closeReason).isEqualTo(SessionCloseReason.DATA_GAP)
            assertThat(discharges.single { it.closeReason == null }.startLevel).isEqualTo(80)
            assertThat(db.snapshotDao().autoReference(AutoReference.SINCE_UNPLUGGED)?.snapshotId)
                .isEqualTo(db.snapshotDao().latest()?.id)
        }

    @Test
    fun aPlugChangeInTheHistoryBeforeTheFirstSnapshotEverMovesNoReference() =
        runTest {
            // The first catch-up read: a time pairing, plugged at its start, unplugged an hour later.
            val records =
                listOf(
                    HistoryRecord(0, HistoryKind.RESET, HistoryChange.SET, value = START_WALL_MS + hoursMs(0.5)),
                    HistoryRecord(0, HistoryKind.PLUGGED, HistoryChange.START, baseline = true),
                    HistoryRecord(hoursMs(1.0), HistoryKind.PLUGGED, HistoryChange.STOP),
                )
            val read =
                BatteryHistory(
                    null,
                    nextCursorMs = 1_000_000 + hoursMs(2.0),
                    firstRecordMs = 1_000_000,
                    recordCount = 3,
                    gapBeforeFirstRecord = false,
                )
            stats.history = { HistoryRead(records, read, SourceStatus.OK) }
            device.set(hours = 5.0, level = 60, chargeMah = 3_000.0, screenOn = true)
            engine().handle(SnapshotTrigger.SERVICE_STARTED)

            assertThat(count("SELECT COUNT(*) FROM history_event")).isGreaterThan(0)
            assertThat(db.snapshotDao().autoReference(AutoReference.SINCE_UNPLUGGED)).isNull()
        }

    @Test
    fun aLevelRiseAcrossARebootIsNotTakenForACharge() =
        runTest {
            device.set(hours = 5.0, level = 60, chargeMah = 3_000.0, screenOn = true)
            engine().handle(SnapshotTrigger.SERVICE_STARTED)
            device.set(hours = 6.0, level = 59, chargeMah = 2_950.0, screenOn = true, plugged = true)
            engine().handle(SnapshotTrigger.SERVICE_STARTED)
            device.set(hours = 7.0, level = 61, chargeMah = 3_050.0, screenOn = true)
            engine().handle(SnapshotTrigger.SERVICE_STARTED)
            val unplug = checkNotNull(db.snapshotDao().latest())

            // The gauge re-estimates the level at boot: up 3 points, but no charge was seen.
            identity.bootCount = identity.bootCount + 1
            clock.wallOffsetMs += hoursMs(8.0)
            stats.reboot()
            device.set(hours = 0.1, level = 64, chargeMah = 3_200.0, screenOn = true)
            engine().handle(SnapshotTrigger.BOOT_COMPLETED)

            assertThat(db.snapshotDao().autoReference(AutoReference.SINCE_UNPLUGGED)?.snapshotId).isEqualTo(unplug.id)
        }

    @Test
    fun countersArePrunedHourlyOnBatteryToo() =
        runTest {
            val engine = engine()
            device.set(hours = 1.0, level = 90, chargeMah = 4_500.0, screenOn = true)
            engine.handle(SnapshotTrigger.SERVICE_STARTED)
            for (i in 1..8) {
                device.set(hours = 1.0 + i / 2.0, level = 90 - i, chargeMah = 4_500.0 - 50 * i, screenOn = true)
                engine.handle(SnapshotTrigger.SCREEN_ON_PERIODIC)
            }
            // At 5 h the cut is 3 h: 1.5, 2 and 2.5 h go; 1 h starts the open sessions and stays.
            assertThat(count("SELECT COUNT(*) FROM snapshot WHERE countersPruned = 1")).isEqualTo(3)
        }

    private fun count(query: String): Long =
        db.openHelper.readableDatabase.query(query).use {
            it.moveToFirst()
            it.getLong(0)
        }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.dao.WindowStat
import io.github.akane599.coulomb.core.database.dao.WindowWake
import io.github.akane599.coulomb.core.database.entity.ReferenceEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.UidMetaEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import io.github.akane599.coulomb.core.model.stats.UidKeys
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset

/** ADR-0036: a span's report from its stored window, or from the consecutive windows between its ends. */
@RunWith(AndroidJUnit4::class)
class DrainReaderTest {
    private lateinit var db: CoulombDatabase
    private lateinit var reader: DrainReader

    private val windows get() = db.windowDao()
    private val snapshots get() = db.snapshotDao()
    private val sessions get() = db.sessionDao()

    private val meta = listOf(UidMetaEntity(APP, "[\"com.example.halcyon\"]", "Halcyon", false, "com.example.halcyon", 0))

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CoulombDatabase::class.java).build()
        reader = DrainReader(windows, snapshots, sessions, db.deviceDao()) { ZoneOffset.UTC }
    }

    @After
    fun close() = db.close()

    @Test
    fun sinceUnpluggedReadsTheStoredWindowAndItsOwnApps() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000)
            val session = sessions.insert(discharge(a))
            val reference = reference(AutoReference.SINCE_UNPLUGGED, a)
            val stored =
                store(
                    window(
                        a,
                        c,
                        WindowKind.REFERENCE,
                    ).copy(measuredMah = 200.0, modeledMah = 180.0, screenOnMs = 600_000, screenOffMs = 1_400_000),
                    WindowRows(
                        uids = listOf(uid(APP, 100.0), uid(OTHER_APP, 50.0), uid(WindowUidEntity.OTHER_UID, 10.0)),
                        wakes = listOf(wake(4_000, 4)),
                    ),
                )
            // Wholly screen-off, then mixed: only the first says when the wakelock was held.
            store(window(a, b).copy(screenOnMs = 0, screenOffMs = 1_000_000), WindowRows(wakes = listOf(wake(3_000, 3))))
            store(window(b, c).copy(screenOnMs = 600_000, screenOffMs = 400_000), WindowRows(wakes = listOf(wake(1_000, 1))))

            val report =
                checkNotNull(
                    reader.read(DrainSpan.SinceUnplugged, DrainInputs(listOf(stored), listOf(reference), sessions.byId(session), meta)),
                )

            assertThat(report.drains.measuredMah).isEqualTo(200.0)
            assertThat(report.drains.measuredProvenance).isEqualTo(Provenance.MEASURED)
            assertThat(report.drains.modeledMah).isEqualTo(180.0)
            assertThat(report.drains.apps.map { it.uid to it.mah }).containsExactly(APP to 100.0, OTHER_APP to 50.0).inOrder()
            assertThat(
                report.drains.apps
                    .first()
                    .label,
            ).isEqualTo("Halcyon")
            assertThat(report.drains.otherMah).isEqualTo(10.0)
            assertThat(report.lowerBound).isFalse()
            assertThat(report.chargedInSpan).isFalse()
            assertThat(report.durationMs).isEqualTo(2_000_000)
            assertThat(report.screenOffMs).isEqualTo(1_400_000)
            // All on battery and all measured: the stored window's own time.
            assertThat(report.onBatteryMs).isEqualTo(2_000_000)
            assertThat(report.screenOffOnBatteryMs).isEqualTo(1_400_000)
            assertThat(report.measuredMs).isEqualTo(2_000_000)
            val wake = report.wakes.single()
            assertThat(wake.totalMs).isEqualTo(4_000)
            assertThat(wake.count).isEqualTo(4)
            assertThat(wake.label).isEqualTo("Halcyon")
            // Batterystats counts a partial wakelock only with the screen off: no share to show.
            assertThat(wake.screenOffShare).isNull()
            // The stored window read S5 at both ends and no root source.
            assertThat(report.wakeCoverage).isEqualTo(WakeCoverage(Coverage.WHOLE, Coverage.NONE, Coverage.NONE))
        }

    @Test
    fun aScreenOffShareIsKeptOnlyForRecordsCountedWithTheScreenOn() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000)
            val reason = { ms: Long, count: Int -> WindowWake(null, "425 ipa", WakeKind.WAKEUP_REASON, ms, count, null) }
            store(window(a, b).copy(screenOnMs = 0, screenOffMs = 1_000_000), WindowRows(wakes = listOf(wake(3_000, 3), reason(3_000, 3))))
            store(window(b, c).copy(screenOnMs = 1_000_000, screenOffMs = 0), WindowRows(wakes = listOf(reason(1_000, 1))))

            val report = checkNotNull(reader.read(DrainSpan.Last24Hours, empty()))

            assertThat(report.wakes.single { it.kind == WakeKind.PARTIAL }.screenOffShare).isNull()
            assertThat(report.wakes.single { it.kind == WakeKind.WAKEUP_REASON }.screenOffShare).isEqualTo(0.75)
            assertThat(report.onBatteryMs).isEqualTo(2_000_000)
            assertThat(report.screenOffOnBatteryMs).isEqualTo(1_000_000)
        }

    @Test
    fun aChainSaysWhichWakeSourcesItsWindowsCollected() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000)
            val root = KernelCoverage(Coverage.WHOLE, Coverage.WHOLE, Coverage.WHOLE)
            store(window(a, b).copy(modeledMah = 10.0, kernelCoverage = root))
            store(window(b, c).copy(modeledMah = 10.0))

            val report = checkNotNull(reader.read(DrainSpan.Last24Hours, empty()))

            // Root for the first window only: its kernel rows are a lower bound for the span.
            assertThat(report.wakeCoverage).isEqualTo(WakeCoverage(Coverage.WHOLE, Coverage.PARTIAL, Coverage.PARTIAL))
        }

    @Test
    fun theDevicesRowsBecomeComponentsByTheirFriendlyKind() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val session = sessions.insert(discharge(a))
            val reference = reference(AutoReference.SINCE_UNPLUGGED, a)
            val device = UidPowerEntity.DEVICE_UID
            val stored =
                store(
                    window(a, b, WindowKind.REFERENCE).copy(measuredMah = 100.0, modeledMah = 90.0),
                    WindowRows(
                        uids =
                            listOf(
                                uid(APP, 30.0),
                                uid(device, 40.0, "SCREEN"),
                                uid(device, 20.0, "CELL"),
                                uid(device, 5.0, "unnamed"),
                                uid(device, 3.0, "something_else"),
                                uid(device, 0.0, "WIFI"),
                            ),
                    ),
                )

            val report =
                checkNotNull(
                    reader.read(DrainSpan.SinceUnplugged, DrainInputs(listOf(stored), listOf(reference), sessions.byId(session), meta)),
                )

            // Unknown kinds share Other; a zero row is left out; largest first.
            assertThat(report.components.map { it.component to it.mah })
                .containsExactly(PowerComponent.SCREEN to 40.0, PowerComponent.MOBILE_RADIO to 20.0, PowerComponent.OTHER to 8.0)
                .inOrder()
        }

    @Test
    fun aSpanWithAChargeCountsOnlyItsDischarge() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000, plugType = 2)
            val d = heavy(4_000)
            store(window(a, b).copy(measuredMah = 120.0), WindowRows(uids = listOf(uid(APP, 30.0)), stats = listOf(alarms(APP, 3))))
            store(window(b, c).copy(measuredMah = 50.0, chargeSource = ChargeSource.LEVEL_DELTA), WindowRows(uids = listOf(uid(APP, 10.0))))
            store(window(c, d).copy(measuredMah = -300.0), WindowRows(stats = listOf(alarms(APP, 2))))

            val report = checkNotNull(reader.read(DrainSpan.Last24Hours, empty()))

            assertThat(report.drains.fromWallMs).isEqualTo(wall(a))
            assertThat(report.drains.toWallMs).isEqualTo(wall(d))
            assertThat(report.drains.measuredMah).isEqualTo(170.0)
            // One of the two discharge windows came from the level.
            assertThat(report.drains.measuredProvenance).isEqualTo(Provenance.ESTIMATED)
            assertThat(report.chargedInSpan).isTrue()
            // Three hours, one of them on the charger.
            assertThat(report.durationMs).isEqualTo(3 * 3_600_000L)
            assertThat(report.onBatteryMs).isEqualTo(2 * 3_600_000L)
            assertThat(report.screenOffOnBatteryMs).isEqualTo(2 * 3_600_000L)
            assertThat(report.measuredMs).isEqualTo(2 * 3_600_000L)
            assertThat(report.lowerBound).isTrue()
            assertThat(report.drains.apps.map { it.uid to it.mah }).containsExactly(APP to 40.0)
            assertThat(report.alarms.map { it.uid to it.wakeups }).containsExactly(APP to 5L)
        }

    @Test
    fun aChainWithLevelWindowsIsARangeOfOneStepForEachStretch() =
        runTest {
            val a = heavy(1_000, level = 80)
            val b = heavy(2_000, level = 78)
            val c = heavy(3_000, level = 75)
            val d = heavy(4_000, level = 74)
            val e = heavy(5_000, level = 73)
            // Two level windows in a row (50 mAh a point), the counter, then one more level window.
            store(window(a, b).copy(measuredMah = 100.0, chargeSource = ChargeSource.LEVEL_DELTA))
            store(window(b, c).copy(measuredMah = 150.0, chargeSource = ChargeSource.LEVEL_DELTA))
            store(window(c, d).copy(measuredMah = 40.0))
            store(window(d, e).copy(measuredMah = 50.0, chargeSource = ChargeSource.LEVEL_DELTA))

            val report = checkNotNull(reader.read(DrainSpan.Last24Hours, empty()))

            assertThat(report.drains.measuredMah).isEqualTo(340.0)
            assertThat(report.drains.measuredProvenance).isEqualTo(Provenance.ESTIMATED)
            // 300 mAh over 6 points: 50 a point, for each of the two stretches.
            assertThat(report.drains.levelEstimate).isEqualTo(LevelEstimate(100.0, stretches = 2, withCounter = true))
            assertThat(report.drains.measuredLowMah).isEqualTo(240.0)
            assertThat(report.drains.measuredHighMah).isEqualTo(440.0)
        }

    @Test
    fun aStoredLevelWindowGivesWayToTheChainThatCoversIt() =
        runTest {
            val a = heavy(1_000, level = 80)
            val b = heavy(2_000, level = 79)
            val c = heavy(3_000, level = 77)
            val session = sessions.insert(discharge(a))
            val reference = reference(AutoReference.SINCE_UNPLUGGED, a)
            // A reboot made the stored window level-based; the counter read both windows inside it.
            val stored =
                store(
                    window(a, c, WindowKind.REFERENCE).copy(
                        measuredMah = 150.0,
                        chargeSource = ChargeSource.LEVEL_DELTA,
                        screenOffMs = 2_000_000,
                    ),
                )
            store(window(a, b).copy(measuredMah = 45.0, screenOffMs = 1_000_000))
            store(window(b, c).copy(measuredMah = 95.0, screenOffMs = 1_000_000))

            val report =
                checkNotNull(
                    reader.read(DrainSpan.SinceUnplugged, DrainInputs(listOf(stored), listOf(reference), sessions.byId(session), meta)),
                )

            assertThat(report.drains.measuredMah).isEqualTo(140.0)
            assertThat(report.drains.measuredProvenance).isEqualTo(Provenance.MEASURED)
            assertThat(report.drains.levelEstimate).isNull()
        }

    @Test
    fun aStoredLevelWindowItsChainDoesNotCoverIsOneStep() =
        runTest {
            val a = heavy(1_000, level = 80)
            val c = heavy(3_000, level = 76)
            val session = sessions.insert(discharge(a))
            val reference = reference(AutoReference.SINCE_UNPLUGGED, a)
            val stored =
                store(
                    window(a, c, WindowKind.REFERENCE).copy(
                        measuredMah = 160.0,
                        chargeSource = ChargeSource.LEVEL_DELTA,
                        screenOffMs = 2_000_000,
                    ),
                )

            val report =
                checkNotNull(
                    reader.read(DrainSpan.SinceUnplugged, DrainInputs(listOf(stored), listOf(reference), sessions.byId(session), meta)),
                )

            assertThat(report.drains.measuredMah).isEqualTo(160.0)
            assertThat(report.drains.measuredProvenance).isEqualTo(Provenance.ESTIMATED)
            assertThat(report.drains.levelEstimate).isEqualTo(LevelEstimate(40.0, stretches = 1, withCounter = false))
        }

    @Test
    fun lastNightIsTheLongestScreenOffSessionOfTheDayWithItsAppsFromTheChain() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(1_000 + 2 * HOUR_S)
            val c = heavy(10_000)
            val d = heavy(10_000 + HOUR_S + 600)
            val e = heavy(20_000)
            val f = heavy(20_000 + 600)
            val night = sessions.insert(screenOff(a, b))
            sessions.insert(screenOff(c, d))
            // Too short to be a night.
            sessions.insert(screenOff(e, f))
            // Screen sessions keep no detail of their own.
            store(window(a, b, WindowKind.SESSION, night).copy(measuredMah = 80.0, detailPruned = true))
            store(window(a, b).copy(measuredMah = 80.0), WindowRows(uids = listOf(uid(APP, 20.0))))
            store(window(b, c))
            store(window(c, d))

            val report = checkNotNull(reader.read(DrainSpan.LastNight, empty()))

            assertThat(report.drains.fromWallMs).isEqualTo(wall(a))
            assertThat(report.drains.toWallMs).isEqualTo(wall(b))
            assertThat(report.drains.measuredMah).isEqualTo(80.0)
            assertThat(report.drains.apps.map { it.uid to it.mah }).containsExactly(APP to 20.0)
            assertThat(report.lowerBound).isTrue()
        }

    @Test
    fun aRangeOfDaysRunsFromItsFirstMidnightToTheNext() =
        runTest {
            val day = LocalDate.of(2026, 11, 1)
            val midnight = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val before = heavy(1_000, wallMs = midnight - 60_000)
            val a = heavy(2_000, wallMs = midnight + 3_600_000)
            val b = heavy(3_000, wallMs = midnight + 12 * 3_600_000)
            val c = heavy(4_000, wallMs = midnight + 25 * 3_600_000)
            heavy(5_000, wallMs = midnight + 30 * 3_600_000)
            store(window(before, a).copy(measuredMah = 999.0))
            store(window(a, b).copy(measuredMah = 40.0))
            store(window(b, c).copy(measuredMah = 60.0))

            val report = checkNotNull(reader.read(DrainSpan.Days(day, day), empty()))

            assertThat(report.drains.fromWallMs).isEqualTo(midnight + 3_600_000)
            assertThat(report.drains.toWallMs).isEqualTo(midnight + 25 * 3_600_000)
            assertThat(report.drains.measuredMah).isEqualTo(100.0)
            assertThat(report.drains.measuredProvenance).isEqualTo(Provenance.MEASURED)
        }

    @Test
    fun todayBeforeItsWindowIsStoredSumsTheChainToTheLatestSnapshot() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            store(window(a, b).copy(measuredMah = 25.0, modeledMah = 20.0), WindowRows(uids = listOf(uid(APP, 15.0))))

            val report = checkNotNull(reader.read(DrainSpan.Today, empty(listOf(reference(AutoReference.TODAY, a)))))

            assertThat(report.drains.measuredMah).isEqualTo(25.0)
            assertThat(report.drains.modeledMah).isEqualTo(20.0)
            assertThat(
                report.drains.apps
                    .single()
                    .mah,
            ).isEqualTo(15.0)
            assertThat(report.lowerBound).isTrue()
        }

    @Test
    fun aSpanWithoutTwoEndsHasNoReport() =
        runTest {
            assertThat(reader.read(DrainSpan.Today, empty())).isNull()
            assertThat(reader.read(DrainSpan.LastNight, empty())).isNull()
            val a = heavy(1_000)
            assertThat(reader.read(DrainSpan.Last24Hours, empty())).isNull()
            assertThat(reader.read(DrainSpan.Today, empty(listOf(reference(AutoReference.TODAY, a))))).isNull()
            assertThat(reader.read(DrainSpan.SinceUnplugged, empty())).isNull()
        }

    private fun empty(references: List<ReferenceEntity> = emptyList()) = DrainInputs(emptyList(), references, null, meta)

    private suspend fun heavy(
        elapsedS: Long,
        wallMs: Long = BASE_WALL + elapsedS * 1_000,
        plugType: Int = 0,
        level: Int = 80,
    ): Long =
        snapshots.insert(
            SnapshotEntity(
                bootSeq = 1,
                elapsedNs = elapsedS * NS_PER_S,
                wallMs = wallMs,
                trigger = SnapshotTrigger.SCREEN_OFF,
                kind = SnapshotKind.HEAVY,
                levelPct = level,
                chargeUah = 4_000_000,
                currentRaw = -250,
                voltageMv = 4_100,
                tempDeciC = 310,
                plugType = plugType,
                status = 3,
                statsStartWall = null,
                statsRealtimeMs = null,
                historyCursorMs = 0,
                historyEventId = null,
                sourceStatusJson = """{"S5":"ok"}""",
                blobPath = null,
                partial = false,
                countersPruned = false,
                durationMs = 900,
            ),
        )

    private suspend fun wall(id: Long): Long = checkNotNull(snapshots.snapshot(id)).wallMs

    private suspend fun reference(
        kind: AutoReference,
        snapshotId: Long,
    ): ReferenceEntity {
        snapshots.moveAutoReference(kind, snapshotId, 0)
        return checkNotNull(snapshots.autoReference(kind))
    }

    private suspend fun store(
        window: WindowResultEntity,
        rows: WindowRows = WindowRows(),
    ): WindowResultEntity = window.copy(id = windows.insert(window, rows))

    private suspend fun discharge(start: Long): SessionEntity = session(SessionKind.DISCHARGE, start, null)

    private suspend fun screenOff(
        start: Long,
        end: Long,
    ): SessionEntity = session(SessionKind.SCREEN_OFF, start, end)

    private suspend fun session(
        kind: SessionKind,
        start: Long,
        end: Long?,
    ): SessionEntity {
        val from = checkNotNull(snapshots.snapshot(start))
        val to = end?.let { checkNotNull(snapshots.snapshot(it)) }
        return SessionEntity(
            kind = kind,
            bootSeq = from.bootSeq,
            startElapsedNs = from.elapsedNs,
            endElapsedNs = to?.elapsedNs,
            startWall = from.wallMs,
            endWall = to?.wallMs,
            startSnapshotId = start,
            endSnapshotId = end,
            startLevel = 90,
            endLevel = null,
            startChargeUah = null,
            endChargeUah = null,
            chargeSource = null,
            avgCurrentMa = null,
            peakCurrentMa = null,
            minTempDeciC = null,
            meanTempDeciC = null,
            maxTempDeciC = 300,
            awakeRatio = null,
            deepIdleReached = null,
            deepIdleReachedMs = null,
            minutesAtOrAbove80 = null,
            minutesAt100 = null,
            stressIndex = null,
            cycleEquivalents = null,
            closeReason = to?.let { SessionCloseReason.STATE_CHANGED },
            quality = 0.0,
        )
    }

    private suspend fun SessionDao.byId(id: Long): SessionEntity? = open().firstOrNull { it.id == id }

    private fun window(
        from: Long,
        to: Long,
        kind: WindowKind = WindowKind.CONSECUTIVE,
        sessionId: Long? = null,
    ) = WindowResultEntity(
        kind = kind,
        sessionId = sessionId,
        fromSnapshotId = from,
        toSnapshotId = to,
        measuredMah = 10.0,
        chargeSource = ChargeSource.CHARGE_COUNTER,
        modeledMah = null,
        unattributedMah = null,
        screenOnMs = 0,
        screenOffMs = 3_600_000,
        awakeMsScreenOff = null,
        quality = 0.8,
        detailPruned = false,
    )

    private fun uid(
        uid: Int,
        mah: Double,
        component: String = WindowDrainsBuilder.ALL_COMPONENTS,
    ) = WindowUidEntity(
        0,
        uid,
        component,
        UidPowerEntity.ANY_STATE,
        WindowUidEntity.WHOLE_UID,
        mah,
        Provenance.MODELED,
    )

    private fun wake(
        totalMs: Long,
        count: Int,
    ) = WindowWake(APP, "*sync*", WakeKind.PARTIAL, totalMs, count, null)

    private fun alarms(
        uid: Int,
        wakeups: Long,
    ) = WindowStat(uid, UidKeys.ALARM_WAKEUPS, wakeups, Provenance.MEASURED)

    private companion object {
        const val APP = 10_001
        const val OTHER_APP = 10_002
        const val NS_PER_S = 1_000_000_000L
        const val HOUR_S = 3_600L

        /** Wall times near the snapshots' elapsed ones, a day back from a fixed instant. */
        const val BASE_WALL = 1_793_000_000_000L
    }
}

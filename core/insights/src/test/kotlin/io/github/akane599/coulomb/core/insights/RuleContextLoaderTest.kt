// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.dao.WindowStat
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.ChargeDetailEntity
import io.github.akane599.coulomb.core.database.entity.ChargerProfileEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.SourceStatusCodes
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowUid
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.stats.DeviceKeys
import io.github.akane599.coulomb.core.model.stats.UidKeys
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The loader reads the stored figures each rule stands on, and leaves out what would mislead it. */
@RunWith(AndroidJUnit4::class)
class RuleContextLoaderTest {
    private lateinit var db: CoulombDatabase
    private lateinit var loader: RuleContextLoader
    private val hour = 3_600_000L
    private val app = 10_123
    private val own = 10_511

    private val ports =
        object : InsightPorts {
            override val ownUid = own

            override suspend fun capacityMah() = 5000.0

            override fun lastUpdateWallMs(uid: Int): Long? = null
        }

    @Before
    fun open() {
        db = CoulombDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        loader = RuleContextLoader(db.snapshotDao(), db.windowDao(), db.sessionDao(), db.sampleDao(), ports)
    }

    @After
    fun close() = db.close()

    /** A heavy snapshot [atHours] into boot 1; the wall clock starts at [BASE_WALL]. */
    private suspend fun heavy(
        atHours: Double,
        plugType: Int = 0,
    ): SnapshotEntity {
        val ms = (atHours * hour).toLong()
        val snapshot =
            SnapshotEntity(
                bootSeq = 1,
                elapsedNs = ms * NS_PER_MS,
                wallMs = BASE_WALL + ms,
                trigger = SnapshotTrigger.SCREEN_OFF,
                kind = SnapshotKind.HEAVY,
                levelPct = 80,
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
                sourceStatus = SourceStatusCodes.fromJson("""{"S5":"ok"}"""),
                blobPath = null,
                partial = false,
                countersPruned = false,
                durationMs = 900,
            )
        return snapshot.copy(id = db.snapshotDao().insert(snapshot))
    }

    private suspend fun window(
        from: SnapshotEntity,
        to: SnapshotEntity,
        screenOnMs: Long = 0,
        kind: WindowKind = WindowKind.CONSECUTIVE,
        sessionId: Long? = null,
        measuredMah: Double? = null,
        modeledMah: Double? = null,
        stats: List<WindowStat> = emptyList(),
        uids: List<WindowUid> = emptyList(),
    ): Long {
        val ms = (to.elapsedNs - from.elapsedNs) / NS_PER_MS
        val row =
            WindowResultEntity(
                kind = kind,
                sessionId = sessionId,
                fromSnapshotId = from.id,
                toSnapshotId = to.id,
                measuredMah = measuredMah,
                chargeSource = measuredMah?.let { ChargeSource.CHARGE_COUNTER },
                modeledMah = modeledMah,
                unattributedMah = if (measuredMah != null && modeledMah != null) measuredMah - modeledMah else null,
                screenOnMs = screenOnMs,
                screenOffMs = ms - screenOnMs,
                awakeMsScreenOff = null,
                quality = 1.0,
                detailPruned = false,
            )
        return db.windowDao().insert(row, WindowRows(uids = uids, stats = stats))
    }

    private fun stat(
        uid: Int,
        key: String,
        value: Long,
    ) = WindowStat(uid, key, value, Provenance.MEASURED)

    private fun mah(
        uid: Int,
        component: String,
        value: Double,
    ) = WindowUid(0, uid, component, "", "", value, Provenance.MODELED)

    private suspend fun session(
        kind: SessionKind,
        from: SnapshotEntity,
        to: SnapshotEntity?,
        startLevel: Int = 90,
        endLevel: Int? = null,
        awakeRatio: Double? = null,
        minutesAt100: Int? = null,
    ): SessionEntity {
        val session =
            SessionEntity(
                kind = kind,
                bootSeq = from.bootSeq,
                startElapsedNs = from.elapsedNs,
                endElapsedNs = to?.elapsedNs,
                startWall = from.wallMs,
                endWall = to?.wallMs,
                startSnapshotId = from.id,
                endSnapshotId = to?.id,
                startLevel = startLevel,
                endLevel = endLevel,
                startChargeUah = null,
                endChargeUah = null,
                chargeSource = null,
                avgCurrentMa = null,
                peakCurrentMa = null,
                minTempDeciC = null,
                meanTempDeciC = null,
                maxTempDeciC = 300,
                awakeRatio = awakeRatio,
                deepIdleReached = null,
                deepIdleReachedMs = null,
                minutesAtOrAbove80 = null,
                minutesAt100 = minutesAt100,
                stressIndex = null,
                cycleEquivalents = null,
                closeReason = to?.let { SessionCloseReason.STATE_CHANGED },
                quality = 1.0,
            )
        return session.copy(id = db.sessionDao().insert(session))
    }

    @Test
    fun theDayCountsAppsOnBatteryAndKeepsScreenOffTimeApart() =
        runTest {
            val (a, b, c) = listOf(heavy(1.0), heavy(5.0), heavy(9.0))
            window(
                a,
                b,
                modeledMah = 100.0,
                stats =
                    listOf(
                        stat(app, UidKeys.PARTIAL_WAKELOCK_MS, 2 * hour),
                        stat(app, UidKeys.GNSS_MS, 12 * MINUTE),
                        stat(app, UidKeys.ALARM_WAKEUPS, 40),
                        stat(app, UidKeys.JOB_COUNT, 60),
                        stat(app, UidKeys.JOB_ABANDONED, 2),
                        stat(app, UidKeys.BLE_SCAN_MS, 5 * MINUTE),
                        stat(10_200, UidKeys.BLE_SCAN_MS, 30 * MINUTE),
                        // The system's own UID, the device's row of the same name and the rest row are not apps.
                        stat(1000, UidKeys.PARTIAL_WAKELOCK_MS, 3 * hour),
                        stat(-1, DeviceKeys.PARTIAL_WAKELOCK_MS, 3 * hour),
                        stat(-2, UidKeys.JOB_COUNT, 900),
                        stat(-1, DeviceKeys.SCREEN_OFF_REALTIME_MS, 4 * hour),
                        stat(-1, DeviceKeys.signalBin(0), hour),
                        stat(-1, DeviceKeys.signalBin(4), 3 * hour),
                    ),
                uids = listOf(mah(-1, "CELL", 20.0), mah(-1, "SCREEN", 30.0), mah(own, "all", 1.5), mah(app, "all", 40.0)),
            )
            // The screen was on for part of this window, so its GNSS and sensor time are not screen-off time.
            window(
                b,
                c,
                screenOnMs = hour,
                modeledMah = 100.0,
                stats = listOf(stat(app, UidKeys.PARTIAL_WAKELOCK_MS, hour), stat(app, UidKeys.GNSS_MS, 50 * MINUTE)),
                uids = listOf(mah(own, "all", 0.5)),
            )
            val day = checkNotNull(loader.load(c.wallMs, hour).day)
            assertThat(day.span).isEqualTo(Span(a.wallMs, c.wallMs))
            assertThat(day.apps).containsExactly(AppDayUse(app, 3 * hour, 40, 12 * MINUTE, 0, 60, abandonedJobs = 2))
            assertThat(day.screenOffMs).isEqualTo(4 * hour)
            assertThat(day.bleScanMs).isEqualTo(30 * MINUTE)
            assertThat(day.wifiScanMs).isEqualTo(0)
            assertThat(day.signal).isEqualTo(SignalUse(listOf(hour, 0, 0, 0, 3 * hour), 0.1))
            assertThat(day.self).isEqualTo(SelfCost(2.0, 5000.0))
        }

    @Test
    fun onlyLongScreenOffSessionsInsideADischargeAreJudged() =
        runTest {
            val (plugged, a, b) = listOf(heavy(0.0, plugType = 1), heavy(2.0), heavy(5.0))
            val (c, d, e) = listOf(heavy(5.5), heavy(5.6), heavy(8.0))
            session(SessionKind.DISCHARGE, a, e, endLevel = 70)
            // Began on the charger.
            session(SessionKind.SCREEN_OFF, plugged, b, endLevel = 95)
            val night = session(SessionKind.SCREEN_OFF, a, b, startLevel = 90, endLevel = 87, awakeRatio = 0.25)
            // Six minutes: under every rule's floor.
            session(SessionKind.SCREEN_OFF, c, d, endLevel = 86)
            val later = session(SessionKind.SCREEN_OFF, d, e, startLevel = 86, endLevel = 84)
            val nightWindow = window(a, b, kind = WindowKind.SESSION, sessionId = night.id, measuredMah = 150.0)
            window(a, b, stats = listOf(stat(-1, DeviceKeys.DEEP_DOZE_COUNT, 2)))
            window(d, e, modeledMah = 10.0)

            val found = loader.load(e.wallMs, hour).screenOffSessions
            assertThat(found.map { it.id }).containsExactly(night.id, later.id).inOrder()
            val first = found.first()
            assertThat(first.durationMs).isEqualTo(3 * hour)
            assertThat(first.awakeRatio).isEqualTo(0.25)
            // 150 mAh of 5000 is 3%, over three hours.
            assertThat(first.ratePctPerHour).isWithin(1e-9).of(1.0)
            assertThat(first.levelDropPct).isEqualTo(3)
            assertThat(first.deepIdleReached).isTrue()
            assertThat(first.windowId).isEqualTo(nightWindow)
            // No counter reading: the level change stands in. A chain with no deep Doze entry says so.
            assertThat(found.last().ratePctPerHour).isWithin(1e-9).of(2 / 2.4)
            assertThat(found.last().deepIdleReached).isFalse()
        }

    /** Audit BUG-058: windows with a stretch missing, or without the device's counters, cannot say "no deep Doze". */
    @Test
    fun aSessionTheWindowsDoNotWhollyCoverIsNotJudgedNo() =
        runTest {
            val (a, b) = listOf(heavy(0.0), heavy(1.0))
            val (c, d) = listOf(heavy(2.0), heavy(4.0))
            session(SessionKind.DISCHARGE, a, d, endLevel = 70)
            session(SessionKind.SCREEN_OFF, a, d, startLevel = 90, endLevel = 86)
            window(a, b, modeledMah = 10.0)
            window(c, d, modeledMah = 10.0)
            assertThat(
                loader
                    .load(d.wallMs, hour)
                    .screenOffSessions
                    .single()
                    .deepIdleReached,
            ).isNull()

            // The stretch is there now, but S5's device section was not read at its ends.
            window(b, c)
            assertThat(
                loader
                    .load(d.wallMs, hour)
                    .screenOffSessions
                    .single()
                    .deepIdleReached,
            ).isNull()
        }

    @Test
    fun aChargeThatMadeItsOwnProfileIsComparedWithTheChargerSeenBefore() =
        runTest {
            val (a, b, c) = listOf(heavy(1.0, plugType = 1), heavy(2.0, plugType = 1), heavy(3.0))
            val charge = session(SessionKind.CHARGE, a, b, minutesAt100 = 130)
            val sessions = db.sessionDao()
            val usual =
                sessions.insertChargerProfile(
                    ChargerProfileEntity(0, "Desk", 1, 25.0, BASE_WALL - 90 * hour, BASE_WALL - 20 * hour, null),
                )
            // Another plug type, and a profile first seen later, are not the charger seen before.
            sessions.insertChargerProfile(ChargerProfileEntity(0, "Pad", 4, 15.0, BASE_WALL - 90 * hour, BASE_WALL - hour, null))
            val fresh = sessions.insertChargerProfile(ChargerProfileEntity(0, "", 1, 9.0, charge.startWall, charge.endWall ?: 0, null))
            sessions.upsertChargeDetail(ChargeDetailEntity(charge.id, 1, fresh, 10.0, 9.0, null, 0, 0, null, null))
            for (minute in 0..2) {
                val ns = a.elapsedNs + minute * MINUTE * NS_PER_MS
                db.sampleDao().insert(
                    BatterySampleEntity(1, ns, a.wallMs + minute * MINUTE, 50, null, null, null, 4_200, 400 + minute, 1, 2, false, 0, 0),
                )
            }
            val context = loader.load(c.wallMs, hour)
            val loaded = context.chargeSessions.single()
            assertThat(loaded.avgW).isEqualTo(9.0)
            assertThat(loaded.baselineW).isEqualTo(25.0)
            assertThat(loaded.baselineName).isEqualTo("Desk")
            assertThat(loaded.chargerId).isEqualTo(fresh)
            assertThat(loaded.minutesAtFull).isEqualTo(130)
            assertThat(loaded.protectionOn).isNull()
            assertThat(loaded.samples.map { it.tempC }).containsExactly(40.0, 40.1, 40.2).inOrder()
            assertThat(loaded.samples.all { it.charging }).isTrue()
            assertThat(context.temperatures.single().tempC).isEqualTo(40.2)
            // A charge that matched a profile it did not create is compared with that profile.
            sessions.upsertChargeDetail(ChargeDetailEntity(charge.id, 1, usual, 26.0, 24.0, null, 0, 0, null, null))
            assertThat(
                loader
                    .load(c.wallMs, hour)
                    .chargeSessions
                    .single()
                    .baselineW,
            ).isEqualTo(25.0)
        }

    @Test
    fun anEmptyDatabaseGivesAnEmptyContext() =
        runTest {
            assertThat(loader.load(BASE_WALL, hour)).isEqualTo(RuleContext(BASE_WALL))
        }

    private companion object {
        const val BASE_WALL = 1_795_000_000_000L
        const val NS_PER_MS = 1_000_000L
        const val MINUTE = 60_000L
    }
}

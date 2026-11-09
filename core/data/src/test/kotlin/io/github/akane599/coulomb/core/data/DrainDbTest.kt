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
import io.github.akane599.coulomb.core.database.entity.PowerColumns
import io.github.akane599.coulomb.core.database.entity.ReferenceEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.SourceStatusCodes
import io.github.akane599.coulomb.core.database.entity.UidMetaEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.WindowComponentEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowUid
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.Breakdown
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
import io.github.akane599.coulomb.core.model.stats.DeviceKeys
import io.github.akane599.coulomb.core.model.stats.UidKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** A Drain database in memory, its reader and the fixtures its tests share (ADR-0036, ADR-0041). */
internal abstract class DrainDbTest {
    protected lateinit var db: CoulombDatabase
    protected lateinit var reader: DrainReader

    protected val windows get() = db.windowDao()
    protected val snapshots get() = db.snapshotDao()
    protected val sessions get() = db.sessionDao()

    protected val meta = listOf(UidMetaEntity(APP, "[\"com.example.halcyon\"]", "Halcyon", false, "com.example.halcyon", 0))

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CoulombDatabase::class.java).build()
        reader = DrainReader(windows, snapshots, sessions, db.deviceDao()) { ZoneOffset.UTC }
    }

    @After
    fun close() = db.close()

    protected fun empty(references: List<ReferenceEntity> = emptyList()) = DrainInputs(emptyList(), references, null, meta)

    protected suspend fun heavy(
        elapsedS: Long,
        wallMs: Long = BASE_WALL + elapsedS * 1_000,
        plugType: Int = 0,
        level: Int = 80,
        kind: SnapshotKind = SnapshotKind.HEAVY,
    ): Long =
        snapshots.insert(
            SnapshotEntity(
                bootSeq = 1,
                elapsedNs = elapsedS * NS_PER_S,
                wallMs = wallMs,
                trigger = SnapshotTrigger.SCREEN_OFF,
                kind = kind,
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
                sourceStatus = SourceStatusCodes.fromJson("""{"S5":"ok"}"""),
                blobPath = null,
                partial = false,
                countersPruned = false,
                durationMs = 900,
            ),
        )

    protected suspend fun wall(id: Long): Long = checkNotNull(snapshots.snapshot(id)).wallMs

    protected suspend fun reference(
        kind: AutoReference,
        snapshotId: Long,
    ): ReferenceEntity {
        snapshots.moveAutoReference(kind, snapshotId, 0)
        return checkNotNull(snapshots.autoReference(kind))
    }

    protected suspend fun store(
        window: WindowResultEntity,
        rows: WindowRows = WindowRows(),
    ): WindowResultEntity = window.copy(id = windows.insert(window, rows))

    protected suspend fun discharge(start: Long): SessionEntity = session(SessionKind.DISCHARGE, start, null)

    protected suspend fun screenOff(
        start: Long,
        end: Long,
    ): SessionEntity = session(SessionKind.SCREEN_OFF, start, end)

    protected suspend fun session(
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

    protected suspend fun SessionDao.byId(id: Long): SessionEntity? = open().firstOrNull { it.id == id }

    protected fun window(
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

    protected fun uid(
        uid: Int,
        mah: Double,
        component: String = WindowDrainsBuilder.ALL_COMPONENTS,
    ) = WindowUid(
        0,
        uid,
        component,
        UidPowerEntity.ANY_STATE,
        WindowUidEntity.WHOLE_UID,
        mah,
        Provenance.MODELED,
    )

    protected fun wake(
        totalMs: Long,
        count: Int,
    ) = WindowWake(APP, "*sync*", WakeKind.PARTIAL, totalMs, count, null)

    protected fun alarms(
        uid: Int,
        wakeups: Long,
    ) = WindowStat(uid, UidKeys.ALARM_WAKEUPS, wakeups, Provenance.MEASURED)

    /** A window's device rows: batterystats' time on battery, and with the screen off on battery. */
    protected fun device(
        onBatteryMs: Long,
        screenOffMs: Long,
    ) = listOf(
        WindowStat(UidPowerEntity.DEVICE_UID, DeviceKeys.BATTERY_REALTIME_MS, onBatteryMs, Provenance.MEASURED),
        WindowStat(UidPowerEntity.DEVICE_UID, DeviceKeys.SCREEN_OFF_REALTIME_MS, screenOffMs, Provenance.MEASURED),
    ).filter { it.value > 0 }

    companion object {
        const val APP = 10_001
        const val OTHER_APP = 10_002
        const val NS_PER_S = 1_000_000_000L
        const val HOUR_S = 3_600L

        /** Wall times near the snapshots' elapsed ones, a day back from a fixed instant. */
        const val BASE_WALL = 1_793_000_000_000L

        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L

        /** Long enough for Room's invalidation to re-run the queries. */
        const val SETTLE_MS = 500L
    }
}

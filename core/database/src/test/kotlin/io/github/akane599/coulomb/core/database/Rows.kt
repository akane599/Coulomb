// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import io.github.akane599.coulomb.core.database.dao.NewHistoryEvent
import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.database.entity.AlarmStatEntity
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.DeviceStatEntity
import io.github.akane599.coulomb.core.database.entity.JobStatEntity
import io.github.akane599.coulomb.core.database.entity.NetStatEntity
import io.github.akane599.coulomb.core.database.entity.RailEnergyEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.UidStatEntity
import io.github.akane599.coulomb.core.database.entity.WakeStatEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind

// Rows with plausible values; tests change only the fields they look at.

internal const val NS_PER_S = 1_000_000_000L

internal fun sample(
    bootSeq: Int,
    elapsedNs: Long,
    wallMs: Long = elapsedNs / 1_000_000,
    levelPct: Int = 80,
    currentUa: Long? = -250_000,
    tempDeciC: Int = 310,
) = BatterySampleEntity(
    bootSeq = bootSeq,
    elapsedNs = elapsedNs,
    wallMs = wallMs,
    levelPct = levelPct,
    chargeUah = 4_000_000,
    currentUa = currentUa,
    currentRaw = currentUa?.div(1_000),
    voltageMv = 4_100,
    tempDeciC = tempDeciC,
    plugType = 0,
    status = 3,
    screenOn = false,
    dozeState = 2,
    thermalStatus = 0,
)

internal fun snapshot(
    elapsedS: Long,
    bootSeq: Int = 1,
    kind: SnapshotKind = SnapshotKind.HEAVY,
    wallMs: Long = elapsedS * 1_000,
    blobPath: String? = "snapshots/$bootSeq-$elapsedS.gz",
    historyEventId: Long? = null,
) = SnapshotEntity(
    bootSeq = bootSeq,
    elapsedNs = elapsedS * NS_PER_S,
    wallMs = wallMs,
    trigger = SnapshotTrigger.SCREEN_OFF,
    kind = kind,
    levelPct = 80,
    chargeUah = 4_000_000,
    currentRaw = -250,
    voltageMv = 4_100,
    tempDeciC = 310,
    plugType = 0,
    status = 3,
    statsStartWall = null,
    statsRealtimeMs = null,
    historyCursorMs = 1_234,
    historyEventId = historyEventId,
    sourceStatusJson = """{"S5":"ok"}""",
    blobPath = blobPath,
    partial = false,
    countersPruned = false,
    durationMs = 900,
)

internal fun counters(snapshotId: Long = 0) =
    SnapshotRows(
        uidPower =
            listOf(
                UidPowerEntity(snapshotId, 10_123, "cpu", UidPowerEntity.ANY_STATE, 12.5, Provenance.MODELED),
                UidPowerEntity(snapshotId, UidPowerEntity.DEVICE_UID, "screen", UidPowerEntity.ANY_STATE, 3.0, Provenance.MODELED),
            ),
        wakeStats =
            listOf(
                WakeStatEntity(
                    snapshotId = snapshotId,
                    uid = 10_123,
                    tag = "sync",
                    kind = WakeKind.PARTIAL,
                    totalMs = 60_000,
                    count = 4,
                    maxMs = 30_000,
                    screenOffMs = null,
                ),
            ),
        alarmStats = listOf(AlarmStatEntity(snapshotId, 10_123, wakeups = 12, total = null)),
        jobStats = listOf(JobStatEntity(snapshotId, 10_123, jobs = 5, runtimeMs = 9_000, abandoned = null, topStopReason = "timeout")),
        netStats = listOf(NetStatEntity(snapshotId, 10_123, 1, 2, null, null, radioActiveMs = null)),
        railEnergy = listOf(RailEnergyEntity(snapshotId, "VSYS_PWR_DISPLAY", 9_000_000, readElapsedNs = 5)),
        uidStats = listOf(UidStatEntity(snapshotId, 10_123, "gps_ms", 4_000)),
        deviceStats = listOf(DeviceStatEntity(snapshotId, "deep_idle_ms", 70_000)),
    )

internal fun session(
    kind: SessionKind = SessionKind.DISCHARGE,
    bootSeq: Int = 1,
    startElapsedNs: Long = 1_000,
    startSnapshotId: Long? = null,
) = SessionEntity(
    kind = kind,
    bootSeq = bootSeq,
    startElapsedNs = startElapsedNs,
    endElapsedNs = null,
    startWall = 1_000,
    endWall = null,
    startSnapshotId = startSnapshotId,
    endSnapshotId = null,
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
    closeReason = null,
    quality = 0.0,
)

internal fun window(
    fromSnapshotId: Long,
    toSnapshotId: Long,
    kind: WindowKind = WindowKind.CONSECUTIVE,
    sessionId: Long? = null,
) = WindowResultEntity(
    kind = kind,
    sessionId = sessionId,
    fromSnapshotId = fromSnapshotId,
    toSnapshotId = toSnapshotId,
    measuredMah = 120.0,
    chargeSource = ChargeSource.CHARGE_COUNTER,
    modeledMah = 100.0,
    unattributedMah = 20.0,
    screenOnMs = 0,
    screenOffMs = 3_600_000,
    awakeMsScreenOff = 60_000,
    quality = 0.8,
    detailPruned = false,
)

internal fun event(
    bootSeq: Int,
    elapsedMs: Long,
    tag: String? = null,
    type: HistoryKind = HistoryKind.BATTERY_LEVEL,
    change: HistoryChange = HistoryChange.SET,
    baseline: Boolean = false,
) = NewHistoryEvent(
    bootSeq = bootSeq,
    elapsedMs = elapsedMs,
    type = type,
    change = change,
    baseline = baseline,
    uid = tag?.let { 10_123 },
    tag = tag,
    value = if (tag == null) 80 else null,
)

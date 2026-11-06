// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import androidx.room.withTransaction
import io.github.akane599.coulomb.core.analytics.CurrentCalibration
import io.github.akane599.coulomb.core.analytics.DeviceState
import io.github.akane599.coulomb.core.analytics.OpenSession
import io.github.akane599.coulomb.core.analytics.SessionRules
import io.github.akane599.coulomb.core.collect.CollectorClock
import io.github.akane599.coulomb.core.collect.history.HistoryIngest
import io.github.akane599.coulomb.core.collect.history.ReadEnd
import io.github.akane599.coulomb.core.collect.history.ReadInput
import io.github.akane599.coulomb.core.collect.history.ReadResult
import io.github.akane599.coulomb.core.collect.retention.Retention
import io.github.akane599.coulomb.core.collect.snapshot.CounterMapper
import io.github.akane599.coulomb.core.collect.snapshot.HeavyReader
import io.github.akane599.coulomb.core.collect.snapshot.HistoryRead
import io.github.akane599.coulomb.core.collect.snapshot.LightReading
import io.github.akane599.coulomb.core.collect.snapshot.LightSource
import io.github.akane599.coulomb.core.collect.snapshot.SourceStatus
import io.github.akane599.coulomb.core.collect.snapshot.StatsRead
import io.github.akane599.coulomb.core.collect.snapshot.sourceStatusJson
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.BootEntity
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.history.HistoryKind
import io.github.akane599.coulomb.core.model.stats.BatteryStatsDump
import java.time.Instant

/**
 * The collector's single consumer (SPEC 7.5, ADR-0018). Each call handles one trigger or sample
 * to the end before the next starts, so its state needs no locking. Not thread-safe: only the
 * actor calls it.
 */
internal class CollectorEngine(
    private val db: CoulombDatabase,
    private val light: LightSource,
    private val heavy: HeavySource,
    private val identity: DeviceIdentity,
    private val clock: CollectorClock,
    private val blobs: BlobStore,
) {
    private val writer = SnapshotWriter(db)
    private val retention = Retention(db, blobs)
    private val calibrator = CalibrationTracker(db.deviceDao(), db.sampleDao())

    private var boot: BootEntity? = null
    private var profile: DeviceProfileEntity? = null
    private var historyEnd: ReadEnd? = null
    private var historyEndLoaded = false
    private var lastHistoryFailed = false
    private var lastCatchUpNs: Long? = null
    private var firstOfProcess = true
    private var heavyOwed = false
    private var owedTrigger: SnapshotTrigger? = null
    private var lastHeavyElapsedNs: Long? = null
    private var lastRetentionElapsedNs: Long? = null
    private var capacityMah: Double? = null
    private var knownPackages: Map<Int, List<String>> = emptyMap()
    private var packagesDay: Long? = null

    /** The calibration new samples get (SPEC 6.1); null until one is found for this build. */
    @Volatile
    var calibration: CurrentCalibration? = null
        private set

    /** Handles one trigger and returns what it did. */
    suspend fun handle(trigger: SnapshotTrigger): Action {
        val reading = light.read()
        val boot = enterBoot(reading)
        val decision =
            TriggerPolicy.decide(
                PolicyInput(trigger, reading.elapsedNs, lastHeavyElapsedNs, heavy.available(), firstOfProcess, heavyOwed),
            )
        if (decision.heavyOwed && !heavyOwed) owedTrigger = trigger
        heavyOwed = decision.heavyOwed
        var action = decision.action
        if (action == Action.SAMPLE && sessionsWouldChange(reading, boot)) {
            action = if (heavy.available()) Action.HEAVY else Action.LIGHT
        }
        when (action) {
            Action.NONE -> return action
            Action.SAMPLE -> storeSample(reading, boot)
            Action.LIGHT, Action.HEAVY -> snapshot(trigger, reading, boot, action == Action.HEAVY)
        }
        firstOfProcess = false
        return action
    }

    /** Takes the heavy snapshot owed since dumps were unavailable, once they work (SPEC 7.2). */
    suspend fun privilegesChanged() {
        val owed = owedTrigger
        if (heavyOwed && owed != null && heavy.available()) handle(owed)
    }

    /** Stores a sampler's reading (SPEC 7.3). */
    suspend fun sample(reading: LightReading) {
        storeSample(reading, enterBoot(reading))
    }

    /** The boot, entered once per process: a process never outlives a reboot. */
    private suspend fun enterBoot(reading: LightReading): BootEntity {
        boot?.let { return it }
        val entered = db.deviceDao().enterBoot(identity.bootCount(), identity.fingerprint, reading.wallMs, reading.elapsedNs)
        boot = entered
        loadProfile()
        return entered
    }

    private suspend fun loadProfile() {
        if (profile?.fingerprint == identity.fingerprint) return
        val devices = db.deviceDao()
        val stored =
            devices.profile(identity.fingerprint)
                ?: DeviceProfileEntity(
                    fingerprint = identity.fingerprint,
                    model = identity.model,
                    oem = identity.oem,
                    sdkInt = identity.sdkInt,
                    sdkIntFull = identity.sdkIntFull,
                    designCapacityMah = null,
                    designCapacitySource = null,
                    currentScale = null,
                    currentSign = null,
                    chargeCounterReliable = null,
                    calibratedAtWall = null,
                    calibrationQuality = null,
                ).also { devices.upsertProfile(it) }
        profile = stored
        calibration = calibrator.stored(stored)
    }

    /** Whether the sessions no longer match the device, as after a missed broadcast. */
    private suspend fun sessionsWouldChange(
        reading: LightReading,
        boot: BootEntity,
    ): Boolean {
        if (reading.levelPct == null || reading.tempDeciC == null) return false
        val open = db.sessionDao().open().map { OpenSession(it.id, it.kind, it.bootSeq) }
        return SessionRules.reconcile(open, DeviceState(boot.bootSeq, reading.plugged, reading.screenOn)).isNotEmpty()
    }

    private suspend fun storeSample(
        reading: LightReading,
        boot: BootEntity,
    ) {
        val level = reading.levelPct ?: return
        val voltage = reading.voltageMv ?: return
        val temp = reading.tempDeciC ?: return
        val raw = reading.currentRaw
        db.sampleDao().insert(
            BatterySampleEntity(
                bootSeq = boot.bootSeq,
                elapsedNs = reading.elapsedNs,
                wallMs = reading.wallMs,
                levelPct = level,
                chargeUah = reading.chargeUah?.takeIf { it > 0 },
                currentUa = raw?.let { calibration?.currentUa(it) },
                currentRaw = raw,
                voltageMv = voltage,
                tempDeciC = temp,
                plugType = reading.plugType ?: 0,
                status = reading.status ?: 0,
                screenOn = reading.screenOn,
                dozeState = reading.dozeState,
                thermalStatus = reading.thermalStatus,
            ),
        )
    }

    private suspend fun snapshot(
        trigger: SnapshotTrigger,
        reading: LightReading,
        boot: BootEntity,
        wantHeavy: Boolean,
    ) {
        val startNs = clock.elapsedNs()
        val stats = if (wantHeavy) heavy.stats(SOURCE_TIMEOUT_MS) else null
        val dump = stats?.dump
        dump
            ?.device
            ?.powerSummary
            ?.capacityMah
            ?.takeIf { it > 0 }
            ?.let { capacityMah = it }
        val history = if (wantHeavy) history(startNs, boot) else null
        val ingest = (history as? HistoryOutcome.Read)?.result
        val statuses = stats?.statuses.orEmpty() + listOfNotNull(history?.let { HeavyReader.S7 to it.status })
        val rows = dump?.let(CounterMapper::rows) ?: SnapshotRows()
        val previousHeavy = db.snapshotDao().latestHeavy()
        val kind = if (dump != null) SnapshotKind.HEAVY else SnapshotKind.LIGHT
        val blob = if (kind == SnapshotKind.HEAVY) writeBlob(trigger, reading, boot, stats) else null
        val entity =
            entity(reading, boot, trigger, kind, dump).copy(
                historyCursorMs = ingest?.nextCursorMs,
                sourceStatusJson = sourceStatusJson(statuses),
                blobPath = blob,
                partial = statuses.values.any { it != SourceStatus.OK },
                durationMs = (clock.elapsedNs() - startNs) / NS_PER_MS,
            )
        val context =
            WriteContext(
                reading = reading,
                previousHeavy = previousHeavy,
                watched = !firstOfProcess,
                changedUnseen = if (firstOfProcess) unseenKinds(ingest) else emptySet(),
                counterReliable = profile?.chargeCounterReliable == true,
                capacityMah = capacityMah,
                currentUa = calibration?.let { c -> c::currentUa },
                zone = identity.zone(),
            )
        db.withTransaction {
            val eventId = ingest?.let { db.historyDao().insertRead(it.events, it.gaps) }
            val id = db.snapshotDao().insert(entity.copy(historyEventId = eventId), rows)
            ingest?.newOffsetMs?.let { db.deviceDao().setHistoryOffset(boot.bootSeq, it) }
            writer.afterSnapshot(entity.copy(id = id, historyEventId = eventId), rows, context)
            if (dump != null) recordPackages(dump, reading)
        }
        afterCommit(history, ingest, boot, kind, reading)
    }

    /** The light part of a snapshot row; the caller adds what the reads gave. */
    private fun entity(
        reading: LightReading,
        boot: BootEntity,
        trigger: SnapshotTrigger,
        kind: SnapshotKind,
        dump: BatteryStatsDump?,
    ) = SnapshotEntity(
        bootSeq = boot.bootSeq,
        elapsedNs = reading.elapsedNs,
        wallMs = reading.wallMs,
        trigger = trigger,
        kind = kind,
        levelPct = reading.levelPct,
        chargeUah = reading.chargeUah?.takeIf { it > 0 },
        currentRaw = reading.currentRaw,
        voltageMv = reading.voltageMv,
        tempDeciC = reading.tempDeciC,
        plugType = reading.plugType,
        status = reading.status,
        statsStartWall = dump?.device?.startClockTimeWallMs,
        statsRealtimeMs = dump?.device?.totalRealtimeMs,
        historyCursorMs = null,
        historyEventId = null,
        sourceStatusJson = "{}",
        blobPath = null,
        partial = false,
        countersPruned = false,
        durationMs = 0,
    )

    /**
     * S7 after S5. A catch-up read (no cursor yet, or the last read failed) gets its own timeout,
     * at most once per [CATCH_UP_EVERY_NS], so a dump that keeps hanging cannot cost it on every
     * snapshot. Any other read gets what the budget left.
     */
    private suspend fun history(
        startNs: Long,
        boot: BootEntity,
    ): HistoryOutcome {
        val cursorAt = db.snapshotDao().latestWithHistoryCursor()
        val leftMs = (BUDGET_NS - (clock.elapsedNs() - startNs)) / NS_PER_MS
        val catchUpDue = lastCatchUpNs.let { it == null || startNs - it >= CATCH_UP_EVERY_NS }
        return when {
            (cursorAt == null || lastHistoryFailed) && catchUpDue -> {
                lastCatchUpNs = startNs
                readHistory(cursorAt, CATCH_UP_TIMEOUT_MS, boot)
            }

            leftMs > 0 -> {
                readHistory(cursorAt, minOf(SOURCE_TIMEOUT_MS, leftMs), boot)
            }

            else -> {
                Skipped
            }
        }
    }

    private sealed interface HistoryOutcome {
        val status: SourceStatus

        data class Read(
            val read: HistoryRead,
            val result: ReadResult?,
        ) : HistoryOutcome {
            override val status: SourceStatus get() = read.status
        }
    }

    private data object Skipped : HistoryOutcome {
        override val status: SourceStatus = SourceStatus.SKIPPED
    }

    private suspend fun readHistory(
        cursorAt: SnapshotEntity?,
        timeoutMs: Long,
        boot: BootEntity,
    ): HistoryOutcome.Read {
        val cursor = cursorAt?.historyCursorMs ?: 0
        val before = clock.wallChanges()
        val read = heavy.history(cursor, timeoutMs)
        val nowNs = clock.elapsedNs()
        val wallMinusElapsed = clock.wallMs() - nowNs / NS_PER_MS
        val changed = clock.wallChanges() != before
        val parsed = read.history ?: return HistoryOutcome.Read(read, null)
        if (!historyEndLoaded) {
            historyEnd = cursorAt?.let { storedHistoryEnd(it, cursor) }
            historyEndLoaded = true
        }
        val input =
            ReadInput(
                read.records,
                parsed,
                cursor,
                historyEnd,
                boot.bootSeq,
                boot.historyOffsetMs,
                wallMinusElapsed,
                nowNs / NS_PER_MS,
                changed,
            )
        return HistoryOutcome.Read(read, HistoryIngest.ingest(input))
    }

    /**
     * After a process start, where the stored reads ended: the cursor's boot and offset, without
     * the state, which lives in memory only (ADR-0018).
     */
    private suspend fun storedHistoryEnd(
        last: SnapshotEntity,
        cursor: Long,
    ): ReadEnd? {
        if (cursor <= 0) return null
        val offset = db.deviceDao().boot(last.bootSeq)?.historyOffsetMs ?: return null
        val lastMonotonic = cursor - 1
        return ReadEnd(last.bootSeq, offset, lastMonotonic, (lastMonotonic - offset) * NS_PER_MS, state = null)
    }

    /** Plug and screen kinds whose history state flipped in a read Coulomb was not watching. */
    private fun unseenKinds(ingest: ReadResult?): Set<SessionKind> {
        val transitions = ingest?.transitions.orEmpty()
        return buildSet {
            if (HistoryKind.PLUGGED in transitions) addAll(listOf(SessionKind.CHARGE, SessionKind.DISCHARGE))
            if (HistoryKind.SCREEN in transitions) addAll(listOf(SessionKind.SCREEN_ON, SessionKind.SCREEN_OFF))
        }
    }

    /** The raw proto, kept for plug, boot, first-of-day and incomplete snapshots (ADR-0018). */
    private suspend fun writeBlob(
        trigger: SnapshotTrigger,
        reading: LightReading,
        boot: BootEntity,
        stats: StatsRead?,
    ): String? {
        val proto = stats?.rawProto
        val incomplete = stats?.rawIncomplete
        val wanted = trigger in BLOB_TRIGGERS || incomplete != null || firstHeavyOfDay(reading)
        if (!wanted || (proto == null && incomplete == null)) return null
        val parts =
            buildMap {
                proto?.let { put("s5.pb", it) }
                incomplete?.let { put("raw.txt", it) }
            }
        return blobs.write("snapshots/${boot.bootSeq}-${reading.elapsedNs}", parts)
    }

    private suspend fun firstHeavyOfDay(reading: LightReading): Boolean {
        val snapshots = db.snapshotDao()
        val today = snapshots.autoReference(AutoReference.TODAY)?.let { snapshots.snapshot(it.snapshotId) } ?: return true
        return localDay(today.wallMs) < localDay(reading.wallMs)
    }

    private fun localDay(wallMs: Long): Long =
        Instant
            .ofEpochMilli(wallMs)
            .atZone(identity.zone())
            .toLocalDate()
            .toEpochDay()

    /** UID to package names, written in full once a day and when they change. */
    private suspend fun recordPackages(
        dump: BatteryStatsDump,
        reading: LightReading,
    ) {
        val packages = CounterMapper.packages(dump)
        val day = localDay(reading.wallMs)
        val changed = if (packagesDay == day) packages.filter { (uid, names) -> knownPackages[uid] != names } else packages
        val devices = db.deviceDao()
        changed.forEach { (uid, names) -> devices.recordUidPackages(uid, names, reading.wallMs) }
        knownPackages = packages
        packagesDay = day
    }

    private suspend fun afterCommit(
        history: HistoryOutcome?,
        ingest: ReadResult?,
        boot: BootEntity,
        kind: SnapshotKind,
        reading: LightReading,
    ) {
        if (history is HistoryOutcome.Read) lastHistoryFailed = history.status == SourceStatus.FAILED
        if (ingest != null) {
            historyEnd = ingest.end
            ingest.newOffsetMs?.let { this.boot = boot.copy(historyOffsetMs = it) }
        }
        if (kind != SnapshotKind.HEAVY) return
        lastHeavyElapsedNs = reading.elapsedNs
        profile?.let { p ->
            val updated = calibrator.update(p, boot.bootSeq, reading.elapsedNs, reading.wallMs)
            if (updated !== p) {
                profile = updated
                calibration = calibrator.stored(updated)
            }
        }
        val last = lastRetentionElapsedNs
        if (reading.plugged && (last == null || reading.elapsedNs - last >= RETENTION_EVERY_NS)) {
            retention.run(this.boot ?: boot, reading.elapsedNs, reading.wallMs)
            lastRetentionElapsedNs = reading.elapsedNs
        }
    }

    companion object {
        /** SPEC 7.5: 2 s per source. */
        const val SOURCE_TIMEOUT_MS: Long = 2_000

        /** A first read, or one after a failure, may have the whole history buffer to read (ADR-0018). */
        const val CATCH_UP_TIMEOUT_MS: Long = 15_000

        /** The shortest spacing between two catch-up reads. */
        const val CATCH_UP_EVERY_NS: Long = 30 * 60_000_000_000L

        /** SPEC 7.5: a heavy snapshot is capped at 4 s; past it the history read is skipped. */
        const val BUDGET_NS: Long = 4_000_000_000L
        const val RETENTION_EVERY_NS: Long = 24 * 3_600_000_000_000L
        private const val NS_PER_MS = 1_000_000L

        private val BLOB_TRIGGERS =
            setOf(
                SnapshotTrigger.POWER_CONNECTED,
                SnapshotTrigger.POWER_DISCONNECTED,
                SnapshotTrigger.BOOT_COMPLETED,
                SnapshotTrigger.PACKAGE_REPLACED,
            )
    }
}

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
import io.github.akane599.coulomb.core.collect.snapshot.KernelFilesRead
import io.github.akane599.coulomb.core.collect.snapshot.KernelLogResult
import io.github.akane599.coulomb.core.collect.snapshot.KernelSnapshot
import io.github.akane599.coulomb.core.collect.snapshot.LightReading
import io.github.akane599.coulomb.core.collect.snapshot.LightSource
import io.github.akane599.coulomb.core.collect.snapshot.SourceStatus
import io.github.akane599.coulomb.core.collect.snapshot.StatsRead
import io.github.akane599.coulomb.core.collect.snapshot.counterSource
import io.github.akane599.coulomb.core.collect.snapshot.sourceStatusJson
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.BootEntity
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.UidMetaEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.PrivilegeMode
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.history.HistoryKind
import io.github.akane599.coulomb.core.model.stats.BatteryStatsDump
import java.time.Instant
import kotlin.math.abs

/**
 * The collector's single consumer (SPEC 7.5, ADR-0018). Each call handles one trigger or sample
 * to the end before the next starts, so its state needs no locking. Not thread-safe: only the
 * actor calls it. Each constructor parameter is a seam that tests fake, so the list is long by design.
 */
@Suppress("LongParameterList")
internal class CollectorEngine(
    private val db: CoulombDatabase,
    private val light: LightSource,
    private val heavy: HeavySource,
    private val identity: DeviceIdentity,
    private val clock: CollectorClock,
    private val blobs: BlobStore,
    private val labels: AppLabels,
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
    private var lastSnapshotElapsedNs: Long? = null
    private var lastRetentionElapsedNs: Long? = null
    private var lastCounterPruneElapsedNs: Long? = null
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
        val uptimeAtReadMs = clock.uptimeMs()
        val boot = enterBoot(reading)
        val decision =
            TriggerPolicy.decide(
                PolicyInput(trigger, reading.elapsedNs, lastSnapshotElapsedNs, heavy.available(), firstOfProcess, heavyOwed),
            )
        if (decision.heavyOwed && !heavyOwed) owedTrigger = trigger
        heavyOwed = decision.heavyOwed
        var action = decision.action
        if (action == Action.SAMPLE && sessionsWouldChange(reading, boot)) {
            action = if (heavy.available()) Action.HEAVY else Action.LIGHT
        }
        when (action) {
            Action.NONE -> Unit
            Action.SAMPLE -> storeSample(reading, boot)
            Action.LIGHT, Action.HEAVY -> snapshot(trigger, Read(reading, uptimeAtReadMs), boot, action == Action.HEAVY)
        }
        // A screen asked, but no heavy snapshot ran: the screen gets windows to the latest one (ADR-0032 §3).
        if (trigger in REFERENCE_TRIGGERS && action != Action.HEAVY) referencesToLatest(reading)
        if (action != Action.NONE) firstOfProcess = false
        return action
    }

    private suspend fun referencesToLatest(reading: LightReading) {
        val latest = db.snapshotDao().latestHeavy() ?: return
        val context = writeContext(reading, previousHeavy = null, unseen = emptySet()).copy(references = true)
        db.withTransaction { writer.references(latest, null, context) }
    }

    private fun writeContext(
        reading: LightReading,
        previousHeavy: SnapshotEntity?,
        unseen: Set<SessionKind>,
    ) = WriteContext(
        reading = reading,
        previousHeavy = previousHeavy,
        watched = !firstOfProcess,
        changedUnseen = unseen,
        counterReliable = profile?.chargeCounterReliable == true,
        capacityMah = capacityMah,
        currentUa = calibration?.let { c -> c::currentUa },
        zone = identity.zone(),
        ownUid = identity.appUid,
    )

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

    /** The light reading a trigger started with, and the uptime clock just after it. */
    private data class Read(
        val reading: LightReading,
        val uptimeMs: Long,
    )

    /**
     * One snapshot row, its counters and what follows from them, in one transaction (SPEC 7.5). It
     * is heavy only when the counters' source ([counterSource]) was read whole without the CPU
     * sleeping; otherwise it is stored light, without counters, and the statuses say why.
     *
     * On a root server the kernel reads join in (ADR-0029 §5): S5, then S25, S26 and S9, then S7,
     * then S27, all inside the budget. On any other server they are not due, and the snapshot's
     * mode says why.
     */
    private suspend fun snapshot(
        trigger: SnapshotTrigger,
        read: Read,
        boot: BootEntity,
        wantHeavy: Boolean,
    ) {
        val reading = read.reading
        val startNs = clock.elapsedNs()
        val stats = if (wantHeavy) sleepChecked(heavy.stats(SOURCE_TIMEOUT_MS), read) else null
        val dump = stats?.dump?.takeIf { counterSource(stats.statuses) != null }
        rememberCapacity(dump)
        val liveMode = heavy.liveMode()
        val kernelDue = wantHeavy && liveMode == PrivilegeMode.ROOT
        val files = kernelFiles(kernelDue, dump != null, startNs, read)
        val history = if (wantHeavy) history(startNs, boot) else null
        val log = kernelLog(kernelDue, files, startNs)
        val kernel = KernelSnapshot.combine(files, log, liveMode)
        val ingest = (history as? HistoryOutcome.Read)?.result
        val statuses = stats?.statuses.orEmpty() + listOfNotNull(history?.let { HeavyReader.S7 to it.status }) + kernel.statuses
        val rows = dump?.let(CounterMapper::rows) ?: SnapshotRows()
        val previousHeavy = db.snapshotDao().latestHeavy()
        val kind = if (dump != null) SnapshotKind.HEAVY else SnapshotKind.LIGHT
        val blob = stats?.let { writeBlob(trigger, reading, boot, it, kind == SnapshotKind.HEAVY) }
        val unseen = if (firstOfProcess) unseenKinds(ingest) + chargedUnseen(reading, boot) else emptySet()
        val entity =
            entity(reading, boot, trigger, kind, dump).copy(
                historyCursorMs = ingest?.nextCursorMs,
                sourceStatusJson = sourceStatusJson(statuses),
                blobPath = blob,
                partial = statuses.values.any { it != SourceStatus.OK },
                durationMs = (clock.elapsedNs() - startNs) / NS_PER_MS,
                uptimeMs = read.uptimeMs,
                privilegeMode = kernel.mode,
            )
        val context = writeContext(reading, previousHeavy, unseen).copy(references = trigger in REFERENCE_TRIGGERS)
        db
            .withTransaction {
                // A read that set no cursor stored nothing: the events before this snapshot are not all in.
                val eventId = ingest?.takeIf { it.nextCursorMs != null }?.let { db.historyDao().insertRead(it.events, it.gaps) }
                val id = db.snapshotDao().insert(entity.copy(historyEventId = eventId), rows, kernel.rows)
                ingest?.newOffsetMs?.let { db.deviceDao().setHistoryOffset(boot.bootSeq, it) }
                writer.afterSnapshot(entity.copy(id = id, historyEventId = eventId), rows, context)
                if (dump != null) recordPackages(dump, reading) else emptyMap()
            }.let { changed -> recordLabels(changed, reading) }
        afterCommit(history, ingest, boot, reading)
    }

    /**
     * Marks the counters' source [SourceStatus.SLEPT] when the CPU slept between the light read
     * and the dump's end: the instant the counters describe is then unknown by up to the sleep, so
     * they cannot end a window. Coulomb holds no wakelock on battery to prevent it (SPEC 7.4).
     */
    private fun sleepChecked(
        stats: StatsRead,
        read: Read,
    ): StatsRead {
        val source = counterSource(stats.statuses) ?: return stats
        return if (!slept(read)) stats else stats.copy(statuses = stats.statuses + (source to SourceStatus.SLEPT))
    }

    /** Whether the CPU slept for more than [MAX_SLEEP_MS] since the light read. */
    private fun slept(read: Read): Boolean {
        val elapsedMs = (clock.elapsedNs() - read.reading.elapsedNs) / NS_PER_MS
        val awakeMs = clock.uptimeMs() - read.uptimeMs
        return elapsedMs - awakeMs > MAX_SLEEP_MS
    }

    /** The capacity a usable dump's power summary reports, kept for the windows written after it. */
    private fun rememberCapacity(dump: BatteryStatsDump?) {
        dump
            ?.device
            ?.powerSummary
            ?.capacityMah
            ?.takeIf { it > 0 }
            ?.let { capacityMah = it }
    }

    /** What is left of the heavy snapshot's budget, in ms. */
    private fun leftMs(startNs: Long): Long = (BUDGET_NS - (clock.elapsedNs() - startNs)) / NS_PER_MS

    /**
     * S25, S26 and S9, right after S5 and only when its counters were usable: they are stored only on
     * heavy snapshots. They get the same sleep check as S5 (ADR-0029 §5). Null when not [due].
     */
    private suspend fun kernelFiles(
        due: Boolean,
        counters: Boolean,
        startNs: Long,
        read: Read,
    ): KernelFilesRead? {
        val leftMs = leftMs(startNs)
        return when {
            !due -> null
            !heavy.kernelReadable() -> KernelFilesRead.all(SourceStatus.FAILED)
            !counters || leftMs <= 0 -> KernelFilesRead.all(SourceStatus.SKIPPED)
            else -> heavy.kernelFiles(minOf(SOURCE_TIMEOUT_MS, leftMs)).let { if (slept(read)) it.slept() else it }
        }
    }

    /**
     * S27, last. Its cursor is the stored one for the files' kernel boot, or for the latest log
     * read's boot when the files gave none; never one kept in memory (ADR-0029 §5). The worker
     * applies it only within the running boot, else reads from the ring's oldest record; records
     * already stored are ignored on insert. Null when not [due].
     */
    private suspend fun kernelLog(
        due: Boolean,
        files: KernelFilesRead?,
        startNs: Long,
    ): KernelLogResult? {
        if (!due) return null
        if (!heavy.kernelReadable()) return KernelLogResult.of(SourceStatus.FAILED)
        val leftMs = leftMs(startNs)
        if (leftMs <= 0) return KernelLogResult.of(SourceStatus.SKIPPED)
        val bootKey = files?.bootKey ?: db.snapshotDao().latestKmsgBoot()
        val cursor = bootKey?.let { db.snapshotDao().kmsgCursor(it) }
        return heavy.kernelLog(bootKey ?: NO_BOOT, cursor ?: NO_CURSOR, minOf(SOURCE_TIMEOUT_MS, leftMs))
    }

    /**
     * After a process start in the same boot, a level well above the last unplugged one stored:
     * the device charged while Coulomb was not running, even when the history read does not show
     * it. Across a reboot the gauge may re-estimate the level, so nothing is inferred there.
     */
    private suspend fun chargedUnseen(
        reading: LightReading,
        boot: BootEntity,
    ): Set<SessionKind> {
        val level = reading.levelPct ?: return emptySet()
        if (reading.plugged) return emptySet()
        val snapshot = db.snapshotDao().latest()
        val sample = db.sampleDao().latest()
        val sampleIsLater =
            sample != null &&
                (
                    snapshot == null ||
                        sample.bootSeq > snapshot.bootSeq ||
                        (sample.bootSeq == snapshot.bootSeq && sample.elapsedNs > snapshot.elapsedNs)
                )
        // The latest stored reading, as (boot, plug type, level).
        val last =
            when {
                sampleIsLater -> Triple(sample.bootSeq, sample.plugType, sample.levelPct)
                snapshot != null -> Triple(snapshot.bootSeq, snapshot.plugType, snapshot.levelPct)
                else -> return emptySet()
            }
        val (lastBoot, lastPlug, lastLevel) = last
        if (lastBoot != boot.bootSeq || lastPlug != 0 || lastLevel == null) return emptySet()
        return if (level > lastLevel + LEVEL_NOISE) setOf(SessionKind.CHARGE, SessionKind.DISCHARGE) else emptySet()
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
        val leftMs = leftMs(startNs)
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
        val offsetBefore = clock.wallMs() - clock.elapsedNs() / NS_PER_MS
        val read = heavy.history(cursor, timeoutMs)
        val nowNs = clock.elapsedNs()
        val wallMinusElapsed = clock.wallMs() - nowNs / NS_PER_MS
        // The broadcast can arrive after the read; the clocks' own offset shows the change at once.
        val changed = clock.wallChanges() != before || abs(wallMinusElapsed - offsetBefore) > MAX_CLOCK_DRIFT_MS
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

    /**
     * The raw proto, kept for plug, boot and first-of-day heavy snapshots, and the raw text of an
     * incomplete read for diagnosis (ADR-0018).
     */
    private suspend fun writeBlob(
        trigger: SnapshotTrigger,
        reading: LightReading,
        boot: BootEntity,
        stats: StatsRead,
        heavy: Boolean,
    ): String? {
        val proto = stats.rawProto
        val incomplete = stats.rawIncomplete
        val wanted = incomplete != null || (heavy && (trigger in BLOB_TRIGGERS || firstHeavyOfDay(reading)))
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

    /** UID to package names, written in full once a day and when they change; returns those written. */
    private suspend fun recordPackages(
        dump: BatteryStatsDump,
        reading: LightReading,
    ): Map<Int, List<String>> {
        val packages = CounterMapper.packages(dump)
        val day = localDay(reading.wallMs)
        val changed = if (packagesDay == day) packages.filter { (uid, names) -> knownPackages[uid] != names } else packages
        val devices = db.deviceDao()
        changed.forEach { (uid, names) -> devices.recordUidPackages(uid, names, reading.wallMs) }
        knownPackages = packages
        packagesDay = day
        return changed
    }

    /**
     * ADR-0032 §5: names for the UIDs [recordPackages] wrote, after the commit so no package
     * lookup holds the database. A UID with nothing installed keeps the name it had.
     */
    private suspend fun recordLabels(
        changed: Map<Int, List<String>>,
        reading: LightReading,
    ) {
        if (changed.isEmpty()) return
        val meta =
            changed.mapNotNull { (uid, names) ->
                labels.label(uid)?.let { label ->
                    UidMetaEntity(
                        uid = uid,
                        packagesJson = names.joinToString(",", "[", "]") { "\"$it\"" },
                        label = label.label,
                        isSystem = label.isSystem,
                        iconCacheKey = label.iconPackage.orEmpty(),
                        updatedWall = reading.wallMs,
                    )
                }
            }
        if (meta.isNotEmpty()) db.deviceDao().upsertUidMeta(meta)
    }

    /** Work after any snapshot, light or heavy: a light one still brings samples to calibrate with. */
    private suspend fun afterCommit(
        history: HistoryOutcome?,
        ingest: ReadResult?,
        boot: BootEntity,
        reading: LightReading,
    ) {
        // A read that set no cursor (failed, or cut short before NEXT:) gets the catch-up again.
        if (history is HistoryOutcome.Read) lastHistoryFailed = history.result?.nextCursorMs == null
        if (ingest != null) {
            historyEnd = ingest.end
            ingest.newOffsetMs?.let { this.boot = boot.copy(historyOffsetMs = it) }
        }
        lastSnapshotElapsedNs = reading.elapsedNs
        profile?.let { p ->
            val updated = calibrator.update(p, boot.bootSeq, reading.elapsedNs, reading.wallMs)
            if (updated !== p) {
                profile = updated
                calibration = calibrator.stored(updated)
            }
        }
        val current = this.boot ?: boot
        if (due(lastCounterPruneElapsedNs, reading, COUNTER_PRUNE_EVERY_NS)) {
            retention.pruneCounters(current, reading.elapsedNs)
            lastCounterPruneElapsedNs = reading.elapsedNs
        }
        // SPEC 7.7: compaction and VACUUM only while charging and idle (the screen off).
        if (reading.plugged && !reading.screenOn && due(lastRetentionElapsedNs, reading, RETENTION_EVERY_NS)) {
            retention.run(current, reading.elapsedNs)
            lastRetentionElapsedNs = reading.elapsedNs
        }
    }

    private fun due(
        last: Long?,
        reading: LightReading,
        everyNs: Long,
    ) = last == null || reading.elapsedNs - last >= everyNs

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
        const val COUNTER_PRUNE_EVERY_NS: Long = 3_600_000_000_000L

        /** More sleep than this inside a read and its counters are not used (ADR-0018). */
        const val MAX_SLEEP_MS: Long = 1_000

        /** A wall-minus-elapsed change past this inside a read is a clock change, not rounding. */
        const val MAX_CLOCK_DRIFT_MS: Long = 1_000

        /** A level this far above the last one stored is a charge, not gauge noise. */
        private const val LEVEL_NOISE = 1

        /** No kernel boot or cursor: the worker reads S27 from the ring's oldest record. */
        private const val NO_BOOT = 0L
        private const val NO_CURSOR = -1L
        private const val NS_PER_MS = 1_000_000L

        /** A screen asked for these: they build the REFERENCE windows (ADR-0032 §3). */
        private val REFERENCE_TRIGGERS = setOf(SnapshotTrigger.APP_OPENED, SnapshotTrigger.PULL_TO_REFRESH)

        private val BLOB_TRIGGERS =
            setOf(
                SnapshotTrigger.POWER_CONNECTED,
                SnapshotTrigger.POWER_DISCONNECTED,
                SnapshotTrigger.BOOT_COMPLETED,
                SnapshotTrigger.PACKAGE_REPLACED,
            )
    }
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.os.BatteryManager
import io.github.akane599.coulomb.core.analytics.Attribution
import io.github.akane599.coulomb.core.analytics.DeviceState
import io.github.akane599.coulomb.core.analytics.OpenSession
import io.github.akane599.coulomb.core.analytics.SessionChange
import io.github.akane599.coulomb.core.analytics.SessionRules
import io.github.akane599.coulomb.core.collect.snapshot.KernelEnd
import io.github.akane599.coulomb.core.collect.snapshot.KernelReader
import io.github.akane599.coulomb.core.collect.snapshot.KernelWindow
import io.github.akane599.coulomb.core.collect.snapshot.KernelWindowRows
import io.github.akane599.coulomb.core.collect.snapshot.LightReading
import io.github.akane599.coulomb.core.collect.snapshot.SourceStatus
import io.github.akane599.coulomb.core.collect.snapshot.WindowBuilder
import io.github.akane599.coulomb.core.collect.snapshot.WindowEnd
import io.github.akane599.coulomb.core.collect.snapshot.WindowSettings
import io.github.akane599.coulomb.core.collect.snapshot.counterSource
import io.github.akane599.coulomb.core.collect.snapshot.sourceStatus
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.WindowKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the writer needs besides the snapshot itself. */
internal data class WriteContext(
    val reading: LightReading,
    /** The heavy snapshot before this one, read before it was stored. */
    val previousHeavy: SnapshotEntity?,
    /** Coulomb was not watching before this snapshot (its process just started). */
    val watched: Boolean,
    /** Kinds whose state changed unseen, from the history read (only when not [watched]). */
    val changedUnseen: Set<SessionKind>,
    val counterReliable: Boolean,
    /** For level-based drain (ADR-0018): the platform's own capacity estimate. */
    val capacityMah: Double?,
    /** Turns raw current into calibrated µA; null before calibration. */
    val currentUa: ((Long) -> Long)?,
    val zone: ZoneId,
    /** Coulomb's own UID (SPEC 6.9). */
    val ownUid: Int,
)

/**
 * The part of a snapshot's transaction after the snapshot row (ADR-0018): sessions, windows and
 * automatic references. Runs inside the caller's `withTransaction`. A window joins only heavy
 * snapshots whose counters came from the same source ([counterSource]): S5 and S6 disagree at the
 * same instant.
 */
internal class SnapshotWriter(
    db: CoulombDatabase,
) {
    private val snapshots = db.snapshotDao()
    private val sessions = db.sessionDao()
    private val windows = db.windowDao()
    private val samples = db.sampleDao()

    suspend fun afterSnapshot(
        stored: SnapshotEntity,
        rows: SnapshotRows,
        context: WriteContext,
    ) {
        val changes = reconcileSessions(stored, rows, context)
        moveReferences(stored, context, changes)
        if (stored.kind != SnapshotKind.HEAVY) return
        val previous = context.previousHeavy?.takeIf { hasCounters(it) && sameSource(it, stored) }
        if (previous != null) {
            insertWindow(
                WindowKind.CONSECUTIVE,
                null,
                listOf(WindowEnd(previous, snapshots.rows(previous.id)), WindowEnd(stored, rows)),
                context,
                true,
            )
        }
        day(stored, rows, context)
    }

    /** Applies [SessionRules] to the open sessions; returns the kinds opened and the sessions closed. */
    private suspend fun reconcileSessions(
        stored: SnapshotEntity,
        rows: SnapshotRows,
        context: WriteContext,
    ): Changes {
        val reading = context.reading
        val level = reading.levelPct
        val temp = reading.tempDeciC
        if (level == null || temp == null) return Changes()
        val open = sessions.open()
        val state = DeviceState(stored.bootSeq, reading.plugged, reading.screenOn, context.watched, changedUnseen = context.changedUnseen)
        val opened = mutableSetOf<SessionKind>()
        val closed = mutableListOf<SessionEntity>()
        for (change in SessionRules.reconcile(open.map { OpenSession(it.id, it.kind, it.bootSeq) }, state)) {
            when (change) {
                is SessionChange.Close -> {
                    closed += close(open.first { it.id == change.id }, change, stored, rows, context)
                }

                is SessionChange.Open -> {
                    sessions.insert(SessionSummary.open(change.kind, stored, level, temp))
                    opened += change.kind
                }
            }
        }
        return Changes(opened, closed)
    }

    private suspend fun close(
        session: SessionEntity,
        change: SessionChange.Close,
        stored: SnapshotEntity,
        rows: SnapshotRows,
        context: WriteContext,
    ): SessionEntity {
        val sameBoot = session.bootSeq == stored.bootSeq
        val sessionSamples =
            samples.samples(
                session.bootSeq,
                session.startElapsedNs,
                if (sameBoot) stored.elapsedNs + 1 else Long.MAX_VALUE,
            )
        val end =
            if (sameBoot) {
                val r = context.reading
                SessionEnd(
                    stored.elapsedNs,
                    stored.wallMs,
                    stored.takeIf {
                        it.kind == SnapshotKind.HEAVY
                    },
                    r.levelPct,
                    r.chargeUah,
                    r.tempDeciC,
                    change.reason,
                )
            } else {
                lastSeenIn(session, sessionSamples.lastOrNull(), change)
            }
        val closed = SessionSummary.close(session, end, sessionSamples, context.counterReliable, context.currentUa)
        sessions.update(closed)
        sessionWindow(closed, stored, rows, context)
        return closed
    }

    /**
     * A session of an earlier boot ends at the last thing Coulomb saw in that boot (SPEC 7.8). Its
     * window ends at that boot's last heavy snapshot, even when a later sample or light snapshot
     * sets the end time.
     */
    private suspend fun lastSeenIn(
        session: SessionEntity,
        lastSample: BatterySampleEntity?,
        change: SessionChange.Close,
    ): SessionEnd {
        val snapshot = snapshots.latestInBoot(session.bootSeq)?.takeIf { it.elapsedNs >= session.startElapsedNs }
        val heavy = snapshots.latestHeavyInBoot(session.bootSeq)?.takeIf { it.elapsedNs >= session.startElapsedNs }
        return if (lastSample != null && (snapshot == null || lastSample.elapsedNs > snapshot.elapsedNs)) {
            SessionEnd(
                lastSample.elapsedNs,
                lastSample.wallMs,
                heavy,
                lastSample.levelPct,
                lastSample.chargeUah,
                lastSample.tempDeciC,
                change.reason,
            )
        } else if (snapshot != null) {
            SessionEnd(
                snapshot.elapsedNs,
                snapshot.wallMs,
                heavy,
                snapshot.levelPct,
                snapshot.chargeUah,
                snapshot.tempDeciC,
                change.reason,
            )
        } else {
            SessionEnd(
                session.startElapsedNs,
                session.startWall,
                null,
                session.startLevel,
                session.startChargeUah,
                session.maxTempDeciC,
                change.reason,
            )
        }
    }

    /** SESSION window (ADR-0015): detail only for charge and discharge sessions. */
    private suspend fun sessionWindow(
        session: SessionEntity,
        stored: SnapshotEntity,
        rows: SnapshotRows,
        context: WriteContext,
    ) {
        val startId = session.startSnapshotId ?: return
        val endId = session.endSnapshotId ?: return
        if (startId == endId) return
        val start = snapshots.snapshot(startId) ?: return
        val end = if (endId == stored.id) stored else snapshots.snapshot(endId) ?: return
        val detail = session.kind == SessionKind.CHARGE || session.kind == SessionKind.DISCHARGE
        chain(start, end, rows.takeIf { end.id == stored.id })?.let { insertWindow(WindowKind.SESSION, session.id, it, context, detail) }
    }

    /** DAY window from the first heavy snapshot of the previous local day, then TODAY moves here. */
    private suspend fun day(
        stored: SnapshotEntity,
        rows: SnapshotRows,
        context: WriteContext,
    ) {
        val start = snapshots.autoReference(AutoReference.TODAY)?.let { snapshots.snapshot(it.snapshotId) }
        if (start != null && localDate(stored.wallMs, context.zone) <= localDate(start.wallMs, context.zone)) return
        if (start != null) chain(start, stored, rows)?.let { insertWindow(WindowKind.DAY, null, it, context, true) }
        snapshots.moveAutoReference(AutoReference.TODAY, stored.id, stored.wallMs)
    }

    /**
     * SPEC 7.6 references, on light snapshots too: a reference marks a moment, and one left on an
     * earlier unplug would be wrong, not just less detailed. Since unplugged and since screen off
     * move only on a real transition: a session of the opposite kind closed in the same reconcile,
     * or the history showed the state flip while Coulomb was not watching. A reopen after a reboot
     * or a data gap, or the first snapshot ever, leaves them where they are. Before reset needs
     * counters at both ends; today moves with the day window ([day]).
     */
    private suspend fun moveReferences(
        stored: SnapshotEntity,
        context: WriteContext,
        changes: Changes,
    ) {
        val sinceBoot = snapshots.autoReference(AutoReference.SINCE_BOOT)?.let { snapshots.snapshot(it.snapshotId) }
        if (sinceBoot == null || sinceBoot.bootSeq != stored.bootSeq) move(AutoReference.SINCE_BOOT, stored)
        if (changes.flipped(SessionKind.DISCHARGE, from = SessionKind.CHARGE, context.changedUnseen)) {
            move(AutoReference.SINCE_UNPLUGGED, stored)
            val charge = changes.closed.lastOrNull { it.kind == SessionKind.CHARGE }
            val full = charge != null && (charge.endLevel ?: 0) >= FULL_LEVEL
            if (full || context.reading.status == BatteryManager.BATTERY_STATUS_FULL) {
                move(AutoReference.SINCE_FULL_CHARGE, stored)
            }
        }
        if (changes.flipped(SessionKind.SCREEN_OFF, from = SessionKind.SCREEN_ON, context.changedUnseen)) {
            move(AutoReference.SINCE_SCREEN_OFF, stored)
        }
        context.previousHeavy?.takeIf { resetBetween(it, stored) }?.let { move(AutoReference.BEFORE_RESET, it) }
    }

    private fun resetBetween(
        previous: SnapshotEntity,
        stored: SnapshotEntity,
    ): Boolean =
        hasCounters(previous) &&
            hasCounters(stored) &&
            sameSource(previous, stored) &&
            Attribution.resetBetween(WindowBuilder.epochOf(previous), WindowBuilder.epochOf(stored))

    private fun sameSource(
        a: SnapshotEntity,
        b: SnapshotEntity,
    ): Boolean = counterSource(a.sourceStatusJson).let { it != null && it == counterSource(b.sourceStatusJson) }

    private suspend fun move(
        kind: AutoReference,
        snapshot: SnapshotEntity,
    ) = snapshots.moveAutoReference(kind, snapshot.id, snapshot.wallMs)

    /**
     * The window chain from [start] to [end]: the snapshots around each reset in between (see
     * [WindowBuilder.links]) with their counters. Null when an end has no counters any more.
     */
    private suspend fun chain(
        start: SnapshotEntity,
        end: SnapshotEntity,
        endRows: SnapshotRows? = null,
    ): List<WindowEnd>? {
        if (!hasCounters(start) || !hasCounters(end) || !sameSource(start, end)) return null
        // A link from the other source is left out; a reset next to it then makes the window partial.
        val between =
            snapshots
                .heavyWithCountersBetween(start.bootSeq, start.elapsedNs, end.bootSeq, end.elapsedNs)
                .filter { sameSource(it, end) }
        return WindowBuilder.links(listOf(start) + between + end).map { s ->
            WindowEnd(s, if (s.id == end.id && endRows != null) endRows else snapshots.rows(s.id))
        }
    }

    private fun hasCounters(snapshot: SnapshotEntity) = snapshot.kind == SnapshotKind.HEAVY && !snapshot.countersPruned

    private suspend fun insertWindow(
        kind: WindowKind,
        sessionId: Long?,
        chain: List<WindowEnd>,
        context: WriteContext,
        detail: Boolean,
    ) {
        val settings = WindowSettings(context.counterReliable, context.capacityMah, context.ownUid)
        val (window, rows) = WindowBuilder.build(kind, sessionId, chain, settings, detail)
        val kernel = if (detail) kernelRows(chain.first().snapshot, chain.last().snapshot) else null
        if (kernel == null) {
            windows.insert(window, rows)
        } else {
            windows.insert(
                window.copy(kernelCoverage = kernel.coverage),
                rows.copy(
                    wakes = rows.wakes + kernel.wakes,
                    stats =
                        rows.stats + kernel.stats,
                ),
            )
        }
    }

    /**
     * The window's root kernel rows (ADR-0029 §4), which are detail like the rest. Null when its
     * start read no kernel source: every kernel source needs the start's reading.
     */
    private suspend fun kernelRows(
        from: SnapshotEntity,
        to: SnapshotEntity,
    ): KernelWindowRows? {
        val start = KernelEnd(from, snapshots.kernelRead(from.id) ?: return null, sourcesIfOk(from))
        val end = KernelEnd(to, snapshots.kernelRead(to.id), sourcesIfOk(to))
        val reads = snapshots.kmsgReadsBetween(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)
        val range = KernelWindow.logRange(start, reads, end)
        val events = range?.let { snapshots.kernelLog(it.kernelBoot, it.afterSeq, it.lastSeq) }.orEmpty()
        return KernelWindow.build(start, end, range, events, sourcePath(from, to))
    }

    /**
     * The heavy snapshots with counters inside a window, for the S9 steps; none for a consecutive
     * window. One without an `ok` S9 read needs no rows to break the chain.
     */
    private suspend fun sourcePath(
        from: SnapshotEntity,
        to: SnapshotEntity,
    ): List<KernelEnd> =
        snapshots.heavyWithCountersBetween(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs).map {
            if (s9Ok(it)) KernelEnd(it, snapshots.kernelRead(it.id), snapshots.wakeupSources(it.id)) else KernelEnd(it, null, emptyList())
        }

    private suspend fun sourcesIfOk(snapshot: SnapshotEntity) = if (s9Ok(snapshot)) snapshots.wakeupSources(snapshot.id) else emptyList()

    private fun s9Ok(snapshot: SnapshotEntity) = sourceStatus(snapshot.sourceStatusJson, KernelReader.S9) == SourceStatus.OK

    private fun localDate(
        wallMs: Long,
        zone: ZoneId,
    ): LocalDate = Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate()

    private data class Changes(
        val opened: Set<SessionKind> = emptySet(),
        val closed: List<SessionEntity> = emptyList(),
    ) {
        /**
         * [to] opened after a [from] session closed here, or after a [to] session closed because
         * the history showed its state flip unseen. Without a stored session to close, as on the
         * first snapshot ever, a flip in the history is older than anything Coulomb watched.
         */
        fun flipped(
            to: SessionKind,
            from: SessionKind,
            unseen: Set<SessionKind>,
        ): Boolean = to in opened && (closed.any { it.kind == from } || (to in unseen && closed.any { it.kind == to }))
    }

    private companion object {
        const val FULL_LEVEL = 100
    }
}

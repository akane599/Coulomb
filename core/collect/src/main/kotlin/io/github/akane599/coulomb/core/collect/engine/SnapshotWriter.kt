// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import android.os.BatteryManager
import io.github.akane599.coulomb.core.analytics.Attribution
import io.github.akane599.coulomb.core.analytics.DeviceState
import io.github.akane599.coulomb.core.analytics.OpenSession
import io.github.akane599.coulomb.core.analytics.SessionChange
import io.github.akane599.coulomb.core.analytics.SessionRules
import io.github.akane599.coulomb.core.collect.snapshot.LightReading
import io.github.akane599.coulomb.core.collect.snapshot.WindowBuilder
import io.github.akane599.coulomb.core.collect.snapshot.WindowEnd
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
)

/**
 * The part of a snapshot's transaction after the snapshot row (ADR-0018): sessions, windows and
 * automatic references. Runs inside the caller's `withTransaction`.
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
        if (stored.kind != SnapshotKind.HEAVY) return
        val previous = context.previousHeavy?.takeIf { it.kind == SnapshotKind.HEAVY && !it.countersPruned }
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
        moveReferences(stored, context, changes)
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
     * window ends at that boot's last heavy snapshot, even when a later sample sets the end time.
     */
    private suspend fun lastSeenIn(
        session: SessionEntity,
        lastSample: BatterySampleEntity?,
        change: SessionChange.Close,
    ): SessionEnd {
        val snapshot = snapshots.latestInBoot(session.bootSeq)?.takeIf { it.elapsedNs >= session.startElapsedNs }
        return if (lastSample != null && (snapshot == null || lastSample.elapsedNs > snapshot.elapsedNs)) {
            SessionEnd(
                lastSample.elapsedNs,
                lastSample.wallMs,
                snapshot?.takeIf { it.kind == SnapshotKind.HEAVY },
                lastSample.levelPct,
                lastSample.chargeUah,
                lastSample.tempDeciC,
                change.reason,
            )
        } else if (snapshot != null) {
            SessionEnd(
                snapshot.elapsedNs,
                snapshot.wallMs,
                snapshot.takeIf { it.kind == SnapshotKind.HEAVY },
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

    private suspend fun moveReferences(
        stored: SnapshotEntity,
        context: WriteContext,
        changes: Changes,
    ) {
        val sinceBoot = snapshots.autoReference(AutoReference.SINCE_BOOT)?.let { snapshots.snapshot(it.snapshotId) }
        if (sinceBoot == null || sinceBoot.bootSeq != stored.bootSeq) move(AutoReference.SINCE_BOOT, stored)
        if (SessionKind.DISCHARGE in changes.opened) {
            move(AutoReference.SINCE_UNPLUGGED, stored)
            val charge = changes.closed.lastOrNull { it.kind == SessionKind.CHARGE }
            val full = charge != null && (charge.endLevel ?: 0) >= FULL_LEVEL
            if (full || context.reading.status == BatteryManager.BATTERY_STATUS_FULL) {
                move(AutoReference.SINCE_FULL_CHARGE, stored)
            }
        }
        if (SessionKind.SCREEN_OFF in changes.opened) move(AutoReference.SINCE_SCREEN_OFF, stored)
        context.previousHeavy?.takeIf { resetBetween(it, stored) }?.let { move(AutoReference.BEFORE_RESET, it) }
    }

    private fun resetBetween(
        previous: SnapshotEntity,
        stored: SnapshotEntity,
    ): Boolean =
        previous.kind == SnapshotKind.HEAVY && Attribution.resetBetween(WindowBuilder.epochOf(previous), WindowBuilder.epochOf(stored))

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
        if (!hasCounters(start) || !hasCounters(end)) return null
        val between = snapshots.heavyWithCountersBetween(start.bootSeq, start.elapsedNs, end.bootSeq, end.elapsedNs)
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
        val (window, rows) = WindowBuilder.build(kind, sessionId, chain, context.counterReliable, context.capacityMah, detail)
        windows.insert(window, rows)
    }

    private fun localDate(
        wallMs: Long,
        zone: ZoneId,
    ): LocalDate = Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate()

    private data class Changes(
        val opened: Set<SessionKind> = emptySet(),
        val closed: List<SessionEntity> = emptyList(),
    )

    private companion object {
        const val FULL_LEVEL = 100
    }
}

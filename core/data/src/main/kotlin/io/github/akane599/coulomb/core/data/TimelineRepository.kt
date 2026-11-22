// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.util.Log
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.database.dao.BootClock
import io.github.akane599.coulomb.core.database.dao.DeviceDao
import io.github.akane599.coulomb.core.database.dao.HistoryDao
import io.github.akane599.coulomb.core.database.dao.SampleDao
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.dao.SnapshotDao
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.CorruptChunkException
import io.github.akane599.coulomb.core.model.SessionKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Timeline's data (SPEC 10.6): the platform's battery history as tracks, with the current
 * Coulomb sampled. History is stored by boot and elapsed time; each boot's latest snapshot places
 * its events on the wall clock, so a clock change inside a boot moves that boot's events together.
 */
@Singleton
public class TimelineRepository
    @Inject
    internal constructor(
        private val history: HistoryDao,
        private val snapshots: SnapshotDao,
        private val samples: SampleDao,
        private val sessions: SessionDao,
        devices: DeviceDao,
        private val dispatchers: AppDispatchers,
    ) {
        /** The name of each UID that has one: who a top-app stretch or a wakelock belongs to. */
        public val labels: Flow<Map<Int, String>> =
            devices
                .observeUidMeta()
                .map { rows -> rows.filter { it.label.isNotBlank() }.associate { it.uid to it.label } }
                .distinctUntilChanged()

        /** Emits when a snapshot is stored: a span that reaches the present has more to show. */
        public val changes: Flow<Int> = snapshots.observeCount().distinctUntilChanged()

        /**
         * The span from [fromWallMs] to [toWallMs]. A history chunk that does not decode leaves the
         * span without history. The span is turned into boot and elapsed times by the same clock
         * that places what it shows, each boot's latest snapshot, so a clock corrected during a
         * boot neither hides its events nor shows its current on another axis (audit BUG-004).
         */
        public suspend fun read(
            fromWallMs: Long,
            toWallMs: Long,
        ): Timeline =
            withContext(dispatchers.io) {
                require(toWallMs > fromWallMs) { "a span ends after it starts" }
                val builder = TimelineBuilder(fromWallMs, toWallMs)
                val latest = snapshots.latest() ?: return@withContext builder.build(emptyList(), emptyList(), fromWallMs, emptyList())
                val clocks = BootTimes(snapshots.bootClocks(Int.MIN_VALUE, Int.MAX_VALUE))
                val from = clocks.key(fromWallMs)
                val to = clocks.key(toWallMs)
                // From the read before the span: its successor restates the state (ADR-0014).
                val start = snapshots.lastWithHistoryAtOrBefore(from.bootSeq, from.elapsedNs)
                val end = snapshots.firstAtOrAfter(to.bootSeq, to.elapsedNs)
                val stored =
                    history(
                        Key(start?.bootSeq ?: 0, start?.elapsedNs ?: 0),
                        Key(
                            end?.bootSeq ?: latest.bootSeq,
                            end?.elapsedNs ?: Long.MAX_VALUE,
                        ),
                    )
                val known = snapshots.lastWithHistoryAtOrBefore(Int.MAX_VALUE, Long.MAX_VALUE)
                val knownUntil = known?.let { clocks.wallAt(it.bootSeq, it.elapsedNs) } ?: fromWallMs
                val inSpan =
                    samples
                        .samplesFrom(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)
                        .filter { s -> clocks.wallAt(s.bootSeq, s.elapsedNs)?.let { it in fromWallMs until toWallMs } == true }
                val current = current(inSpan) { s -> clocks.wallAt(s.bootSeq, s.elapsedNs) ?: s.wallMs }
                builder.build(stored.events, stored.gaps, knownUntil, current)
            }

        /** Each boot's wall time at elapsed zero, by its latest snapshot, as [history] places events. */
        private class BootTimes(
            clocks: List<BootClock>,
        ) {
            private val boots = clocks.sortedBy { it.bootSeq }.map { it.bootSeq to it.wallMs - it.elapsedNs / NS_PER_MS }
            private val zero = boots.toMap()

            fun wallAt(
                bootSeq: Int,
                elapsedNs: Long,
            ): Long? = zero[bootSeq]?.plus(elapsedNs / NS_PER_MS)

            /** [wallMs] in the last boot that started by then; before every boot, the first one's start. */
            fun key(wallMs: Long): Key {
                val (boot, zeroMs) = boots.lastOrNull { it.second <= wallMs } ?: return Key(boots.first().first, 0)
                return Key(boot, (wallMs - zeroMs) * NS_PER_MS)
            }
        }

        /**
         * The screen-off session [sessionId] as a timeline (SPEC 10.7), on the axis [NightSummary]
         * dates it by. Read by the session's boot and elapsed times, so a wall clock that was wrong
         * during the session cannot move the read away from it. Null when the session is not a closed
         * screen-off session.
         */
        public suspend fun night(sessionId: Long): Timeline? =
            withContext(dispatchers.io) {
                val session = sessions.session(sessionId)?.takeIf { it.kind == SessionKind.SCREEN_OFF } ?: return@withContext null
                val endNs = session.endElapsedNs ?: return@withContext null
                val boot = session.bootSeq
                val start = snapshots.lastWithHistoryAtOrBefore(boot, session.startElapsedNs)
                val stored = history(Key(start?.bootSeq ?: boot, start?.elapsedNs ?: 0), Key(boot, endNs))
                val wallOfBoot = stored.bootWall[boot] ?: (session.startWall - session.startElapsedNs / NS_PER_MS)
                val from = wallOfBoot + session.startElapsedNs / NS_PER_MS
                val to = wallOfBoot + endNs / NS_PER_MS
                if (to <= from) return@withContext null
                val read = snapshots.lastWithHistoryAtOrBefore(boot, Long.MAX_VALUE)?.takeIf { it.bootSeq == boot }
                val knownUntil = read?.let { wallOfBoot + it.elapsedNs / NS_PER_MS } ?: from
                val current = current(samples.samples(boot, session.startElapsedNs, endNs)) { wallOfBoot + it.elapsedNs / NS_PER_MS }
                TimelineBuilder(from, to).build(stored.events, stored.gaps, knownUntil, current)
            }

        private class Key(
            val bootSeq: Int,
            val elapsedNs: Long,
        )

        private class Stored(
            val events: List<TimedEvent>,
            val gaps: List<LongRange>,
            /** Each boot's wall time at elapsed zero, by its latest snapshot. */
            val bootWall: Map<Int, Long>,
        )

        /** The history from [from] to [to], placed on the wall clock by each boot's latest snapshot. */
        private suspend fun history(
            from: Key,
            to: Key,
        ): Stored {
            val bootWall = snapshots.bootClocks(from.bootSeq, to.bootSeq).associate { it.bootSeq to it.wallMs - it.elapsedNs / NS_PER_MS }

            fun wall(
                bootSeq: Int,
                elapsedMs: Long,
            ): Long? = bootWall[bootSeq]?.let { it + elapsedMs }
            val events =
                try {
                    history.eventsOf(TimelineBuilder.KINDS, from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)
                } catch (e: CorruptChunkException) {
                    Log.w(TAG, "history from boot ${from.bootSeq} to ${to.bootSeq} does not decode; the span is drawn without it", e)
                    emptyList()
                }
            val gaps =
                history.gaps(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs).mapNotNull { gap ->
                    val start = wall(gap.startBootSeq, gap.startElapsedNs / NS_PER_MS) ?: return@mapNotNull null
                    val end = wall(gap.endBootSeq, gap.endElapsedNs / NS_PER_MS) ?: return@mapNotNull null
                    (start..end).takeIf { end > start }
                }
            val timed = events.mapNotNull { e -> wall(e.event.bootSeq, e.event.elapsedMs)?.let { TimedEvent(it, e.event, e.tag) } }
            return Stored(timed, gaps, bootWall)
        }

        /** The calibrated current of [samples], each at [wallOf] it; at most [MAX_CURRENT_POINTS], evenly taken. */
        private fun current(
            samples: List<BatterySampleEntity>,
            wallOf: (BatterySampleEntity) -> Long,
        ): List<CurrentPoint> {
            val points = samples.mapNotNull { s -> s.currentUa?.let { CurrentPoint(wallOf(s), it / UA_PER_MA) } }
            if (points.size <= MAX_CURRENT_POINTS) return points
            val step = points.size.toDouble() / MAX_CURRENT_POINTS
            return List(MAX_CURRENT_POINTS) { points[(it * step).toInt()] }
        }

        private companion object {
            const val TAG = "TimelineRepository"
            const val NS_PER_MS = 1_000_000L
            const val UA_PER_MA = 1_000.0
            const val MAX_CURRENT_POINTS = 2_000
        }
    }

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity

/**
 * One offset (wall minus elapsed) per boot: the latest sample's for its boot, otherwise the boot's
 * latest stored session end, or a session's start where none ended. It places a boot's elapsed times
 * on one wall clock, so lengths and overlaps come from the elapsed clock and a clock correction
 * moves a boot's sessions together (SPEC 7.8; audit BUG-003).
 */
internal class BootWalls private constructor(
    private val offsets: Map<Int, Long>,
) {
    fun wallAt(
        bootSeq: Int,
        elapsedNs: Long,
    ): Long? = offsets[bootSeq]?.let { it + Math.floorDiv(elapsedNs, NS_PER_MS) }

    /** [session] with its ends on its boot's clock; an end stored without its elapsed time stays as stored. */
    fun place(session: SessionEntity): SessionEntity {
        val start = wallAt(session.bootSeq, session.startElapsedNs) ?: return session
        val end = session.endElapsedNs?.let { wallAt(session.bootSeq, it) } ?: session.endWall
        return session.copy(startWall = start, endWall = end)
    }

    companion object {
        fun of(
            latest: BatterySampleEntity,
            sessions: List<SessionEntity>,
        ): BootWalls {
            val offsets = HashMap<Int, Long>()
            // Each boot's latest stored end, as (boot, elapsed, wall).
            sessions
                .mapNotNull { s -> s.endElapsedNs?.let { ns -> s.endWall?.let { wall -> Triple(s.bootSeq, ns, wall) } } }
                .groupBy { it.first }
                .forEach { (boot, ends) ->
                    val (_, ns, wall) = ends.maxBy { it.second }
                    offsets[boot] = wall - Math.floorDiv(ns, NS_PER_MS)
                }
            for (session in sessions) {
                offsets.putIfAbsent(
                    session.bootSeq,
                    session.startWall - Math.floorDiv(session.startElapsedNs, NS_PER_MS),
                )
            }
            offsets[latest.bootSeq] = latest.offsetMs()
            return BootWalls(offsets)
        }
    }
}

/**
 * Where this session started on its end's clock: the end's wall time less its elapsed length. A
 * clock correction during the session moves its start, never its length (SPEC 7.8; audit BUG-003).
 * Without an elapsed end, as stored.
 */
internal fun SessionEntity.startOnEndClock(): Long {
    val endNs = endElapsedNs ?: return startWall
    val end = endWall ?: return startWall
    return end - Math.floorDiv(endNs - startElapsedNs, NS_PER_MS)
}

/** The wall offset (wall minus elapsed) of the clock this session ended on; null while open. */
internal fun SessionEntity.endOffsetMs(): Long? {
    val endNs = endElapsedNs ?: return null
    val end = endWall ?: return null
    return end - Math.floorDiv(endNs, NS_PER_MS)
}

/** A reading's wall offset: wall minus elapsed. */
internal fun BatterySampleEntity.offsetMs(): Long = wallMs - Math.floorDiv(elapsedNs, NS_PER_MS)

private const val NS_PER_MS = 1_000_000L

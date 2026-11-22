// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity

/**
 * The level line since the unplug, for SPEC 10.2's scale. A flat run keeps only its first and last
 * reading, so a long discharge stays a few hundred points; a reboot starts a new segment, since the
 * phone was off in between. Pure.
 */
internal object LevelTrail {
    fun extend(
        points: List<LevelPoint>,
        samples: List<BatterySampleEntity>,
        lastBoot: Int?,
        at: (BatterySampleEntity) -> Long = { it.wallMs },
    ): Pair<List<LevelPoint>, Int?> {
        val out = points.toMutableList()
        var boot = lastBoot
        for (sample in samples) {
            val point = LevelPoint(at(sample), sample.levelPct, startsSegment = boot != null && sample.bootSeq != boot)
            boot = sample.bootSeq
            if (extendsFlatRun(out, point)) out[out.lastIndex] = point else out += point
        }
        return out to boot
    }

    /** The last two points and [point] share a level within one segment: [point] moves the run's end. */
    private fun extendsFlatRun(
        points: List<LevelPoint>,
        point: LevelPoint,
    ): Boolean {
        if (points.size < 2 || point.startsSegment) return false
        val last = points[points.lastIndex]
        val before = points[points.lastIndex - 1]
        return !last.startsSegment && last.levelPct == point.levelPct && before.levelPct == point.levelPct
    }
}

/**
 * Reads only the samples since the last call, so each new sample costs one short query instead of
 * the whole discharge. A discharge lies in one boot (a reboot closes it): the cache reads and keeps
 * it on the boot's elapsed clock and places it on the wall clock through the latest offset, so a
 * clock correction moves the line rather than stalling or reordering it (audit BUG-003). Not
 * thread-safe: one flow drives it, one value at a time.
 */
internal class LevelTrailCache {
    private var start: Pair<Int, Long>? = null
    private var readToNs = 0L

    /** The points with elapsed milliseconds in [LevelPoint.wallMs]. */
    private var points: List<LevelPoint> = emptyList()
    private var placedWith: Long? = null
    private var placed: List<LevelPoint> = emptyList()

    /** The trail of boot [bootSeq] from [startElapsedNs] to [nowElapsedNs], at wall = elapsed + [offsetMs]. */
    suspend fun read(
        bootSeq: Int,
        startElapsedNs: Long,
        nowElapsedNs: Long,
        offsetMs: Long,
        fetch: suspend (bootSeq: Int, fromElapsedNs: Long, toElapsedNs: Long) -> List<BatterySampleEntity>,
    ): List<LevelPoint> {
        if (start != bootSeq to startElapsedNs) {
            start = bootSeq to startElapsedNs
            readToNs = startElapsedNs
            points = emptyList()
            placedWith = null
        }
        val toNs = nowElapsedNs + 1
        if (toNs > readToNs) {
            val fresh = fetch(bootSeq, readToNs, toNs)
            readToNs = toNs
            if (fresh.isNotEmpty()) {
                points = LevelTrail.extend(points, fresh, bootSeq) { Math.floorDiv(it.elapsedNs, NS_PER_MS) }.first
                placedWith = null
            }
        }
        if (placedWith != offsetMs) {
            placed = points.map { it.copy(wallMs = it.wallMs + offsetMs) }
            placedWith = offsetMs
        }
        return placed
    }

    fun clear() {
        start = null
        points = emptyList()
        placedWith = null
        placed = emptyList()
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
    }
}

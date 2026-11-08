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
    ): Pair<List<LevelPoint>, Int?> {
        val out = points.toMutableList()
        var boot = lastBoot
        for (sample in samples) {
            val point = LevelPoint(sample.wallMs, sample.levelPct, startsSegment = boot != null && sample.bootSeq != boot)
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
 * the whole discharge. Not thread-safe: one flow drives it, one value at a time.
 */
internal class LevelTrailCache {
    private var startWallMs: Long? = null
    private var readToWallMs = 0L
    private var lastBoot: Int? = null
    private var points: List<LevelPoint> = emptyList()

    suspend fun read(
        startWallMs: Long,
        nowWallMs: Long,
        fetch: suspend (fromWall: Long, toWall: Long) -> List<BatterySampleEntity>,
    ): List<LevelPoint> {
        if (this.startWallMs != startWallMs) {
            this.startWallMs = startWallMs
            readToWallMs = startWallMs
            lastBoot = null
            points = emptyList()
        }
        val toWall = nowWallMs + 1
        if (toWall <= readToWallMs) return points
        val (next, boot) = LevelTrail.extend(points, fetch(readToWallMs, toWall), lastBoot)
        points = next
        lastBoot = boot
        readToWallMs = toWall
        return points
    }

    fun clear() {
        startWallMs = null
        points = emptyList()
    }
}

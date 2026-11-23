// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.dao

import io.github.akane599.coulomb.core.database.entity.SnapshotEntity

/** A moment in boot and elapsed time, the order every stored row keeps (SPEC 7.8). */
public data class BootKey(
    val bootSeq: Int,
    val elapsedNs: Long,
)

/**
 * Each boot's wall time at elapsed zero, by its latest snapshot: the clock the Timeline places
 * history on (ADR-0056 §2 and §8) and a span picks its ends by (ADR-0036 §2, audit BUG-011). Within
 * a boot it moves with the elapsed clock, so a wall clock that ran ahead and was set back cannot
 * make an old snapshot look later than a new one.
 */
public class BootTimes(
    clocks: List<BootClock>,
) {
    private val boots = clocks.sortedBy { it.bootSeq }.map { it.bootSeq to it.wallMs - it.elapsedNs / NS_PER_MS }
    private val zero = boots.toMap()

    /** The wall time of (boot, elapsed) on that boot's latest clock; null for a boot without a snapshot. */
    public fun wallAt(
        bootSeq: Int,
        elapsedNs: Long,
    ): Long? = zero[bootSeq]?.plus(elapsedNs / NS_PER_MS)

    /**
     * [wallMs] in the last boot that had started by then: a later boot's start ends the one before.
     * Before every boot, the first one's start. Null with no snapshot.
     */
    public fun key(wallMs: Long): BootKey? {
        val first = boots.firstOrNull() ?: return null
        val (boot, zeroMs) = boots.lastOrNull { it.second <= wallMs } ?: return BootKey(first.first, 0)
        return BootKey(boot, (wallMs - zeroMs) * NS_PER_MS)
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
    }
}

/** Each boot's clock by its latest snapshot. */
public suspend fun SnapshotDao.bootTimes(): BootTimes = BootTimes(bootClocks(Int.MIN_VALUE, Int.MAX_VALUE))

/** [ms] before [to] on [to]'s boot's latest clock, as a boot and elapsed time. */
public suspend fun SnapshotDao.keyBefore(
    to: SnapshotEntity,
    ms: Long,
): BootKey? {
    val clocks = bootTimes()
    return clocks.key((clocks.wallAt(to.bootSeq, to.elapsedNs) ?: to.wallMs) - ms)
}

/**
 * The first heavy snapshot within [ms] before [to]: where "the last day" up to [to] starts. [to]'s
 * own wall stamp is not used, so one taken before a clock correction does not move the day.
 */
public suspend fun SnapshotDao.firstHeavyWithin(
    ms: Long,
    to: SnapshotEntity,
): SnapshotEntity? = keyBefore(to, ms)?.let { firstHeavyAtOrAfter(it.bootSeq, it.elapsedNs) }

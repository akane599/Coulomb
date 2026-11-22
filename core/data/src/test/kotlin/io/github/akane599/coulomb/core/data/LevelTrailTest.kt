// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LevelTrailTest {
    private fun sample(
        wallMs: Long,
        level: Int,
        boot: Int = 1,
    ) = BatterySampleEntity(
        bootSeq = boot,
        elapsedNs = wallMs * 1_000_000,
        wallMs = wallMs,
        levelPct = level,
        chargeUah = null,
        currentUa = -410_000,
        currentRaw = null,
        voltageMv = 3_900,
        tempDeciC = 300,
        plugType = 0,
        status = 3,
        screenOn = true,
        dozeState = 0,
        thermalStatus = 0,
    )

    @Test
    fun aFlatRunKeepsItsFirstAndLastReading() {
        val samples = listOf(sample(0, 80), sample(5, 80), sample(10, 80), sample(15, 80), sample(20, 79))

        val (points, _) = LevelTrail.extend(emptyList(), samples, lastBoot = null)

        assertThat(points.map { it.wallMs to it.levelPct }).containsExactly(0L to 80, 15L to 80, 20L to 79).inOrder()
    }

    @Test
    fun aRebootStartsANewSegment() {
        val samples = listOf(sample(0, 80), sample(5, 80), sample(10, 80, boot = 2), sample(15, 80, boot = 2))

        val (points, boot) = LevelTrail.extend(emptyList(), samples, lastBoot = null)

        assertThat(points.map { it.wallMs to it.startsSegment })
            .containsExactly(0L to false, 5L to false, 10L to true, 15L to false)
            .inOrder()
        assertThat(boot).isEqualTo(2)
    }

    /** Elapsed milliseconds as the cache asks for them, in nanoseconds. */
    private fun ns(ms: Long) = ms * 1_000_000

    @Test
    fun theCacheReadsOnlyWhatIsNewAndStartsOverAtTheNextUnplug() =
        runTest {
            val stored = listOf(sample(100, 80), sample(105, 79), sample(110, 79), sample(115, 79), sample(300, 90))
            val asked = mutableListOf<Pair<Long, Long>>()
            val cache = LevelTrailCache()
            val fetch: suspend (Int, Long, Long) -> List<BatterySampleEntity> = { _, from, to ->
                asked += from to to
                stored.filter { it.elapsedNs in from until to }
            }

            cache.read(1, ns(100), ns(105), offsetMs = 0, fetch)
            val later = cache.read(1, ns(100), ns(115), offsetMs = 0, fetch)
            val again = cache.read(1, ns(100), ns(115), offsetMs = 0, fetch)
            val next = cache.read(1, ns(300), ns(300), offsetMs = 0, fetch)

            assertThat(asked).containsExactly(ns(100) to ns(105) + 1, ns(105) + 1 to ns(115) + 1, ns(300) to ns(300) + 1).inOrder()
            assertThat(later.map { it.wallMs }).containsExactly(100L, 105L, 115L).inOrder()
            assertThat(again).isSameInstanceAs(later)
            assertThat(next.map { it.wallMs }).containsExactly(300L)
        }

    /** Audit BUG-003: a clock set back moves the line with it; the next sample still arrives. */
    @Test
    fun aClockCorrectionMovesTheLineAndKeepsItGoing() =
        runTest {
            val stored = listOf(sample(100, 80), sample(105, 79), sample(120, 78))
            val cache = LevelTrailCache()
            val fetch: suspend (Int, Long, Long) -> List<BatterySampleEntity> = { _, from, to ->
                stored.filter { it.elapsedNs in from until to }
            }

            cache.read(1, ns(100), ns(105), offsetMs = 1_000, fetch)
            val back = cache.read(1, ns(100), ns(120), offsetMs = -50, fetch)

            assertThat(back.map { it.wallMs to it.levelPct }).containsExactly(50L to 80, 55L to 79, 70L to 78).inOrder()
        }
}

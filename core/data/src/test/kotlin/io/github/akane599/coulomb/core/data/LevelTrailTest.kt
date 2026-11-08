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

    @Test
    fun theCacheReadsOnlyWhatIsNewAndStartsOverAtTheNextUnplug() =
        runTest {
            val stored = listOf(sample(100, 80), sample(105, 79), sample(110, 79), sample(115, 79), sample(300, 90))
            val asked = mutableListOf<Pair<Long, Long>>()
            val cache = LevelTrailCache()
            val fetch: suspend (Long, Long) -> List<BatterySampleEntity> = { from, to ->
                asked += from to to
                stored.filter { it.wallMs in from until to }
            }

            cache.read(startWallMs = 100, nowWallMs = 105, fetch)
            val later = cache.read(startWallMs = 100, nowWallMs = 115, fetch)
            val again = cache.read(startWallMs = 100, nowWallMs = 115, fetch)
            val next = cache.read(startWallMs = 300, nowWallMs = 300, fetch)

            assertThat(asked).containsExactly(100L to 106L, 106L to 116L, 300L to 301L).inOrder()
            assertThat(later.map { it.wallMs }).containsExactly(100L, 105L, 115L).inOrder()
            assertThat(again).isSameInstanceAs(later)
            assertThat(next.map { it.wallMs }).containsExactly(300L)
        }
}

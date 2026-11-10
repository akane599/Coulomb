// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.analytics.LevelDrops
import io.github.akane599.coulomb.core.database.dao.StoredLevelChange
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LevelDropsCacheTest {
    private val wall = 1_790_000_000_000L
    private val hourMs = 3_600_000L

    private fun session(
        id: Long,
        kind: SessionKind = SessionKind.SCREEN_OFF,
        closed: Boolean = true,
    ) = SessionEntity(
        id = id,
        kind = kind,
        bootSeq = 4,
        // Elapsed 5 s into the boot at the wall clock's [wall].
        startElapsedNs = 5_000_000_000,
        endElapsedNs = if (closed) 5_000_000_000 + 4 * hourMs * 1_000_000 else null,
        startWall = wall,
        endWall = if (closed) wall + 4 * hourMs else null,
        startSnapshotId = null,
        endSnapshotId = null,
        startLevel = 80,
        endLevel = if (closed) 74 else null,
        startChargeUah = null,
        endChargeUah = null,
        chargeSource = null,
        avgCurrentMa = null,
        peakCurrentMa = null,
        minTempDeciC = null,
        meanTempDeciC = null,
        maxTempDeciC = 300,
        awakeRatio = null,
        deepIdleReached = null,
        deepIdleReachedMs = null,
        minutesAtOrAbove80 = null,
        minutesAt100 = null,
        stressIndex = null,
        cycleEquivalents = null,
        closeReason = if (closed) SessionCloseReason.STATE_CHANGED else null,
        quality = 1.0,
    )

    private fun change(
        afterStartMs: Long,
        level: Long,
        boot: Int = 4,
    ) = StoredLevelChange(boot, 5_000 + afterStartMs, level)

    @Test
    fun historyTimesLandOnTheWallClockThroughTheSessionsStart() {
        val drops =
            LevelDropsCache.of(
                session(1),
                listOf(change(hourMs / 2, 79), change(hourMs, 78), change(3 * hourMs, 75), change(2 * hourMs, 77, boot = 3)),
            )

        // The change logged in boot 3 is not this session's.
        assertThat(drops).isEqualTo(LevelDrops(wall + hourMs / 2, 79, wall + 3 * hourMs, 75))
    }

    @Test
    fun eachClosedScreenOffSessionIsReadOnceWhileItStaysInTheLookback() =
        runTest {
            val fetched = mutableListOf<Long>()
            val cache = LevelDropsCache()
            val fetch: suspend (SessionEntity) -> List<StoredLevelChange>? = {
                fetched += it.id
                listOf(change(hourMs, 79), change(2 * hourMs, 77))
            }
            val sessions = listOf(session(1), session(2, closed = false), session(3, kind = SessionKind.SCREEN_ON), session(4))

            val first = cache.read(sessions, fetch)
            cache.read(sessions, fetch)
            // Session 1 leaves the lookback and comes back: it is read again.
            cache.read(sessions.drop(1), fetch)
            cache.read(sessions, fetch)

            assertThat(first.keys).containsExactly(1L, 4L)
            assertThat(fetched).containsExactly(1L, 4L, 1L).inOrder()
        }

    @Test
    fun aSessionWhoseHistoryIsNotReadYetIsAskedForAgain() =
        runTest {
            var read = false
            var asked = 0
            val cache = LevelDropsCache()
            val fetch: suspend (SessionEntity) -> List<StoredLevelChange>? = {
                asked++
                if (read) listOf(change(hourMs, 79), change(2 * hourMs, 77)) else null
            }

            // No read past the session's end yet: no drops, nothing kept.
            assertThat(cache.read(listOf(session(1)), fetch)).isEmpty()
            read = true
            assertThat(cache.read(listOf(session(1)), fetch).keys).containsExactly(1L)
            cache.read(listOf(session(1)), fetch)

            assertThat(asked).isEqualTo(2)
        }
}

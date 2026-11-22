// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.database.dao.NewHistoryEvent
import io.github.akane599.coulomb.core.database.entity.HistoryGapEntity
import io.github.akane599.coulomb.core.model.HistoryGapReason
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** Audit DATA-010: whether a night reached deep Doze is said only as far as the history covers it. */
@RunWith(AndroidJUnit4::class)
internal class NightRepositoryTest : DrainDbTest() {
    private val repository by lazy {
        NightRepository(sessions, snapshots, db.historyDao(), AppDispatchers(Dispatchers.IO, Dispatchers.Default))
    }

    private fun idle(
        atS: Long,
        value: Long,
    ) = NewHistoryEvent(1, atS * 1_000, HistoryKind.DEVICE_IDLE, HistoryChange.SET, false, null, null, value)

    private fun cpu(atS: Long) = NewHistoryEvent(1, atS * 1_000, HistoryKind.CPU_RUNNING, HistoryChange.START, false, null, null, null)

    private fun gap(
        fromS: Long,
        toS: Long,
    ) = HistoryGapEntity(
        startMonotonicMs = fromS * 1_000,
        endMonotonicMs = toS * 1_000,
        startBootSeq = 1,
        startElapsedNs = fromS * NS_PER_S,
        endBootSeq = 1,
        endElapsedNs = toS * NS_PER_S,
        reason = HistoryGapReason.READ_DESYNC,
    )

    /** A four-hour night from 0 to 4 h, and a read after it that stored [events] and [gaps]. */
    private suspend fun night(
        events: List<NewHistoryEvent>,
        gaps: List<HistoryGapEntity> = emptyList(),
    ): NightSummary {
        val start = heavy(0)
        val end = heavy(4 * HOUR_S)
        val id = sessions.insert(screenOff(start, end).copy(endLevel = 85))
        val read = heavy(5 * HOUR_S)
        val mark = db.historyDao().insertRead(events, gaps)
        db.openHelper.writableDatabase.execSQL("UPDATE snapshot SET historyEventId = $mark WHERE id = $read")
        return checkNotNull(repository.night(id).first())
    }

    @Test
    fun aWholeHistoryWithoutDeepDozeSaysItWasNotReached() =
        runTest {
            val night = night(listOf(cpu(HOUR_S), idle(HOUR_S, LIGHT)))
            assertThat(night.deepDozeReached).isFalse()
        }

    @Test
    fun aGapWithoutDeepDozeLeavesItUnknown() =
        runTest {
            val night = night(listOf(cpu(HOUR_S), idle(HOUR_S, LIGHT)), listOf(gap(2 * HOUR_S, 3 * HOUR_S)))
            assertThat(night.deepDozeReached).isNull()
        }

    @Test
    fun deepDozeSeenAfterAGapWasReachedAtATimeNotKnown() =
        runTest {
            val night = night(listOf(cpu(HOUR_S), idle(3 * HOUR_S + 600, DEEP)), listOf(gap(2 * HOUR_S, 3 * HOUR_S)))
            assertThat(night.deepDozeReached).isTrue()
            assertThat(night.deepDozeAfterMs).isNull()
        }

    @Test
    fun deepDozeBeforeAGapKeepsItsTime() =
        runTest {
            val night = night(listOf(idle(HOUR_S, DEEP)), listOf(gap(2 * HOUR_S, 3 * HOUR_S)))
            assertThat(night.deepDozeReached).isTrue()
            assertThat(night.deepDozeAfterMs).isEqualTo(HOUR_S * 1_000)
        }

    /** Audit BUG-034: the latest night is the newest by boot, even when a wrong clock dated an older one later. */
    @Test
    fun theLatestNightIsTheNewestBootsNotTheLatestDated() =
        runTest {
            val start = heavy(0)
            val end = heavy(4 * HOUR_S)
            // Boot 1 ran with a clock a week ahead; boot 2's is right, and its night is the latest.
            val ahead = 7 * 24 * HOUR_S * 1_000
            val older =
                screenOff(start, end).copy(
                    endLevel = 85,
                    startWall = BASE_WALL + ahead,
                    endWall =
                        BASE_WALL + ahead + 4 * HOUR_S * 1_000,
                )
            sessions.insert(older)
            val newer =
                sessions.insert(
                    older.copy(bootSeq = 2, startWall = BASE_WALL + 10 * HOUR_S * 1_000, endWall = BASE_WALL + 14 * HOUR_S * 1_000),
                )
            assertThat(
                repository.nights
                    .first()
                    .first()
                    .id,
            ).isEqualTo(newer)
        }

    private companion object {
        const val LIGHT = 1L
        const val DEEP = 2L
    }
}

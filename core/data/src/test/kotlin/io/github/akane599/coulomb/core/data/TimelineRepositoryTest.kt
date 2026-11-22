// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.database.dao.NewHistoryEvent
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.model.history.HistoryChange
import io.github.akane599.coulomb.core.model.history.HistoryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** SPEC 10.6: a wall-clock span of the stored history, read from the read before it. */
@RunWith(AndroidJUnit4::class)
internal class TimelineRepositoryTest : DrainDbTest() {
    private val repository by lazy {
        TimelineRepository(
            db.historyDao(),
            snapshots,
            db.sampleDao(),
            sessions,
            db.deviceDao(),
            AppDispatchers(Dispatchers.IO, Dispatchers.Default),
        )
    }

    private fun event(
        elapsedS: Long,
        kind: HistoryKind,
        change: HistoryChange,
        value: Long? = null,
        baseline: Boolean = false,
    ) = NewHistoryEvent(1, elapsedS * 1_000, kind, change, baseline, null, null, value)

    /** A heavy snapshot at [elapsedS] whose history read stored [events]. */
    private suspend fun read(
        elapsedS: Long,
        events: List<NewHistoryEvent>,
        wallMs: Long = at(elapsedS),
    ) {
        val id = heavy(elapsedS, wallMs = wallMs)
        val mark = db.historyDao().insertRead(events, emptyList())
        db.openHelper.writableDatabase.execSQL("UPDATE snapshot SET historyEventId = $mark WHERE id = $id")
    }

    private suspend fun stored() {
        read(100, listOf(event(90, HistoryKind.BATTERY_LEVEL, HistoryChange.SET, 80, baseline = true)))
        read(
            1_000,
            listOf(
                event(101, HistoryKind.BATTERY_LEVEL, HistoryChange.SET, 80, baseline = true),
                event(101, HistoryKind.SCREEN, HistoryChange.START, baseline = true),
                // A kind the Timeline does not draw is not read.
                event(200, HistoryKind.JOB, HistoryChange.START),
                event(400, HistoryKind.SCREEN, HistoryChange.STOP),
                event(500, HistoryKind.BATTERY_LEVEL, HistoryChange.SET, 79),
            ),
        )
        db.sampleDao().insert(sample(450, -250_000))
        db.sampleDao().insert(sample(460, null))
    }

    private fun sample(
        elapsedS: Long,
        currentUa: Long?,
    ) = BatterySampleEntity(
        bootSeq = 1,
        elapsedNs = elapsedS * NS_PER_S,
        wallMs = at(elapsedS),
        levelPct = 80,
        chargeUah = null,
        currentUa = currentUa,
        currentRaw = null,
        voltageMv = 4_100,
        tempDeciC = 310,
        plugType = 0,
        status = 3,
        screenOn = true,
        dozeState = 0,
        thermalStatus = 0,
    )

    private fun at(elapsedS: Long) = BASE_WALL + elapsedS * 1_000

    @Test
    fun aSpanStartsInTheStateTheReadBeforeItRestated() =
        runTest {
            stored()

            val timeline = repository.read(at(300), at(900))

            // The screen was on since the read that began at 101 s; the span sees it from its own start.
            assertThat(timeline.tracks.getValue(TimelineTrack.SCREEN)).containsExactly(TrackSpan(at(300), at(400)))
            assertThat(timeline.level).containsExactly(
                LevelPoint(at(300), 80, false),
                LevelPoint(at(500), 79, false),
                LevelPoint(at(900), 79, false),
            )
            assertThat(timeline.current).containsExactly(CurrentPoint(at(450), -250.0))
            assertThat(timeline.unknown).isEmpty()
        }

    /**
     * Audit BUG-004: the clock ran two days ahead until it was corrected before the read at 1,000 s.
     * The span is chosen on the clock that places it, so the screen and the current stay in it.
     */
    @Test
    fun aClockCorrectedDuringTheBootKeepsTheSpansEvents() =
        runTest {
            val ahead = 2 * 86_400_000L
            read(100, listOf(event(90, HistoryKind.BATTERY_LEVEL, HistoryChange.SET, 80, baseline = true)), wallMs = at(100) + ahead)
            read(
                1_000,
                listOf(
                    event(101, HistoryKind.BATTERY_LEVEL, HistoryChange.SET, 80, baseline = true),
                    event(101, HistoryKind.SCREEN, HistoryChange.START, baseline = true),
                    event(400, HistoryKind.SCREEN, HistoryChange.STOP),
                ),
            )
            db.sampleDao().insert(sample(450, -250_000).copy(wallMs = at(450) + ahead))

            val timeline = repository.read(at(300), at(900))

            assertThat(timeline.tracks.getValue(TimelineTrack.SCREEN)).containsExactly(TrackSpan(at(300), at(400)))
            assertThat(timeline.current).containsExactly(CurrentPoint(at(450), -250.0))
        }

    @Test
    fun theTimeAfterTheLastReadIsNotKnownYet() =
        runTest {
            stored()

            val timeline = repository.read(at(300), at(2_000))

            assertThat(timeline.knownUntilWallMs).isEqualTo(at(1_000))
            assertThat(timeline.unknown).containsExactly(TrackSpan(at(1_000), at(2_000)))
            assertThat(timeline.level.last()).isEqualTo(LevelPoint(at(1_000), 79, false))
        }

    @Test
    fun aDatabaseWithoutSnapshotsGivesAnEmptySpan() =
        runTest {
            val timeline = repository.read(at(0), at(100))

            assertThat(timeline.empty).isTrue()
            assertThat(timeline.unknown).containsExactly(TrackSpan(at(0), at(100)))
        }
}

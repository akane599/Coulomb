// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import org.junit.Test

/** SPEC 10.7: what a stored screen-off session says as a night. */
class NightsTest {
    private val hourMs = 3_600_000L
    private val hourNs = hourMs * 1_000_000
    private val start = 1_800_000_000_000L

    private fun session(
        hours: Double?,
        levels: Pair<Int, Int?>,
        kind: SessionKind = SessionKind.SCREEN_OFF,
        awake: Double? = 0.09,
        deep: Boolean? = true,
        startWall: Long = start,
    ) = SessionEntity(
        id = 7,
        kind = kind,
        bootSeq = 1,
        startElapsedNs = hourNs,
        endElapsedNs = hours?.let { hourNs + (it * hourNs).toLong() },
        startWall = startWall,
        endWall = hours?.let { startWall + (it * hourMs).toLong() },
        startSnapshotId = null,
        endSnapshotId = null,
        startLevel = levels.first,
        endLevel = levels.second,
        startChargeUah = null,
        endChargeUah = null,
        chargeSource = null,
        avgCurrentMa = null,
        peakCurrentMa = null,
        minTempDeciC = null,
        meanTempDeciC = null,
        maxTempDeciC = 300,
        awakeRatio = awake,
        deepIdleReached = deep,
        deepIdleReachedMs = 42 * 60_000L,
        minutesAtOrAbove80 = null,
        minutesAt100 = null,
        stressIndex = null,
        cycleEquivalents = null,
        closeReason = hours?.let { SessionCloseReason.STATE_CHANGED },
        quality = 1.0,
    )

    @Test
    fun aNightGivesItsRateItsSleepAndWhenDeepDozeBegan() {
        val night = checkNotNull(Nights.summary(session(7.5, 80 to 74), emptyList()))
        assertThat(night.durationMs).isEqualTo(7 * hourMs + hourMs / 2)
        assertThat(night.ratePctPerHour).isWithin(1e-9).of(0.8)
        assertThat(night.deepSleepShare).isWithin(1e-9).of(0.91)
        assertThat(night.deepDozeReached).isTrue()
        assertThat(night.deepDozeAfterMs).isEqualTo(42 * 60_000L)
        assertThat(night.charged).isFalse()
    }

    @Test
    fun underAnHourOrStillOpenIsNoNight() {
        assertThat(Nights.summary(session(0.99, 80 to 79), emptyList())).isNull()
        assertThat(Nights.summary(session(null, 80 to null), emptyList())).isNull()
        assertThat(Nights.summary(session(1.0, 80 to 79), emptyList())).isNotNull()
    }

    @Test
    fun aRateNeedsThreeLevelPointsAndNoCharging() {
        // Two points over eight hours could be anything from one to three.
        assertThat(checkNotNull(Nights.summary(session(8.0, 80 to 78), emptyList())).ratePctPerHour).isNull()
        assertThat(checkNotNull(Nights.summary(session(8.0, 80 to 77), emptyList())).ratePctPerHour).isNotNull()
        // A charge that overlaps the night, even with the level lower at its end.
        val charge = session(1.0, 60 to 70, SessionKind.CHARGE, startWall = start + 2 * hourMs)
        val charged = checkNotNull(Nights.summary(session(8.0, 80 to 70), listOf(charge)))
        assertThat(charged.charged).isTrue()
        assertThat(charged.ratePctPerHour).isNull()
        // One that ended before it began does not count; a level that rose does.
        val earlier = session(1.0, 60 to 80, SessionKind.CHARGE, startWall = start - 2 * hourMs)
        assertThat(checkNotNull(Nights.summary(session(8.0, 80 to 70), listOf(earlier))).charged).isFalse()
        assertThat(checkNotNull(Nights.summary(session(8.0, 80 to 85), emptyList())).charged).isTrue()
    }

    @Test
    fun unknownSleepStaysUnknown() {
        val night = checkNotNull(Nights.summary(session(8.0, 80 to 70, awake = null, deep = null), emptyList()))
        assertThat(night.deepSleepShare).isNull()
        assertThat(night.deepDozeReached).isNull()
        assertThat(night.deepDozeAfterMs).isNull()
        // Not reached: the stored time, if any, is not shown.
        assertThat(checkNotNull(Nights.summary(session(8.0, 80 to 70, deep = false), emptyList())).deepDozeAfterMs).isNull()
    }
}

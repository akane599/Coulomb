// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.dao.BootClock
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
        /** Hours into the boot. */
        at: Double = 1.0,
        boot: Int = 1,
    ) = SessionEntity(
        id = 7,
        kind = kind,
        bootSeq = boot,
        startElapsedNs = (at * hourNs).toLong(),
        endElapsedNs = hours?.let { ((at + it) * hourNs).toLong() },
        startWall = start + (at * hourMs).toLong(),
        endWall = hours?.let { start + ((at + it) * hourMs).toLong() },
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
        deepIdleReachedMs = (42 * 60_000L).takeIf { deep != null },
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
        val charge = session(1.0, 60 to 70, SessionKind.CHARGE, at = 3.0)
        val charged = checkNotNull(Nights.summary(session(8.0, 80 to 70), listOf(charge)))
        assertThat(charged.charged).isTrue()
        assertThat(charged.ratePctPerHour).isNull()
        // So does one still open; one that ended as the night began does not, and a level that rose does.
        assertThat(
            checkNotNull(Nights.summary(session(8.0, 80 to 70), listOf(session(null, 60 to null, SessionKind.CHARGE, at = 0.5)))).charged,
        ).isTrue()
        val earlier = session(0.5, 60 to 80, SessionKind.CHARGE, at = 0.5)
        assertThat(checkNotNull(Nights.summary(session(8.0, 80 to 70), listOf(earlier))).charged).isFalse()
        assertThat(checkNotNull(Nights.summary(session(8.0, 80 to 85), emptyList())).charged).isTrue()
    }

    @Test
    fun aChargeInAnotherBootDoesNotOverlapWhateverItsWallTimesSay() {
        // The wall clock was wrong for a while in boot 2: its charge seems to span the night of boot 1.
        val charge = session(1_000.0, 60 to 70, SessionKind.CHARGE, at = 0.0, boot = 2)

        assertThat(checkNotNull(Nights.summary(session(8.0, 80 to 70), listOf(charge))).charged).isFalse()
    }

    @Test
    fun aNightIsDatedByItsBootsClockAsLastSeen() {
        // The clock was 40 days behind when the session was stored and was set right later in the boot.
        val right = start + 40 * 24 * hourMs
        val clock = BootClock(bootSeq = 1, elapsedNs = 20 * hourNs, wallMs = right + 20 * hourMs)

        val night = checkNotNull(Nights.summary(session(8.0, 80 to 70), emptyList(), clock))

        assertThat(night.startWallMs).isEqualTo(right + hourMs)
        assertThat(night.endWallMs).isEqualTo(right + 9 * hourMs)
        assertThat(night.durationMs).isEqualTo(8 * hourMs)
    }

    @Test
    fun dozeComesFromTheHistoryWhereTheSessionStoredNone() {
        val stored = session(8.0, 80 to 70, deep = null)

        val night = checkNotNull(Nights.summary(stored, emptyList(), doze = DozeReached(true, 25 * 60_000L)))
        assertThat(night.deepDozeReached).isTrue()
        assertThat(night.deepDozeAfterMs).isEqualTo(25 * 60_000L)
        // What the session stored stands.
        val own = checkNotNull(Nights.summary(session(8.0, 80 to 70), emptyList(), doze = DozeReached(false, null)))
        assertThat(own.deepDozeReached).isTrue()
        assertThat(own.deepDozeAfterMs).isEqualTo(42 * 60_000L)
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

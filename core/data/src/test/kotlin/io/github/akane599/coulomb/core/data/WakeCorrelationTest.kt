// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WakeCorrelationTest {
    private fun timeline(
        awake: List<TrackSpan>,
        locks: List<TrackSpan>,
        unknown: List<TrackSpan> = emptyList(),
    ) = Timeline(
        fromWallMs = 0,
        toWallMs = 100_000,
        level = emptyList(),
        current = emptyList(),
        temperature = emptyList(),
        tracks =
            TimelineTrack.entries.associateWith { emptyList<TrackSpan>() } +
                mapOf(TimelineTrack.AWAKE to awake, TimelineTrack.WAKELOCKS to locks),
        unknown = unknown,
        knownUntilWallMs = 100_000,
    )

    private fun awake(
        from: Long,
        to: Long,
        reason: String?,
    ) = TrackSpan(from, to, tag = reason)

    private fun lock(
        from: Long,
        to: Long,
        uid: Int?,
    ) = TrackSpan(from, to, uid = uid)

    @Test
    fun theFirstWakelockTakenInAStretchNamesItsApp() {
        val timeline =
            timeline(
                awake = listOf(awake(1_000, 3_000, "modem"), awake(10_000, 12_000, "modem"), awake(20_000, 21_000, "modem")),
                locks =
                    listOf(
                        lock(1_200, 1_500, 10_001),
                        // A second one in the same stretch is not the first.
                        lock(1_600, 2_000, 10_002),
                        lock(10_100, 11_000, 10_001),
                        lock(20_500, 20_900, 10_002),
                    ),
            )
        val apps = WakeCorrelation.apps(timeline, "modem", mapOf(10_001 to "Mail"))
        assertThat(apps.wakes).isEqualTo(3)
        assertThat(apps.unclaimed).isEqualTo(0)
        assertThat(apps.apps).containsExactly(WakeApp(10_001, "Mail", 2), WakeApp(10_002, null, 1)).inOrder()
    }

    @Test
    fun onlyStretchesOfThisReasonCount() {
        val timeline =
            timeline(
                awake = listOf(awake(1_000, 3_000, "modem"), awake(5_000, 6_000, "wifi"), awake(8_000, 9_000, null)),
                locks = listOf(lock(1_200, 1_500, 10_001), lock(5_100, 5_500, 10_002), lock(8_100, 8_500, 10_003)),
            )
        val apps = WakeCorrelation.apps(timeline, "modem", emptyMap())
        assertThat(apps.wakes).isEqualTo(1)
        assertThat(apps.apps.map { it.uid }).containsExactly(10_001)
    }

    @Test
    fun aStretchWithNoWakelockOfItsOwnIsUnclaimed() {
        val timeline =
            timeline(
                awake = listOf(awake(1_000, 3_000, "modem"), awake(10_000, 12_000, "modem"), awake(20_000, 22_000, "modem")),
                locks =
                    listOf(
                        // Began after the first stretch ended: nobody's.
                        lock(4_000, 4_500, 10_001),
                        // Began before the second stretch: not that wake's.
                        lock(9_000, 11_000, 10_001),
                        // Began the millisecond the third stretch ended: after it.
                        lock(22_000, 22_500, 10_001),
                    ),
            )
        val apps = WakeCorrelation.apps(timeline, "modem", emptyMap())
        assertThat(apps.wakes).isEqualTo(3)
        assertThat(apps.unclaimed).isEqualTo(3)
        assertThat(apps.unnamed).isEqualTo(0)
        assertThat(apps.apps).isEmpty()
    }

    @Test
    fun aFirstWakelockWithoutAHolderIsNotGivenToTheNextOne() {
        val timeline =
            timeline(
                awake = listOf(awake(1_000, 5_000, "modem")),
                locks = listOf(lock(1_100, 1_200, null), lock(1_300, 1_400, 10_001)),
            )
        val apps = WakeCorrelation.apps(timeline, "modem", emptyMap())
        assertThat(apps.wakes).isEqualTo(1)
        assertThat(apps.unnamed).isEqualTo(1)
        assertThat(apps.unclaimed).isEqualTo(0)
        assertThat(apps.apps).isEmpty()
    }

    @Test
    fun aWakelockTakenLongAfterTheWakeDidNotFollowIt() {
        val timeline =
            timeline(
                awake = listOf(awake(1_000, 90_000, "modem"), awake(91_000, 99_000, "modem")),
                locks = listOf(lock(11_001, 12_000, 10_001), lock(96_000, 97_000, 10_002)),
            )
        val apps = WakeCorrelation.apps(timeline, "modem", emptyMap(), followMs = 5_000)
        assertThat(apps.unclaimed).isEqualTo(1)
        assertThat(apps.apps).containsExactly(WakeApp(10_002, null, 1))
    }

    @Test
    fun aStretchWhoseBeginningTheHistoryDoesNotShowIsNotCounted() {
        val timeline =
            timeline(
                awake =
                    listOf(
                        // Cut by the start of the read.
                        awake(0, 2_000, "modem"),
                        // Begins where a gap in the history ends.
                        awake(40_000, 42_000, "modem"),
                        awake(50_000, 52_000, "modem"),
                    ),
                locks = listOf(lock(0, 500, 10_001), lock(40_000, 40_500, 10_001), lock(50_100, 50_500, 10_002)),
                unknown = listOf(TrackSpan(30_000, 40_000)),
            )
        val apps = WakeCorrelation.apps(timeline, "modem", emptyMap())
        assertThat(apps.wakes).isEqualTo(1)
        assertThat(apps.apps).containsExactly(WakeApp(10_002, null, 1))
        assertThat(apps.knownMs).isEqualTo(90_000)
    }

    @Test
    fun theOrderOfTheWakelocksAndOfEqualAppsIsFixed() {
        val timeline =
            timeline(
                awake = listOf(awake(1_000, 3_000, "modem"), awake(10_000, 12_000, "modem")),
                locks = listOf(lock(10_100, 10_200, 10_002), lock(1_100, 1_200, 10_009), lock(1_500, 1_600, 10_001)),
            )
        assertThat(WakeCorrelation.apps(timeline, "modem", emptyMap()).apps.map { it.uid }).containsExactly(10_002, 10_009).inOrder()
    }

    @Test
    fun aWakelockAtTheStartOfAStretchCounts() {
        val timeline = timeline(awake = listOf(awake(1_000, 3_000, "modem")), locks = listOf(lock(1_000, 1_500, 10_001)))
        assertThat(WakeCorrelation.apps(timeline, "modem", emptyMap()).apps.map { it.uid }).containsExactly(10_001)
    }

    @Test
    fun noHistoryIsNoKnownTime() {
        val none = timeline(emptyList(), emptyList(), unknown = listOf(TrackSpan(0, 100_000)))
        assertThat(WakeCorrelation.apps(none, "modem", emptyMap()))
            .isEqualTo(WakeApps(0, 100_000, knownMs = 0, wakes = 0, unclaimed = 0, unnamed = 0, apps = emptyList()))
        // History with no such wake is known time with none.
        assertThat(WakeCorrelation.apps(timeline(emptyList(), emptyList()), "modem", emptyMap()).knownMs).isEqualTo(100_000)
    }
}

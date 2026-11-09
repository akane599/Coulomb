// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import org.junit.Test

/** A session's awake share from its snapshots' clocks: uptime stops in suspend (ADR-0029 §3). */
class AwakeRatioTest {
    private fun at(
        minutes: Double,
        uptimeMinutes: Double?,
        bootSeq: Int = 1,
    ) = SnapshotEntity(
        bootSeq = bootSeq,
        elapsedNs = (minutes * 60_000_000_000L).toLong(),
        wallMs = 0,
        trigger = SnapshotTrigger.SCREEN_OFF,
        kind = SnapshotKind.LIGHT,
        levelPct = 80,
        chargeUah = null,
        currentRaw = null,
        voltageMv = null,
        tempDeciC = null,
        plugType = null,
        status = null,
        statsStartWall = null,
        statsRealtimeMs = null,
        historyCursorMs = null,
        historyEventId = null,
        sourceStatus = 0,
        blobPath = null,
        partial = false,
        countersPruned = false,
        durationMs = 0,
        uptimeMs = uptimeMinutes?.let { (it * 60_000).toLong() },
    )

    @Test
    fun uptimeOverElapsedIsTheAwakeShare() {
        // 60 min apart, 6 of them awake.
        assertThat(SessionSummary.awakeRatio(at(10.0, 10.0), at(70.0, 16.0))).isWithin(1e-9).of(0.1)
    }

    @Test
    fun noShareAcrossABootWithoutUptimeOrUnderAMinute() {
        assertThat(SessionSummary.awakeRatio(at(10.0, 10.0), at(70.0, 16.0, bootSeq = 2))).isNull()
        assertThat(SessionSummary.awakeRatio(at(10.0, null), at(70.0, 16.0))).isNull()
        assertThat(SessionSummary.awakeRatio(at(10.0, 10.0), at(10.9, 10.9))).isNull()
        assertThat(SessionSummary.awakeRatio(null, at(70.0, 16.0))).isNull()
        assertThat(SessionSummary.awakeRatio(at(10.0, 10.0), at(11.0, 11.0))).isWithin(1e-9).of(1.0)
    }

    @Test
    fun clockReadsAFewMillisecondsApartNeverLeaveZeroToOne() {
        // Uptime read a little after elapsed: a hair above 1 is clamped.
        assertThat(SessionSummary.awakeRatio(at(10.0, 10.0), at(20.0, 20.001))).isEqualTo(1.0)
    }
}

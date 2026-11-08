// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.analytics.Discharge
import io.github.akane599.coulomb.core.analytics.LevelSpan
import io.github.akane599.coulomb.core.analytics.ScreenRates
import io.github.akane599.coulomb.core.analytics.TimeRemaining
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.SessionKind
import java.time.ZoneId

/**
 * Builds [DischargeSummary] from the stored sessions (SPEC 7.2) and the latest sample. Pure.
 * An open session ends at the latest sample. Screen sessions count only where they overlap a
 * discharge session ([ScreenRates.discharging]), so time on the charger never enters a rate.
 */
internal object DischargeSummaries {
    /** Sessions the summary reads: since this long before the latest sample, for the history. */
    const val LOOKBACK_MS: Long = (ScreenRates.HISTORY_DAYS + 1) * 86_400_000L

    val KINDS: List<SessionKind> = listOf(SessionKind.DISCHARGE, SessionKind.SCREEN_ON, SessionKind.SCREEN_OFF)

    fun build(
        latest: BatterySampleEntity,
        sessions: List<SessionEntity>,
        zone: ZoneId,
    ): DischargeSummary? {
        if (latest.plugType != 0) return null
        val open = sessions.lastOrNull { it.kind == SessionKind.DISCHARGE && it.closeReason == null } ?: return null
        val now = latest.wallMs
        val discharges = sessions.filter { it.kind == SessionKind.DISCHARGE }.map { it.toDischarge(latest) }
        val current = open.toDischarge(latest)
        val screens = sessions.filter { it.kind != SessionKind.DISCHARGE }
        val spans = screens.flatMap { ScreenRates.discharging(it.toSpan(latest), discharges) }
        val inSession = spans.filter { it.startWallMs >= current.startWallMs }
        val on = ScreenRates.combine(inSession.filter { it.screenOn })
        val off = ScreenRates.combine(inSession.filter { !it.screenOn })
        val history = ScreenRates.history(spans.filter { it.endWallMs <= current.startWallMs }, latest.screenOn, now, zone)
        return DischargeSummary(
            startWallMs = open.startWall,
            startLevel = open.startLevel,
            levelPct = latest.levelPct,
            screenOn = on?.let { StateRate(it.pctPerHour, it.hours) },
            screenOff = off?.let { StateRate(it.pctPerHour, it.hours) },
            deepSleepShare = deepSleep(screens.filter { it.kind == SessionKind.SCREEN_OFF }, current, latest),
            timeLeft = TimeRemaining.estimate(latest.levelPct, if (latest.screenOn) on else off, history),
        )
    }

    /** One minus the screen-off sessions' awake share, weighted by their time in [current]. */
    private fun deepSleep(
        screenOff: List<SessionEntity>,
        current: Discharge,
        latest: BatterySampleEntity,
    ): Double? {
        var hours = 0.0
        var awake = 0.0
        for (session in screenOff) {
            val ratio = session.awakeRatio ?: continue
            val time = ScreenRates.discharging(session.toSpan(latest), listOf(current)).sumOf { (it.endWallMs - it.startWallMs).toDouble() }
            hours += time
            awake += ratio.coerceIn(0.0, 1.0) * time
        }
        return if (hours > 0) 1 - awake / hours else null
    }

    private fun SessionEntity.toDischarge(latest: BatterySampleEntity) =
        Discharge(startWall, endWall ?: latest.wallMs, startLevel, endLevel ?: latest.levelPct, quality)

    private fun SessionEntity.toSpan(latest: BatterySampleEntity) =
        LevelSpan(kind == SessionKind.SCREEN_ON, startWall, endWall ?: latest.wallMs, startLevel, endLevel ?: latest.levelPct, quality)
}

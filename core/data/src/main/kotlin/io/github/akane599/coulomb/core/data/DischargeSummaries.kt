// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.analytics.Discharge
import io.github.akane599.coulomb.core.analytics.LevelDrops
import io.github.akane599.coulomb.core.analytics.LevelSpan
import io.github.akane599.coulomb.core.analytics.ScreenRates
import io.github.akane599.coulomb.core.analytics.TimeRemaining
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import java.time.ZoneId

/**
 * Builds [DischargeSummary] from the stored sessions (SPEC 7.2) and the latest sample. Pure.
 * An open session ends at the latest sample. Screen sessions count only where they overlap a
 * discharge session ([ScreenRates.discharging]), so time on the charger never enters a rate.
 *
 * The rates are level-based (SPEC 6.2 shows those until the capacity estimate exists), so a
 * session's quality here is how far its level ends can be trusted ([levelQuality]), not the stored
 * quality: an open session stores 0 until it closes, and a closed one's stored quality already
 * holds its own level resolution, which [ScreenRates.combine] applies once over the combined drop.
 * A closed screen-off session with the platform's level [drops] is timed by them (ADR-0049).
 *
 * Times are on each boot's elapsed clock, placed on the wall clock by one offset per boot
 * ([BootWalls]): a clock correction during a session changes neither its length nor its overlaps
 * (SPEC 7.8; audit BUG-003).
 */
internal object DischargeSummaries {
    /** Sessions the summary reads: since this long before the latest sample, for the history. */
    const val LOOKBACK_MS: Long = (ScreenRates.HISTORY_DAYS + 1) * 86_400_000L

    val KINDS: List<SessionKind> = listOf(SessionKind.DISCHARGE, SessionKind.SCREEN_ON, SessionKind.SCREEN_OFF)

    fun build(
        latest: BatterySampleEntity,
        sessions: List<SessionEntity>,
        zone: ZoneId,
        drops: Map<Long, LevelDrops> = emptyMap(),
    ): DischargeSummary? {
        if (latest.plugType != 0) return null
        val openIndex = sessions.indexOfLast { it.kind == SessionKind.DISCHARGE && it.closeReason == null }
        if (openIndex < 0) return null
        val stored = sessions[openIndex]
        // A sample from before the session opened (the first after a screen-off stretch is still
        // on its way), or from the boot after it before the reboot closed it, says nothing about it.
        if (stored.bootSeq != latest.bootSeq || latest.elapsedNs < stored.startElapsedNs) return null
        val walls = BootWalls.of(latest, sessions)
        val placed = sessions.map(walls::place)
        val open = placed[openIndex]
        val now = latest.wallMs
        // The level drops were placed through each session's stored start: move them with it.
        val moved = sessions.zip(placed).associate { (was, now) -> was.id to now.startWall - was.startWall }
        val shifted = drops.mapValues { (id, d) -> d.shiftedBy(moved[id] ?: 0) }
        // An open session of an earlier boot has no end on this boot's clock; the reboot closes it.
        val readable =
            placed.filter {
                if (it.closeReason == null) it.bootSeq == latest.bootSeq else it.endWall != null && it.endLevel != null
            }
        // A screen session overlaps only its own boot's discharges: each boot has its own clock, and
        // a clock set wrong can lay one boot over another.
        val discharges =
            readable.filter { it.kind == SessionKind.DISCHARGE }.groupBy({ it.bootSeq }, { it.toDischarge(latest) })
        val current = open.toDischarge(latest)
        val screens = readable.filter { it.kind != SessionKind.DISCHARGE }
        val spans =
            screens.flatMap { ScreenRates.discharging(it.toSpan(latest, shifted[it.id]), discharges[it.bootSeq].orEmpty()) }
        val inSession = spans.filter { it.startWallMs >= current.startWallMs }
        val on = ScreenRates.combine(inSession.filter { it.screenOn })
        val off = ScreenRates.combine(inSession.filter { !it.screenOn })
        val history = ScreenRates.history(spans.filter { it.endWallMs <= current.startWallMs }, latest.screenOn, now, zone)
        return DischargeSummary(
            startWallMs = open.startWall,
            startLevel = open.startLevel,
            levelPct = latest.levelPct,
            screenOn = on?.let { StateRate(it.pctPerHour, it.hours, it.quality) },
            screenOff = off?.let { StateRate(it.pctPerHour, it.hours, it.quality) },
            deepSleep = deepSleep(screens.filter { it.kind == SessionKind.SCREEN_OFF && it.bootSeq == open.bootSeq }, current, latest),
            timeLeft = TimeRemaining.estimate(latest.levelPct, if (latest.screenOn) on else off, history),
        )
    }

    /**
     * One minus the screen-off sessions' awake share, weighted by their time in [current]. The
     * quality (SPEC 6.7: sample count, window length) is their [levelQuality] weighted the same way,
     * as a session cut short by a reboot or a gap misses part of its awake time too, times the
     * window's length up to [FULL_SLEEP_HOURS]: a quarter hour earns a quarter.
     */
    private fun deepSleep(
        screenOff: List<SessionEntity>,
        current: Discharge,
        latest: BatterySampleEntity,
    ): DeepSleepShare? {
        var ms = 0.0
        var awake = 0.0
        var quality = 0.0
        for (session in screenOff) {
            val ratio = session.awakeRatio ?: continue
            val time = ScreenRates.discharging(session.toSpan(latest), listOf(current)).sumOf { (it.endWallMs - it.startWallMs).toDouble() }
            ms += time
            awake += ratio.coerceIn(0.0, 1.0) * time
            quality += session.levelQuality() * time
        }
        if (ms <= 0) return null
        val hours = ms / MS_PER_HOUR
        return DeepSleepShare(share = 1 - awake / ms, hours = hours, quality = quality / ms * minOf(1.0, hours / FULL_SLEEP_HOURS))
    }

    /**
     * 1 for an open session (it ends at the latest sample) or one closed by its own state change;
     * half for one cut short by a reboot, a history gap or monitoring stopping, as ADR-0017's gap
     * penalty in SessionSummary.
     */
    private fun SessionEntity.levelQuality(): Double =
        when (closeReason) {
            null, SessionCloseReason.STATE_CHANGED -> 1.0
            SessionCloseReason.REBOOT, SessionCloseReason.DATA_GAP, SessionCloseReason.MONITORING_STOPPED -> GAP_QUALITY
        }

    private const val GAP_QUALITY = 0.5
    private const val MS_PER_HOUR = 3_600_000.0

    /** Screen-off time after which the share's window no longer lowers its quality. */
    private const val FULL_SLEEP_HOURS = 1.0

    private fun LevelDrops.shiftedBy(ms: Long) = copy(firstWallMs = firstWallMs + ms, lastWallMs = lastWallMs + ms)

    private fun SessionEntity.toDischarge(latest: BatterySampleEntity) =
        Discharge(startWall, endWall ?: latest.wallMs, startLevel, endLevel ?: latest.levelPct, levelQuality())

    private fun SessionEntity.toSpan(
        latest: BatterySampleEntity,
        drops: LevelDrops? = null,
    ) = LevelSpan(
        kind == SessionKind.SCREEN_ON,
        startWall,
        endWall ?: latest.wallMs,
        startLevel,
        endLevel ?: latest.levelPct,
        levelQuality(),
        drops,
    )
}

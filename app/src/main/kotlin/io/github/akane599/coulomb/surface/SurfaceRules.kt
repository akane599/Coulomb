// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.surface

import io.github.akane599.coulomb.core.data.ChargingNow
import io.github.akane599.coulomb.core.model.PrivilegeIssue

// The decisions behind the notifications of SPEC 12, apart from the Android calls that post them.
// Each is fed from a stored snapshot (SPEC 12: no surface adds a wakeup), so each keeps only what
// it must remember between two snapshots.

/** What to do with the Charging Live Update after a snapshot (SPEC 12.2). */
sealed interface ChargingStep {
    data object None : ChargingStep

    /** Post or update the ongoing notification. */
    data class Show(
        val now: ChargingNow,
    ) : ChargingStep

    /** The charge ended: replace the notification with its summary, which leaves by itself. */
    data class End(
        val summary: ChargeSummary,
    ) : ChargingStep

    /** Unplugged with no charge followed: withdraw a notification an earlier process may have left. */
    data object Clear : ChargingStep
}

/** "+46% in 38 min, avg 21 W" (SPEC 12.2). */
data class ChargeSummary(
    val gainedPct: Int,
    val durationMs: Long,
    /** Mean power into the battery over the readings that have one; null before calibration. */
    val averageWatts: Double?,
)

/**
 * The Charging Live Update's pace (SPEC 12.2): shown at once when a charge begins, then updated on
 * a level change, but not within [MIN_INTERVAL_MS] of the last update ("on each 1% change or every
 * 30 s, whichever comes later"). When the charge ends, its summary.
 */
class ChargingUpdates {
    private var last: ChargingNow? = null
    private var shownLevel: Int? = null
    private var shownAtMs = 0L
    private var cleared = false

    /** [now] is the charge in progress, null while unplugged; [levelPct] is the level at [atMs]. */
    fun next(
        now: ChargingNow?,
        atMs: Long,
        levelPct: Int?,
    ): ChargingStep {
        val before = last
        last = now
        if (now == null) return unplugged(before, atMs, levelPct)
        cleared = true
        val first = shownLevel == null || before == null || before.startWallMs != now.startWallMs
        val due = now.levelPct != shownLevel && atMs - shownAtMs >= MIN_INTERVAL_MS
        if (!first && !due) return ChargingStep.None
        shownLevel = now.levelPct
        shownAtMs = atMs
        return ChargingStep.Show(now)
    }

    private fun unplugged(
        before: ChargingNow?,
        atMs: Long,
        levelPct: Int?,
    ): ChargingStep {
        shownLevel = null
        if (before != null) return ChargingStep.End(summary(before, levelPct ?: before.levelPct, atMs))
        return if (cleared) ChargingStep.None else ChargingStep.Clear.also { cleared = true }
    }

    /** [charge] is the last reading before the plug left; the level and the time are the unplugging's. */
    private fun summary(
        charge: ChargingNow,
        endLevelPct: Int,
        endMs: Long,
    ): ChargeSummary {
        val watts = charge.curve.mapNotNull { it.watts }.filter { it > 0 }
        return ChargeSummary(
            gainedPct = endLevelPct - charge.startLevelPct,
            durationMs = (endMs - charge.startWallMs).coerceAtLeast(0),
            averageWatts = watts.takeIf { it.isNotEmpty() }?.average(),
        )
    }

    companion object {
        const val MIN_INTERVAL_MS = 30_000L
    }
}

/** The bar of the Live Update: from the charge's start level to 100%, with the target marked (SPEC 12.2). */
data class ChargeBar(
    /** The bar's length: the points from the start level to full. */
    val max: Int,
    val progress: Int,
    /** The first segment, up to the target; the whole bar when the charge began at or above it. */
    val toTarget: Int,
    /** Where the target sits on the bar; null when the charge began at or above it. */
    val targetAt: Int?,
) {
    /** The quieter segment from the target to full. */
    val pastTarget: Int get() = max - toTarget

    companion object {
        private const val FULL = 100

        fun of(now: ChargingNow): ChargeBar {
            // A charge that began at 100% still needs a bar with a length.
            val start = now.startLevelPct.coerceIn(0, FULL - 1)
            val max = FULL - start
            val target = (now.targetPct - start).takeIf { it in 1 until max }
            return ChargeBar(max, (now.levelPct - start).coerceIn(0, max), target ?: max, target)
        }
    }
}

/** An alert to post now (SPEC 12.3). */
sealed interface Alert {
    data class TargetReached(
        val targetPct: Int,
    ) : Alert

    data class Hot(
        val tempDeciC: Int,
    ) : Alert
}

/**
 * SPEC 12.3. The charge target alarm fires when the level crosses the target while plugged, once
 * per charge: a dip and a second crossing on the same charger say nothing more (audit BUG-012),
 * and a level that was already there when Coulomb started says nothing. The temperature alarm
 * fires at or above its threshold, at most once per [TEMPERATURE_REST_MS].
 */
class AlertRules {
    private var lastLevel: Int? = null
    private var lastPlugged = false

    /** The target alarm rang since the phone was last seen unplugged. */
    private var rang = false
    private var hotAtMs: Long? = null

    @Suppress("LongParameterList") // One reading and the two alarms' settings.
    fun next(
        levelPct: Int,
        plugged: Boolean,
        tempDeciC: Int,
        atMs: Long,
        targetPct: Int?,
        hotFromDeciC: Int?,
    ): List<Alert> {
        val before = lastLevel?.takeIf { lastPlugged }
        lastLevel = levelPct
        lastPlugged = plugged
        if (!plugged) rang = false
        val reached = !rang && plugged && targetPct != null && before != null && before < targetPct && levelPct >= targetPct
        if (reached) rang = true
        val rested = hotAtMs?.let { atMs - it >= TEMPERATURE_REST_MS } ?: true
        val hot = hotFromDeciC != null && tempDeciC >= hotFromDeciC && rested
        if (hot) hotAtMs = atMs
        return listOfNotNull(
            targetPct?.takeIf { reached }?.let(Alert::TargetReached),
            Alert.Hot(tempDeciC).takeIf { hot },
        )
    }

    /** The heat was told another way at [atMs] (an insight): the alarm rests from then. */
    fun rest(atMs: Long) {
        hotAtMs = atMs
    }

    companion object {
        const val TEMPERATURE_REST_MS = 30 * 60_000L
    }
}

/**
 * The insights digest's day (SPEC 9.4, 12.1): at most one a day, from the set minute of the local
 * day on, and only when there is something new to say. It posts with the first snapshot after that
 * minute; no alarm is set for it.
 */
object DigestRules {
    /** Whether today's digest may still post; asked before the insights are read. */
    fun open(
        enabled: Boolean,
        minuteOfDay: Int,
        fromMinute: Int,
        epochDay: Long,
        lastPostedEpochDay: Long?,
    ): Boolean = enabled && minuteOfDay >= fromMinute && lastPostedEpochDay != epochDay

    /**
     * The insights a digest names: the new ones, when at least one was found since the last digest
     * ([lastId], ids grow). Without one, yesterday's digest already said it all.
     */
    fun <T> fresh(
        new: List<T>,
        lastId: Long?,
        id: (T) -> Long,
    ): List<T> = if (new.any { lastId == null || id(it) > lastId }) new else emptyList()
}

/** Which losses of access are worth a notification (SPEC 12.1), and each only once. */
object PrivilegeNotices {
    /** The other issues are told on screen, where the user is when they arise. */
    private val TOLD = setOf(PrivilegeIssue.MANAGER_STOPPED, PrivilegeIssue.GRANTS_REVOKED, PrivilegeIssue.PERMISSION_DENIED)

    /** [issue] when it should be posted now: [told] is the name of the one last posted and not yet resolved. */
    fun toPost(
        issue: PrivilegeIssue?,
        told: String?,
    ): PrivilegeIssue? = issue?.takeIf { it in TOLD && it.name != told }
}

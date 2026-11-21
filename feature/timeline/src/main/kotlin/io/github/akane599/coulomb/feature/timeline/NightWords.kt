// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.NightSummary
import io.github.akane599.coulomb.core.insights.Contributor
import io.github.akane599.coulomb.core.insights.DrainStory
import io.github.akane599.coulomb.core.insights.StoryInput
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.model.story.Story
import io.github.akane599.coulomb.core.model.story.StorySpan
import io.github.akane599.coulomb.core.model.story.StorySubject
import io.github.akane599.coulomb.core.ui.format.Figure
import io.github.akane599.coulomb.core.ui.format.Formats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val WEEK = 6L
private const val MS_PER_MINUTE = 60_000L
private const val HALF_MINUTE_MS = 30_000L

private fun dayOf(
    wallMs: Long,
    zone: ZoneId,
): LocalDate = Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate()

/** When a night began: "Today, 00:30", "Yesterday, 23:10", "Wednesday, 23:10", or "Oct 21, 23:10". */
@Composable
internal fun startedAt(
    wallMs: Long,
    nowMs: Long,
    zone: ZoneId,
    formats: Formats,
): String {
    val day = dayOf(wallMs, zone)
    val today = dayOf(nowMs, zone)
    val ago = ChronoUnit.DAYS.between(day, today)
    val clock = formats.clock(wallMs, zone)
    return when {
        // A start after now (a clock set back, a trip west) reads as today.
        ago <= 0L -> stringResource(R.string.night_today_at, clock)

        ago == 1L -> stringResource(R.string.night_yesterday_at, clock)

        ago in 2..WEEK -> stringResource(R.string.night_day_at, formats.weekday(day), clock)

        else -> stringResource(R.string.night_day_at, formats.day(day, today), clock)
    }
}

/** A length of time to the nearest minute. */
internal fun Formats.minutes(ms: Long): String = duration((ms + HALF_MINUTE_MS) / MS_PER_MINUTE)

/** The night's rate as a figure, "0.8 %/h"; null where it has none. */
internal fun NightSummary.rate(formats: Formats): Figure? = ratePctPerHour?.let { Figure(formats.rate(it), formats.rateUnit) }

/**
 * The line under a night's start in the list: how long, how much of it in deep sleep, and whether
 * and when deep Doze began; what is not known is left out.
 */
@Composable
internal fun nightLine(
    night: NightSummary,
    formats: Formats,
): String {
    val parts =
        if (night.charged) {
            // On the charger the phone does not sleep: its sleep and Doze say nothing about the night.
            listOf(formats.minutes(night.durationMs), stringResource(R.string.night_charged))
        } else {
            listOfNotNull(
                formats.minutes(night.durationMs),
                night.deepSleepShare?.let { stringResource(R.string.night_deep_sleep_share, formats.share(it)) },
                when (night.deepDozeReached) {
                    true -> night.deepDozeAfterMs?.let { stringResource(R.string.night_doze_after, formats.minutes(it)) }
                    false -> stringResource(R.string.night_doze_never)
                    null -> null
                },
            )
        }
    val separator = stringResource(R.string.night_separator)
    return parts.joinToString(separator)
}

/**
 * The night's Drain Story (SPEC 9.3): its causes from who used the battery, each app with the
 * wakeup alarms it set off and the time it held the phone awake, and how well the phone slept.
 */
internal fun nightStory(
    night: NightSummary,
    report: DrainReport?,
    open: List<Insight>,
): Story {
    val drains = report?.drains
    val alarms = report?.alarms.orEmpty().associate { it.uid to it.wakeups }
    val held =
        report
            ?.wakes
            .orEmpty()
            .filter { it.kind == WakeKind.PARTIAL && it.uid != null }
            .groupingBy { it.uid }
            .fold(0L) { sum, wake -> sum + wake.totalMs }
    return DrainStory.compose(
        StoryInput(
            subject = StorySubject.ScreenOff(overnight = false),
            span =
                StorySpan(
                    night.startWallMs,
                    night.endWallMs,
                    night.durationMs,
                    (night.startLevel - night.endLevel).takeIf { !night.charged },
                    night.ratePctPerHour,
                ),
            totalMah = drains?.total ?: 0.0,
            apps = drains?.apps.orEmpty().map { Contributor(it.uid, it.label, it.mah, alarms[it.uid] ?: 0, held[it.uid] ?: 0) },
            deviceMah = drains?.deviceMah ?: 0.0,
            unattributedMah = drains?.unattributedMah ?: 0.0,
            // On the charger the phone stays awake by design: its sleep is no finding.
            deepSleepShare = night.deepSleepShare.takeIf { !night.charged },
            open = open,
        ),
    )
}

// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.widget

import io.github.akane599.coulomb.core.data.NightSummary
import io.github.akane599.coulomb.core.insights.Contributor
import io.github.akane599.coulomb.core.insights.DrainStory
import io.github.akane599.coulomb.core.insights.StoryInput
import io.github.akane599.coulomb.core.model.story.StorySpan
import io.github.akane599.coulomb.core.model.story.StorySubject

/**
 * What the widget picker's previews show (SPEC 12.4): made-up figures in the real layouts, so a
 * preview never shows the owner's data and looks the same before anything is recorded.
 */
internal object Samples {
    private const val HOUR_MS = 3_600_000L
    private const val MINUTE_MS = 60_000L
    private const val UID_A = 10_001
    private const val UID_B = 10_002
    private const val UID_C = 10_003

    val level =
        LevelFacts(
            levelPct = 74,
            state = PowerState.ON_BATTERY,
            story =
                DrainStory.compose(
                    StoryInput(
                        subject = StorySubject.SinceUnplugged,
                        span =
                            StorySpan(
                                fromWallMs = 0,
                                toWallMs = 6 * HOUR_MS,
                                durationMs = 6 * HOUR_MS,
                                levelDropPct = 26,
                                ratePctPerHour = null,
                            ),
                        totalMah = 1_240.0,
                        apps = listOf(Contributor(UID_A, "Maps", 384.0)),
                    ),
                ),
        )

    val drains =
        DrainFacts(
            listOf(
                DrainRow(UID_A, "Maps", 0.31),
                DrainRow(UID_B, "Camera", 0.18),
                DrainRow(UID_C, "Music", 0.09),
            ),
        )

    fun night(nowMs: Long) =
        NightSummary(
            id = 0,
            startWallMs = nowMs - 9 * HOUR_MS,
            endWallMs = nowMs - 2 * HOUR_MS - 18 * MINUTE_MS,
            durationMs = 6 * HOUR_MS + 42 * MINUTE_MS,
            startLevel = 81,
            endLevel = 76,
            ratePctPerHour = 0.7,
            charged = false,
            deepSleepShare = 0.94,
            deepDozeReached = true,
            deepDozeAfterMs = 23 * MINUTE_MS,
        )

    val health =
        HealthFacts(capacityMah = 4_710.0, lowMah = 4_640.0, highMah = 4_780.0, healthPct = 94.2, cycles = 212.0, cyclesMeasured = true)
}

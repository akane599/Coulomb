// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.model.story.Story
import io.github.akane599.coulomb.core.model.story.StoryCause
import io.github.akane599.coulomb.core.model.story.StorySpan
import io.github.akane599.coulomb.core.model.story.StorySubject

/** One app's part in a stretch, as the story's selection reads it. */
public data class Contributor(
    val uid: Int,
    val label: String?,
    /** Modeled (SPEC 6.5). */
    val mah: Double,
    val alarmWakeups: Long = 0,
    val wakelockMs: Long = 0,
)

/** What a story is selected from: a stretch, who used the battery in it, and the open insights. */
public data class StoryInput(
    val subject: StorySubject,
    val span: StorySpan,
    /** The share base: the measured drain, or the modeled total where that is larger. */
    val totalMah: Double,
    /** Any order. */
    val apps: List<Contributor>,
    val deviceMah: Double = 0.0,
    val unattributedMah: Double = 0.0,
    val deepSleepShare: Double? = null,
    val open: List<Insight> = emptyList(),
)

/**
 * SPEC 9.3 selection, as a pure function: which causes a story names and which open insight is its
 * anomaly. The words are in `:core:ui` (ADR-0054 §13).
 */
public object DrainStory {
    public const val MAX_CAUSES: Int = 3

    /** A contributor under this share of the stretch is not a cause worth a sentence. */
    public const val MIN_CAUSE_SHARE: Double = 0.05

    /** SPEC 6.5: the unattributed drain is named above 10% of the total. */
    public const val MIN_UNATTRIBUTED_SHARE: Double = 0.10

    /** Fewer wakeups than this say nothing about why an app used what it did. */
    public const val MIN_WAKEUPS: Long = 10

    public const val MIN_HELD_AWAKE_MS: Long = 10 * 60_000L

    public fun compose(input: StoryInput): Story {
        val own = (input.subject as? StorySubject.App)?.let { subject -> input.apps.firstOrNull { it.uid == subject.uid } }
        return Story(
            subject = input.subject,
            span = input.span,
            appMah = own?.mah,
            appShare = own?.let { share(it.mah, input.totalMah) },
            causes = if (input.subject is StorySubject.App) listOfNotNull(own?.let { cause(it, input.totalMah) }) else causes(input),
            deepSleepShare = input.deepSleepShare,
            anomaly = anomaly(input),
        )
    }

    /** The largest contributors, each at least [MIN_CAUSE_SHARE], largest first. */
    private fun causes(input: StoryInput): List<StoryCause> {
        val unattributed = share(input.unattributedMah, input.totalMah)
        val all =
            input.apps.map { cause(it, input.totalMah) } +
                StoryCause.Device(share(input.deviceMah, input.totalMah)) +
                listOfNotNull(StoryCause.Unattributed(unattributed).takeIf { unattributed > MIN_UNATTRIBUTED_SHARE })
        return all.filter { it.share >= MIN_CAUSE_SHARE }.sortedByDescending { it.share }.take(MAX_CAUSES)
    }

    private fun cause(
        app: Contributor,
        totalMah: Double,
    ) = StoryCause.App(
        uid = app.uid,
        label = app.label,
        share = share(app.mah, totalMah),
        wakeups = app.alarmWakeups.takeIf { it >= MIN_WAKEUPS },
        heldAwakeMs = app.wakelockMs.takeIf { it >= MIN_HELD_AWAKE_MS },
    )

    /**
     * The highest-severity open insight whose evidence lies in the stretch; the newest among equals.
     * An app's story takes only that app's insights.
     */
    private fun anomaly(input: StoryInput): Insight? {
        val uid = (input.subject as? StorySubject.App)?.uid
        return input.open
            .filter { it.evidence.toWallMs >= input.span.fromWallMs && it.evidence.fromWallMs <= input.span.toWallMs }
            .filter { uid == null || it.uid == uid }
            .sortedWith(compareByDescending<Insight> { it.severity }.thenByDescending { it.createdWallMs })
            .firstOrNull()
    }

    private fun share(
        mah: Double,
        totalMah: Double,
    ): Double = if (totalMah > 0) (mah / totalMah).coerceIn(0.0, 1.0) else 0.0
}

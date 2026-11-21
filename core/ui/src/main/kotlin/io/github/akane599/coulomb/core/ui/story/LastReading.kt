// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.story

import android.content.res.Resources
import io.github.akane599.coulomb.core.model.story.StorySpan
import io.github.akane599.coulomb.core.ui.R
import io.github.akane599.coulomb.core.ui.format.Formats

private const val MS_PER_MINUTE = 60_000L

/**
 * The since-unplugged verdict of an old reading. The story's own line counts "ago" to the reading,
 * which is not now, so this one names its end instead, as Drain's note does (ADR-0069). Now and the
 * Level widget both use it.
 */
public fun lastReadingLine(
    resources: Resources,
    formats: Formats,
    span: StorySpan,
): String {
    val duration = formats.duration((span.durationMs / MS_PER_MINUTE).coerceAtLeast(0))
    val fell = span.levelDropPct ?: 0
    return if (fell <= 0) {
        resources.getString(R.string.story_to_last_reading_flat, duration)
    } else {
        resources.getString(R.string.story_to_last_reading, duration, formats.percent(fell))
    }
}

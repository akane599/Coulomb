// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.story

import android.content.res.Resources
import io.github.akane599.coulomb.core.model.story.Story
import io.github.akane599.coulomb.core.model.story.StoryCause
import io.github.akane599.coulomb.core.model.story.StorySubject
import io.github.akane599.coulomb.core.ui.R
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.insight.InsightCopy
import kotlin.math.roundToInt

/** What a story sentence is about, so a screen can open its evidence (SPEC 9.3: every noun is a link). */
public sealed interface StoryLink {
    public data class App(
        val uid: Int,
    ) : StoryLink

    public data class Insight(
        val id: Long,
    ) : StoryLink
}

public data class StorySentence(
    val text: String,
    val about: StoryLink? = null,
)

/**
 * A Drain Story in words (SPEC 9.3): one verdict sentence, up to three cause sentences and at most
 * one anomaly sentence, from templates. Numbers come from [Formats]; no sentence passes 22 words.
 */
public class StoryCopy(
    private val resources: Resources,
    private val formats: Formats,
    private val insights: InsightCopy,
) {
    /** The strings in use are Turkish, whose suffixes follow the word before them ([TurkishSuffix]). */
    private val turkish = resources.getBoolean(R.bool.story_turkish_suffixes)

    public fun sentences(story: Story): List<StorySentence> {
        val subject = story.subject
        val sleep = story.deepSleepShare?.takeIf { subject is StorySubject.ScreenOff }?.let(::deepSleep)
        val causes =
            if (subject is StorySubject.App) {
                story.causes.filterIsInstance<StoryCause.App>().flatMap(::did)
            } else {
                // How the phone slept takes one of the three places.
                story.causes.take(if (sleep == null) MAX_CAUSES else MAX_CAUSES - 1).map(::cause)
            }
        val anomaly =
            story.anomaly?.let { insight ->
                val name =
                    (subject as? StorySubject.App)?.label
                        ?: story.causes
                            .filterIsInstance<StoryCause.App>()
                            .firstOrNull { it.uid == insight.uid }
                            ?.label
                StorySentence(
                    insights.text(insight, name).title + resources.getString(R.string.story_full_stop),
                    StoryLink.Insight(insight.id),
                )
            }
        return listOf(verdict(story)) + causes + listOfNotNull(sleep, anomaly)
    }

    /**
     * The story in one sentence, for Now and the surfaces outside the app (SPEC 10.2, 12): the
     * verdict, and for "since unplugged" the app that used most of the drain when one is a cause.
     */
    public fun line(story: Story): StorySentence {
        val top = story.causes.filterIsInstance<StoryCause.App>().firstOrNull()
        val drop = story.span.levelDropPct?.takeIf { it > 0 }
        if (story.subject != StorySubject.SinceUnplugged || top == null || drop == null) return verdict(story)
        val sentence =
            text(
                R.string.story_unplugged_with_app,
                formats.hours(story.span.durationMs / MS_PER_HOUR),
                formats.percent(drop),
                formats.appName(top.label, top.uid),
                formats.share(top.share),
            )
        return StorySentence(sentence, StoryLink.App(top.uid))
    }

    /** The story's first sentence: what the battery did over the stretch. */
    public fun verdict(story: Story): StorySentence {
        val span = story.span
        val duration = formats.hours(span.durationMs / MS_PER_HOUR)
        val level = level(span.levelDropPct)
        return when (val subject = story.subject) {
            StorySubject.SinceUnplugged -> {
                StorySentence(
                    when (level) {
                        is Level.Fell -> text(R.string.story_unplugged, duration, level.points)
                        is Level.Rose -> text(R.string.story_unplugged_rose, duration, level.points)
                        Level.Flat -> text(R.string.story_unplugged_flat, duration)
                        Level.Unknown -> text(R.string.story_unplugged_unknown, duration)
                    },
                )
            }

            is StorySubject.ScreenOff -> {
                StorySentence(screenOff(subject, duration, level, span.ratePctPerHour))
            }

            is StorySubject.App -> {
                val name = formats.appName(subject.label, subject.uid)
                val mah = story.appMah
                val share = story.appShare
                val sentence =
                    if (mah == null || share == null) {
                        text(R.string.story_app_none, name)
                    } else {
                        text(R.string.story_app, name, formats.mah(mah).joined(), formats.share(share))
                    }
                StorySentence(sentence, StoryLink.App(subject.uid))
            }
        }
    }

    /**
     * What the level did over a stretch. A rise is not "no change", and neither is a level that is
     * not known at both ends (audit BUG-018).
     */
    private sealed interface Level {
        data class Fell(
            val points: String,
        ) : Level

        data class Rose(
            val points: String,
        ) : Level

        data object Flat : Level

        data object Unknown : Level
    }

    private fun level(dropPct: Int?): Level =
        when {
            dropPct == null -> Level.Unknown
            dropPct > 0 -> Level.Fell(formats.percent(dropPct))
            dropPct < 0 -> Level.Rose(formats.percent(-dropPct))
            else -> Level.Flat
        }

    private fun screenOff(
        subject: StorySubject.ScreenOff,
        duration: String,
        level: Level,
        ratePctPerHour: Double?,
    ): String {
        val night = subject.overnight
        return when (level) {
            Level.Flat -> text(if (night) R.string.story_night_flat else R.string.story_screen_off_flat, duration)
            Level.Unknown -> text(if (night) R.string.story_night_unknown else R.string.story_screen_off_unknown, duration)
            is Level.Rose -> text(if (night) R.string.story_night_rose else R.string.story_screen_off_rose, duration, level.points)
            is Level.Fell -> fell(night, duration, level.points, ratePctPerHour)
        }
    }

    private fun fell(
        night: Boolean,
        duration: String,
        points: String,
        ratePctPerHour: Double?,
    ): String {
        val rate = ratePctPerHour?.let { text(R.string.story_rate, formats.rate(it), formats.rateUnit.replace("/", "/⁠")) }
        return if (rate == null) {
            text(if (night) R.string.story_night_no_rate else R.string.story_screen_off_no_rate, duration, points)
        } else {
            text(if (night) R.string.story_night else R.string.story_screen_off, duration, points, rate)
        }
    }

    private fun cause(cause: StoryCause): StorySentence =
        when (cause) {
            is StoryCause.App -> {
                val name = formats.appName(cause.label, cause.uid)
                val wakeups = cause.wakeups
                val held = cause.heldAwakeMs
                val sentence =
                    when {
                        wakeups != null -> {
                            text(
                                R.string.story_cause_wakeups,
                                share(cause.share, subject = true),
                                owner(name),
                                formats.integer(wakeups),
                            )
                        }

                        held != null -> {
                            text(R.string.story_cause_held, share(cause.share, subject = true), owner(name), formats.held(held))
                        }

                        else -> {
                            text(R.string.story_cause_app, share(cause.share, subject = false), name)
                        }
                    }
                StorySentence(sentence, StoryLink.App(cause.uid))
            }

            is StoryCause.Device -> {
                StorySentence(text(R.string.story_cause_device, share(cause.share, subject = false)))
            }

            is StoryCause.Unattributed -> {
                StorySentence(text(R.string.story_cause_unattributed, share(cause.share, subject = true)))
            }
        }

    /** What an app did, in its own story, where its share is already in the verdict. */
    private fun did(cause: StoryCause.App): List<StorySentence> =
        listOfNotNull(
            cause.wakeups?.let { StorySentence(text(R.string.story_app_wakeups, formats.integer(it)), StoryLink.App(cause.uid)) },
            cause.heldAwakeMs?.let { StorySentence(text(R.string.story_app_held, formats.held(it)), StoryLink.App(cause.uid)) },
        )

    private fun deepSleep(share: Double): StorySentence {
        val percent = formats.share(share)
        val points = (share * PERCENT).roundToInt()
        // The suffix follows the number on screen: ">%99" and "<%1" are not 100 and 0, so they take
        // the wording without one (audit BUG-021).
        val suffix = if (turkish && percent == formats.percent(points)) TurkishSuffix.percentIn(points) else null
        val part = if (suffix == null) text(R.string.story_percent_in, percent) else "$percent’$suffix"
        return StorySentence(text(R.string.story_deep_sleep, part))
    }

    /**
     * A share as the sentence's subject ("Half", "33%") or its object ("half", "33%"): Turkish
     * marks the two differently. Within five points of a half it is said as a half.
     */
    private fun share(
        share: Double,
        subject: Boolean,
    ): String =
        when {
            share in HALF_FROM..HALF_TO -> resources.getString(if (subject) R.string.story_half_subject else R.string.story_half_object)
            subject -> text(R.string.story_share_subject, formats.share(share))
            else -> text(R.string.story_share_object, formats.share(share))
        }

    /** [name] as the one that did something: in Turkish the genitive, where the name can take it. */
    private fun owner(name: String): String = (if (turkish) TurkishSuffix.genitive(name) else null) ?: text(R.string.story_owner, name)

    private fun text(
        id: Int,
        vararg args: Any,
    ): String = resources.getString(id, *args)

    private companion object {
        const val MAX_CAUSES = 3
        const val MS_PER_HOUR = 3_600_000.0
        const val PERCENT = 100.0
        const val HALF_FROM = 0.45
        const val HALF_TO = 0.55
    }
}

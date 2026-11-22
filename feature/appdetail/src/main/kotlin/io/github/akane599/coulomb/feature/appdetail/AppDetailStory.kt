// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.AppDetailReport
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.insights.Contributor
import io.github.akane599.coulomb.core.insights.DrainStory
import io.github.akane599.coulomb.core.insights.InsightStore
import io.github.akane599.coulomb.core.insights.Rules
import io.github.akane599.coulomb.core.insights.ShownInsight
import io.github.akane599.coulomb.core.insights.StoryInput
import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.insight.ActionId
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.model.story.Story
import io.github.akane599.coulomb.core.model.story.StorySpan
import io.github.akane599.coulomb.core.model.story.StorySubject
import io.github.akane599.coulomb.core.ui.insight.InsightCard
import io.github.akane599.coulomb.core.ui.insight.InsightCardActions
import io.github.akane599.coulomb.core.ui.insight.InsightCopy
import io.github.akane599.coulomb.core.ui.insight.insightAction
import io.github.akane599.coulomb.core.ui.story.StoryCopy
import io.github.akane599.coulomb.core.ui.story.StoryLink

/** A paragraph reads best under about 65 characters a line. */
private val StoryMeasure = 560.dp

/**
 * Of the app's open insights, most severe first, the first whose evidence lies in [detail]'s
 * window (SPEC 9.3): one from another date does not hide it (audit BUG-033).
 */
internal fun List<ShownInsight>.inWindow(detail: AppDetailReport): ShownInsight? {
    val drains = detail.report.drains
    return firstOrNull { it.insight.evidence.toWallMs >= drains.fromWallMs && it.insight.evidence.fromWallMs <= drains.toWallMs }
}

/**
 * The app's Drain Story over the window (SPEC 10.4, 9.3): what it did, and as its anomaly the
 * app's open insight when that insight's evidence lies in the window.
 */
internal fun appStory(
    view: AppDetailView,
    insight: Insight?,
): Story {
    val detail = view.detail
    val report = detail.report
    val drop = report.startLevel?.let { start -> report.endLevel?.let { start - it } }
    // The app's own total, with overlaps between its wakelocks removed: its rows cannot be added up (ADR-0039 §4).
    val heldMs = detail.counters.partialWakelockMs ?: 0
    return DrainStory.compose(
        StoryInput(
            subject = StorySubject.App(detail.uid, detail.label),
            span = StorySpan(report.drains.fromWallMs, report.drains.toWallMs, report.durationMs, drop, ratePctPerHour = null),
            totalMah = view.denominator,
            apps = listOfNotNull(view.mah?.let { Contributor(detail.uid, detail.label, it, detail.counters.alarmWakeups ?: 0, heldMs) }),
            open = listOfNotNull(insight),
        ),
    )
}

/**
 * The story under the header, then the card of an Act insight (ADR-0026 §3). The header already
 * gives the story's verdict as figures, and the card's title is the anomaly sentence, so neither
 * is said twice (ADR-0054 §14). Nothing when there is nothing to say.
 */
@Composable
internal fun StoryAndInsight(
    story: Story,
    shown: ShownInsight?,
    copy: StoryCopy,
    insights: InsightCopy,
    actions: AppDetailActions,
    onWhy: () -> Unit,
) {
    val card = shown?.takeIf { it.insight.severity == InsightSeverity.ACT && story.anomaly?.id == it.insight.id }
    val sentences =
        copy
            .sentences(story)
            .drop(1)
            .filterNot { card != null && it.about is StoryLink.Insight }
    if (sentences.isEmpty() && card == null) return
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16)) {
        if (sentences.isNotEmpty()) {
            Text(
                sentences.joinToString(" ") { it.text },
                Modifier.widthIn(max = StoryMeasure),
                color = CoulombTheme.colors.textPrimary,
                style = CoulombTheme.typography.body,
            )
        }
        if (card != null) {
            val id = card.insight.id
            InsightCard(
                severity = card.insight.severity,
                text = insights.text(card.insight, card.appLabel),
                actions =
                    InsightCardActions(
                        why = onWhy,
                        snoozeDay = { actions.snoozeInsight(id, InsightStore.SNOOZE_DAY_MS) },
                        snoozeWeek = { actions.snoozeInsight(id, InsightStore.SNOOZE_WEEK_MS) },
                        dismiss = { actions.dismissInsight(id) },
                    ),
                // Already on the app's page: "open the app's page" would lead nowhere.
                action =
                    insightAction(
                        Rules.actions(card.insight.rule),
                        card.insight,
                        except = setOf(ActionId.OPEN_APP_DETAIL),
                        onAction = actions.insightAction,
                    ),
                modifier = Modifier.padding(top = if (sentences.isEmpty()) 0.dp else CoulombSpacing.Space16),
            )
        }
    }
}

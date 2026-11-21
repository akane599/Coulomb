// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.InsightState
import io.github.akane599.coulomb.core.model.insight.Evidence
import io.github.akane599.coulomb.core.model.insight.EvidenceUnit
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.model.insight.RuleId
import io.github.akane599.coulomb.core.model.story.StoryCause
import io.github.akane599.coulomb.core.model.story.StorySpan
import io.github.akane599.coulomb.core.model.story.StorySubject
import org.junit.Test

class DrainStoryTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L
    private val night = StorySpan(now - 8 * hour, now, 8 * hour, levelDropPct = 6, ratePctPerHour = 0.8)

    private fun insight(
        id: Long,
        rule: RuleId,
        uid: Int?,
        fromWallMs: Long = now - 4 * hour,
        toWallMs: Long = now - 2 * hour,
    ) = Insight(
        id,
        rule,
        rule.severity,
        InsightState.NEW,
        null,
        uid,
        null,
        Evidence(1.0, 0.5, EvidenceUnit.COUNT, fromWallMs, toWallMs),
        now - id,
    )

    private val apps =
        listOf(
            Contributor(10_001, "Small", 4.0),
            Contributor(10_002, "Waker", 50.0, alarmWakeups = 41, wakelockMs = 3 * 60_000L),
            Contributor(10_003, "Holder", 20.0, alarmWakeups = 3, wakelockMs = 34 * 60_000L),
            Contributor(10_004, "Plain", 12.0),
        )

    @Test
    fun aStoryNamesTheThreeLargestCausesLargestFirst() {
        val story =
            DrainStory.compose(
                StoryInput(
                    StorySubject.ScreenOff(overnight = true),
                    night,
                    totalMah = 100.0,
                    apps = apps,
                    deviceMah = 8.0,
                    deepSleepShare = 0.91,
                ),
            )
        assertThat(story.causes)
            .containsExactly(
                // An app and what it did are one cause: 41 wakeups say why, 3 minutes held awake do not.
                StoryCause.App(10_002, "Waker", 0.5, wakeups = 41, heldAwakeMs = null),
                StoryCause.App(10_003, "Holder", 0.2, wakeups = null, heldAwakeMs = 34 * 60_000L),
                StoryCause.App(10_004, "Plain", 0.12, wakeups = null, heldAwakeMs = null),
            ).inOrder()
        assertThat(story.deepSleepShare).isEqualTo(0.91)
        assertThat(story.appMah).isNull()
    }

    @Test
    fun smallSharesAreNoCauseAndTheUnattributedDrainIsOneAboveTenPercent() {
        val few = listOf(apps[0], apps[3])
        val story = DrainStory.compose(StoryInput(StorySubject.SinceUnplugged, night, 100.0, few, deviceMah = 30.0, unattributedMah = 54.0))
        assertThat(story.causes)
            .containsExactly(StoryCause.Unattributed(0.54), StoryCause.Device(0.3), StoryCause.App(10_004, "Plain", 0.12, null, null))
            .inOrder()
        // Exactly 10% unattributed is not named; 4% for an app is not either.
        val quiet = DrainStory.compose(StoryInput(StorySubject.SinceUnplugged, night, 100.0, few, deviceMah = 4.0, unattributedMah = 10.0))
        assertThat(quiet.causes).containsExactly(StoryCause.App(10_004, "Plain", 0.12, null, null))
        // Without a total there are no shares and no causes.
        assertThat(DrainStory.compose(StoryInput(StorySubject.SinceUnplugged, night, 0.0, few)).causes).isEmpty()
    }

    @Test
    fun theAnomalyIsTheMostSevereOpenInsightInsideTheStretch() {
        val open =
            listOf(
                insight(1, RuleId.SCAN_HEAVY, null),
                insight(2, RuleId.JOB_CHURN, 10_004),
                insight(3, RuleId.ALARM_STORM, 10_002),
                insight(4, RuleId.EXCESSIVE_WAKELOCK, 10_003),
                // More severe than any, but from the day before.
                insight(5, RuleId.HOT_BATTERY, null, fromWallMs = now - 30 * hour, toWallMs = now - 29 * hour),
            )
        val story = DrainStory.compose(StoryInput(StorySubject.ScreenOff(overnight = true), night, 100.0, apps, open = open))
        // Two Act insights: the newer one.
        assertThat(story.anomaly?.id).isEqualTo(3)
        assertThat(DrainStory.compose(StoryInput(StorySubject.ScreenOff(overnight = true), night, 100.0, apps)).anomaly).isNull()
    }

    @Test
    fun anAppsStoryIsAboutThatAppAlone() {
        val subject = StorySubject.App(10_003, "Holder")
        val open = listOf(insight(3, RuleId.ALARM_STORM, 10_002), insight(2, RuleId.JOB_CHURN, 10_003))
        val story = DrainStory.compose(StoryInput(subject, night, 100.0, apps, deviceMah = 8.0, open = open))
        assertThat(story.appMah).isEqualTo(20.0)
        assertThat(story.appShare).isEqualTo(0.2)
        assertThat(story.causes).containsExactly(StoryCause.App(10_003, "Holder", 0.2, wakeups = null, heldAwakeMs = 34 * 60_000L))
        // The other app's Act insight is not this app's anomaly.
        assertThat(story.anomaly?.id).isEqualTo(2)
        // An app with nothing stored in the stretch has a story with no figures.
        val absent = DrainStory.compose(StoryInput(StorySubject.App(10_999, null), night, 100.0, apps))
        assertThat(absent.appMah).isNull()
        assertThat(absent.causes).isEmpty()
    }
}

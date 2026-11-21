// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class InsightEngineTest {
    private val engine = InsightEngine(Thresholds.bundled())
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val lastDay = Span(now - 24 * hour, now)

    private val ctx =
        RuleContext(
            now,
            day = DayUse(lastDay, 8 * hour, listOf(AppDayUse(10_123, partialWakelockMs = 3 * hour, jobCount = 80))),
            temperatures = listOf(TempSample(now, 47.0)),
        )

    @Test
    fun everyRuleRunsByDefaultAndOnlyThoseWhoseInputsChangedOtherwise() {
        assertThat(
            engine.evaluate(ctx).map { it.rule },
        ).containsExactly(RuleId.EXCESSIVE_WAKELOCK, RuleId.JOB_CHURN, RuleId.HOT_BATTERY).inOrder()
        assertThat(engine.evaluate(ctx, setOf(RuleInput.TEMPERATURE)).map { it.rule }).containsExactly(RuleId.HOT_BATTERY)
        assertThat(engine.evaluate(ctx, setOf(RuleInput.HEALTH))).isEmpty()
        assertThat(engine.evaluate(ctx, emptySet())).isEmpty()
    }

    @Test
    fun aSubjectFoundTwiceKeepsTheLaterFinding() {
        val earlier = Span(now - 48 * hour, now - 40 * hour)
        val later = Span(now - 9 * hour, now - hour)
        val holds =
            listOf(
                TagHold("*alarm*", 10_123, 3 * hour, later, windowId = 2),
                TagHold("*alarm*", 10_123, 5 * hour, earlier, windowId = 1),
                TagHold("AudioMix", 1041, 2 * hour, earlier, windowId = 1),
            )
        val found = engine.evaluate(RuleContext(now, tagHolds = holds))
        assertThat(found.map { it.subject?.key to it.windowId }).containsExactly("wakelock:*alarm*" to 2L, "wakelock:AudioMix" to 1L)
    }

    @Test
    fun theSameDataGivesTheSameFindings() {
        assertThat(engine.evaluate(ctx)).isEqualTo(engine.evaluate(ctx))
    }

    @Test
    fun aBrokenThresholdFileFailsLoudly() {
        assertThrows(IllegalStateException::class.java) { Thresholds.parse("""{"R01":{"wakelockHours":"two"}}""") }
        assertThrows(
            IllegalStateException::class.java,
        ) { Thresholds.parse("""{"R01":{}}""").of(RuleId.EXCESSIVE_WAKELOCK)["wakelockHours"] }
        assertThrows(IllegalStateException::class.java) { Thresholds.parse("{}").of(RuleId.SELF_COST) }
        assertThat(Thresholds.parse("""{"R01":{"wakelockHours":2}}""").of(RuleId.EXCESSIVE_WAKELOCK)["wakelockHours"]).isEqualTo(2.0)
    }
}

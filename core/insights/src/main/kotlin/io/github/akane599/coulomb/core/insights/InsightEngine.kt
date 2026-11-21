// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

/**
 * Runs the rules over stored data (SPEC 9). Deterministic: the same data and thresholds give the
 * same candidates. What becomes of a candidate is [InsightLifecycle]'s to decide.
 */
public class InsightEngine(
    private val thresholds: Thresholds,
    private val rules: List<Rule> = Rules.all,
) {
    /**
     * The findings in [ctx] of the rules that read any of [changed] (SPEC 7.5), at most one per rule
     * and subject: of several about the same subject, the one whose evidence ends last.
     */
    public fun evaluate(
        ctx: RuleContext,
        changed: Set<RuleInput> = RuleInput.entries.toSet(),
    ): List<InsightCandidate> =
        rules
            .filter { rule -> rule.inputs.any { it in changed } }
            .flatMap { rule ->
                rule
                    .evaluate(ctx, thresholds.of(rule.id))
                    .groupBy { it.subject?.key }
                    .map { (_, sameSubject) -> sameSubject.maxBy { it.evidence.toWallMs } }
            }
}

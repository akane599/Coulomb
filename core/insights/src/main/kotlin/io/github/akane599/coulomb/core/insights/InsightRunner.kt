// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

/**
 * One pass of the insight engine (SPEC 7.5, step 6): load what the rules read, run them, and store
 * what they found. It reads stored data only and wakes nothing.
 */
public class InsightRunner(
    private val loader: RuleContextLoader,
    private val engine: InsightEngine,
    private val store: InsightStore,
) {
    /** Runs every rule as of [nowWallMs] and returns the insights that became new. */
    public suspend fun run(nowWallMs: Long): List<Insight> =
        store.record(engine.evaluate(loader.load(nowWallMs, engine.minScreenOffMs)), nowWallMs)
}

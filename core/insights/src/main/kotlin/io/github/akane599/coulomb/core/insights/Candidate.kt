// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.model.insight.Evidence
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.model.insight.RuleId

/**
 * What an insight is about, so a rule keeps one open insight per subject (SPEC 9.1). [key] is
 * stored as `Insight.subjectKey`: `uid:10123`, `wakelock:<tag>`, `charger:3`, `session:42`. A rule
 * about the whole device has no subject.
 */
public data class Subject(
    val key: String,
    val uid: Int? = null,
) {
    public companion object {
        public fun app(uid: Int): Subject = Subject("uid:$uid", uid)

        public fun wakelock(
            tag: String,
            uid: Int?,
        ): Subject = Subject("wakelock:$tag", uid)

        public fun charger(id: Long): Subject = Subject("charger:$id")

        public fun session(id: Long): Subject = Subject("session:$id")
    }
}

/** A finding a rule made (SPEC 9.1), before the lifecycle decides whether it becomes an insight. */
public data class InsightCandidate(
    val rule: RuleId,
    val severity: InsightSeverity,
    val subject: Subject?,
    /** The stored window the finding came from, when it came from one. */
    val windowId: Long?,
    val evidence: Evidence,
    /** An app the finding points at without being about it, such as the top consumer when the battery ran hot. */
    val relatedUid: Int? = null,
)

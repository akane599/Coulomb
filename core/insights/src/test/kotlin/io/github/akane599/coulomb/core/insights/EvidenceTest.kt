// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.insight.Evidence
import io.github.akane599.coulomb.core.model.insight.EvidenceUnit
import io.github.akane599.coulomb.core.model.insight.RuleId
import org.junit.Test

class EvidenceTest {
    @Test
    fun evidenceSurvivesItsJson() {
        val evidence =
            Evidence(
                measured = 2.75,
                threshold = 2.0,
                unit = EvidenceUnit.HOURS,
                fromWallMs = 1_793_491_200_000,
                toWallMs = 1_793_577_600_000,
                values = mapOf("holds" to 41.0, "share" to 0.125),
                labels = mapOf("tag" to "*job*/com.example/.Sync \"quoted\" \\ ü"),
                sessionId = 9_007_199_254_740_993,
            )
        assertThat(EvidenceJson.decode(EvidenceJson.encode(evidence))).isEqualTo(evidence)
        val plain = evidence.copy(values = emptyMap(), labels = emptyMap(), sessionId = null)
        assertThat(EvidenceJson.encode(plain)).doesNotContain("values")
        assertThat(EvidenceJson.encode(plain)).doesNotContain("session")
        assertThat(EvidenceJson.decode(EvidenceJson.encode(plain))).isEqualTo(plain)
    }

    @Test
    fun unknownFieldsAreIgnoredAndBrokenTextReadsAsNothing() {
        val json = """{"measured":1,"threshold":2.5,"unit":"COUNT","from":10,"to":20,"later":{"a":[1]},"values":{"n":3,"bad":"x"}}"""
        assertThat(EvidenceJson.decode(json)).isEqualTo(Evidence(1.0, 2.5, EvidenceUnit.COUNT, 10, 20, mapOf("n" to 3.0)))
        for (broken in listOf("", "{}", "[1]", "not json", """{"measured":1,"threshold":2,"unit":"FURLONGS","from":1,"to":2}""")) {
            assertThat(EvidenceJson.decode(broken)).isNull()
        }
    }

    @Test
    fun everyRuleHasItsOwnCode() {
        assertThat(RuleId.entries.map { it.code }).isEqualTo((1..20).map { "R%02d".format(it) })
        assertThat(RuleId.of("R07")).isEqualTo(RuleId.WEAK_SIGNAL_TAX)
        assertThat(RuleId.of("R99")).isNull()
    }
}

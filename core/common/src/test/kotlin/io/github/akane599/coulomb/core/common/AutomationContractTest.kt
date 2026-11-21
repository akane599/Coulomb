// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.common

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Version 1 of the Automation API is pinned in `docs/automation-api-v1.json` (ADR-0065 §9): other
 * people's automations are written against these names and numbers. A change here that fails
 * this test either adds to the version, and then the file gains the same lines, or it changes a
 * meaning, and then it is a version 2 beside this one and not an edit.
 */
class AutomationContractTest {
    @Test
    fun versionOneIsWhatTheFileSays() {
        val pinned = File("../../docs/automation-api-v1.json")

        assertWithMessage("docs/automation-api-v1.json; expected content:\n${contract()}")
            .that(pinned.takeIf(File::isFile)?.readText())
            .isEqualTo(contract())
    }

    @Test
    fun aResultIsNeverZeroAndEveryCodeMeansOneThing() {
        val codes = RESULTS.map { it.second }

        // 0 is what a broadcast nobody answered leaves.
        assertThat(codes).doesNotContain(0)
        assertThat(codes).containsNoDuplicates()
        assertThat(REASONS).containsNoDuplicates()
        assertThat(AutomationContract.COLUMNS).containsNoDuplicates()
        assertThat(AutomationContract.METRICS.keys).containsExactlyElementsIn(AutomationContract.PATHS).inOrder()
    }

    private fun contract(): String =
        buildString {
            appendLine("{")
            appendLine("  \"api_version\": ${AutomationContract.API_VERSION},")
            appendLine("  \"application_id\": \"$ID\",")
            appendLine("  \"permission\": \"${AutomationContract.permission(ID)}\",")
            appendLine("  \"actions\": ${list(ACTIONS)},")
            appendLine("  \"extras\": ${list(EXTRAS)},")
            appendLine("  \"doze_profiles\": ${list(AutomationContract.DOZE_PROFILES)},")
            appendLine("  \"results\": {")
            appendLine(RESULTS.joinToString(",\n") { (name, code) -> "    \"$name\": $code" })
            appendLine("  },")
            appendLine("  \"reasons\": ${list(REASONS)},")
            appendLine("  \"authority\": \"${AutomationContract.authority(ID)}\",")
            appendLine("  \"columns\": ${list(AutomationContract.COLUMNS)},")
            appendLine("  \"states\": ${list(STATES)},")
            appendLine("  \"units\": ${list(UNITS)},")
            appendLine("  \"paths\": {")
            appendLine(AutomationContract.METRICS.entries.joinToString(",\n") { (path, metrics) -> "    \"$path\": ${list(metrics)}" })
            appendLine("  }")
            appendLine("}")
        }

    private fun list(items: List<String>): String = items.joinToString(", ", "[", "]") { "\"$it\"" }

    private companion object {
        /** The released application id (`coulomb.applicationId`); a build with another id has its own names. */
        const val ID = "io.github.akane599.coulomb"
        val ACTIONS =
            listOf(
                AutomationContract.actionSnapshotNow(ID),
                AutomationContract.actionSetChargeTarget(ID),
                AutomationContract.actionApplyDozeProfile(ID),
            )
        val EXTRAS =
            listOf(
                AutomationContract.extraChargeTargetPct(ID),
                AutomationContract.extraDozeProfile(ID),
                AutomationContract.extraApiVersion(ID),
                AutomationContract.extraRetryAfterS(ID),
            )
        val RESULTS =
            listOf(
                "done" to AutomationContract.Result.DONE,
                "accepted" to AutomationContract.Result.ACCEPTED,
                "unchanged" to AutomationContract.Result.UNCHANGED,
                "invalid" to AutomationContract.Result.INVALID,
                "rate_limited" to AutomationContract.Result.RATE_LIMITED,
                "blocked" to AutomationContract.Result.BLOCKED,
                "failed" to AutomationContract.Result.FAILED,
                "unverified" to AutomationContract.Result.UNVERIFIED,
            )
        val REASONS =
            listOf(
                AutomationContract.Reason.OK,
                AutomationContract.Reason.OFF,
                AutomationContract.Reason.UNKNOWN_ACTION,
                AutomationContract.Reason.BAD_TARGET,
                AutomationContract.Reason.BAD_PROFILE,
                AutomationContract.Reason.RATE_LIMITED,
                AutomationContract.Reason.SCREEN_OFF,
                AutomationContract.Reason.NEEDS_FIRST_APPLY,
                AutomationContract.Reason.PROFILE_ACTIVE,
                AutomationContract.Reason.ACTION_CENTER_OFF,
                AutomationContract.Reason.DRIFTED,
                AutomationContract.Reason.FAILED,
                AutomationContract.Reason.UNVERIFIED,
                AutomationContract.Reason.TIMEOUT,
            )
        val STATES =
            listOf(
                AutomationContract.STATE_OK,
                AutomationContract.STATE_COLLECTING,
                AutomationContract.STATE_UNAVAILABLE,
                AutomationContract.STATE_NOT_APPLICABLE,
            )
        val UNITS =
            listOf(
                AutomationContract.UNIT_PCT,
                AutomationContract.UNIT_PCT_PER_H,
                AutomationContract.UNIT_MIN,
                AutomationContract.UNIT_PCT_OF_WINDOW,
            )
    }
}

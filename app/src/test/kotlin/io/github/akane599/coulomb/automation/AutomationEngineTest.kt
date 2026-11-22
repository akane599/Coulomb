// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.automation

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.analytics.DozeProfile
import io.github.akane599.coulomb.core.collect.SnapshotOutcome
import io.github.akane599.coulomb.core.collect.actions.ActionBlock
import io.github.akane599.coulomb.core.collect.actions.ActionPreview
import io.github.akane599.coulomb.core.collect.actions.ActionRecord
import io.github.akane599.coulomb.core.collect.actions.ActionRequest
import io.github.akane599.coulomb.core.collect.actions.ActionResult
import io.github.akane599.coulomb.core.collect.actions.ActionRisk
import io.github.akane599.coulomb.core.collect.actions.ActionType
import io.github.akane599.coulomb.core.collect.actions.AutomationCalls
import io.github.akane599.coulomb.core.common.AutomationContract.Reason
import io.github.akane599.coulomb.core.common.AutomationContract.Result
import io.github.akane599.coulomb.core.model.ActionOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** SPEC 12.7's three actions against a stand-in phone and Action Center (ADR-0065). Nothing here touches a device. */
class AutomationEngineTest {
    private val phone = Phone()
    private val center = Center(phone)
    private val engine = AutomationEngine(phone, center)

    private fun call(
        name: String,
        target: Int? = null,
        profile: String? = null,
        caller: String? = null,
    ): AutomationAnswer =
        runBlocking { engine.handle(AutomationCall(name, target, profile, caller)) }.also {
            // Calls a few minutes apart, so the limit on calls in a row is out of the way unless a test wants it.
            phone.elapsed += 5 * MINUTE
            phone.wall += 5 * MINUTE
        }

    private fun snapshot() = call(AutomationCalls.SNAPSHOT_NOW)

    private fun doze(profile: String?) = call(AutomationCalls.APPLY_DOZE_PROFILE, profile = profile)

    @Test
    fun nothingIsDoneOrLoggedWhileTheSettingIsOff() {
        phone.on = false

        assertThat(snapshot()).isEqualTo(AutomationAnswer(Result.BLOCKED, Reason.OFF))
        assertThat(call(AutomationCalls.SET_CHARGE_TARGET, target = 70).reason).isEqualTo(Reason.OFF)
        assertThat(doze("balanced").reason).isEqualTo(Reason.OFF)

        assertThat(phone.snapshots).isEqualTo(0)
        assertThat(phone.target).isEqualTo(80)
        assertThat(center.rows).isEmpty()
    }

    /** Audit BUG-001: "accepted" promises new figures, so a failed snapshot or a wait that ran out says so. */
    @Test
    fun aSnapshotThatFailsOrIsNotDoneInTimeIsNotAccepted() {
        phone.outcome = SnapshotOutcome.FAILED
        assertThat(snapshot()).isEqualTo(AutomationAnswer(Result.FAILED, Reason.FAILED))
        phone.outcome = null
        assertThat(snapshot()).isEqualTo(AutomationAnswer(Result.UNVERIFIED, Reason.TIMEOUT))
        // One the debounce covered is still accepted: a snapshot of the last minute stands.
        phone.outcome = SnapshotOutcome.NONE
        assertThat(snapshot()).isEqualTo(AutomationAnswer(Result.ACCEPTED))
    }

    @Test
    fun aSnapshotIsQueuedAndLoggedWithItsCaller() {
        assertThat(call(AutomationCalls.SNAPSHOT_NOW, caller = "org.example.tasks")).isEqualTo(AutomationAnswer(Result.ACCEPTED))

        assertThat(phone.snapshots).isEqualTo(1)
        val row = center.rows.single()
        assertThat(row.type).isEqualTo(ActionType.AUTOMATION_CALL)
        assertThat(row.origin).isEqualTo(ActionOrigin.AUTOMATION)
        assertThat(AutomationCalls.name(row.target)).isEqualTo(AutomationCalls.SNAPSHOT_NOW)
        assertThat(AutomationCalls.caller(row.target)).isEqualTo("org.example.tasks")
        assertThat(row.after).isEqualTo(AutomationCalls.ACCEPTED)
    }

    /** SPEC 7.4: a call must not be a way to wake the phone on a schedule. */
    @Test
    fun aSnapshotIsRefusedWhileTheScreenIsOffOnBattery() {
        phone.awake = false

        assertThat(snapshot()).isEqualTo(AutomationAnswer(Result.BLOCKED, Reason.SCREEN_OFF))
        assertThat(snapshot().code).isEqualTo(Result.BLOCKED)

        assertThat(phone.snapshots).isEqualTo(0)
        // The same refusal again within the hour is one row.
        assertThat(center.rows.map { it.after }).containsExactly(AutomationCalls.REFUSED + Reason.SCREEN_OFF)
    }

    @Test
    fun theFifthSnapshotInTwoHoursWaitsForTheFirstToAge() {
        repeat(AutomationEngine.SNAPSHOTS) { assertThat(snapshot().code).isEqualTo(Result.ACCEPTED) }

        // Four calls, five minutes apart: the first was 20 minutes ago, so 100 minutes are left.
        val fifth = snapshot()
        assertThat(fifth.code).isEqualTo(Result.RATE_LIMITED)
        assertThat(fifth.retryAfterS).isEqualTo(100 * 60)
        assertThat(phone.snapshots).isEqualTo(AutomationEngine.SNAPSHOTS)

        phone.wall += 100 * MINUTE
        assertThat(snapshot().code).isEqualTo(Result.ACCEPTED)
    }

    @Test
    fun aChargeTargetOutsideTheRangeIsRefusedWholeNotBroughtIntoIt() {
        for (bad in listOf(49, 101, -1, null)) {
            assertThat(call(AutomationCalls.SET_CHARGE_TARGET, target = bad)).isEqualTo(AutomationAnswer(Result.INVALID, Reason.BAD_TARGET))
        }
        assertThat(phone.target).isEqualTo(80)
        assertThat(center.rows.first().after).isEqualTo(AutomationCalls.REFUSED + Reason.BAD_TARGET)
    }

    @Test
    fun aChargeTargetIsSetWithItsOldValueOnRecordAndTheSameAgainChangesNothing() {
        assertThat(call(AutomationCalls.SET_CHARGE_TARGET, target = 65)).isEqualTo(AutomationAnswer(Result.DONE))
        assertThat(phone.target).isEqualTo(65)
        val row = center.rows.single()
        assertThat(row.before).isEqualTo("80")
        assertThat(row.after).isEqualTo("65")

        assertThat(call(AutomationCalls.SET_CHARGE_TARGET, target = 65).code).isEqualTo(Result.UNCHANGED)
        assertThat(center.rows).hasSize(1)
    }

    @Test
    fun aDozeProfileNobodyAppliedHereIsNotAppliedByACall() {
        assertThat(doze("balanced")).isEqualTo(AutomationAnswer(Result.BLOCKED, Reason.NEEDS_FIRST_APPLY))
        assertThat(doze("warp")).isEqualTo(AutomationAnswer(Result.INVALID, Reason.BAD_PROFILE))
        assertThat(doze(null).reason).isEqualTo(Reason.BAD_PROFILE)

        assertThat(center.applied).isEmpty()
    }

    @Test
    fun aCallRepeatsOnlyTheChangeTheUserApplied() {
        // The user applied "balanced" over an unset value and reverted it; "aggressive" never.
        center.userApplied("balanced", before = null, after = BALANCED, reverted = true)

        assertThat(doze("aggressive").reason).isEqualTo(Reason.NEEDS_FIRST_APPLY)
        assertThat(
            call(AutomationCalls.APPLY_DOZE_PROFILE, profile = "balanced", caller = "org.example.tasks"),
        ).isEqualTo(AutomationAnswer(Result.DONE))
        assertThat(center.applied).containsExactly(DozeProfile.BALANCED)
        // The Action Center's own row, with the origin, and under it the call's row, which says who asked.
        assertThat(center.rows[0].type).isEqualTo(ActionType.DOZE_PROFILE)
        assertThat(center.rows[0].origin).isEqualTo(ActionOrigin.AUTOMATION)
        val asked = center.rows[1]
        assertThat(asked.type).isEqualTo(ActionType.AUTOMATION_CALL)
        assertThat(AutomationCalls.caller(asked.target)).isEqualTo("org.example.tasks")
        assertThat(asked.before).isEqualTo(AutomationCalls.STOCK)
        assertThat(asked.after).isEqualTo("balanced")

        assertThat(doze("balanced").code).isEqualTo(Result.UNCHANGED)
        assertThat(doze("aggressive").reason).isEqualTo(Reason.PROFILE_ACTIVE)
        assertThat(center.applied).hasSize(1)
    }

    /** A consent is for one change: the same profile over other values, or to other values, is a new one. */
    @Test
    fun aConsentDoesNotCoverTheProfileOverOtherValues() {
        center.userApplied("balanced", before = null, after = BALANCED, reverted = true)

        center.preview = center.preview.copy(before = "light_after_inactive_to=1")
        assertThat(doze("balanced").reason).isEqualTo(Reason.NEEDS_FIRST_APPLY)

        center.preview = center.preview.copy(before = null, after = "$BALANCED,idle_to=9")
        assertThat(doze("balanced").reason).isEqualTo(Reason.NEEDS_FIRST_APPLY)

        // A row a call wrote is not the user's consent to the next call.
        center.rows.clear()
        center.preview = center.preview.copy(after = BALANCED)
        center.rows += record(7, ActionType.DOZE_PROFILE, "balanced", null, BALANCED, ActionOrigin.AUTOMATION, reverted = true)
        assertThat(doze("balanced").reason).isEqualTo(Reason.NEEDS_FIRST_APPLY)
        assertThat(center.applied).isEmpty()
    }

    /** SPEC 13.1: a value changed outside Coulomb is never silently re-applied, by a call either. */
    @Test
    fun aChangeSomethingElseUndidIsNotConsentToApplyItAgain() {
        center.rows +=
            record(
                7,
                ActionType.DOZE_PROFILE,
                "balanced",
                null,
                BALANCED,
                ActionOrigin.USER,
                reverted = false,
            ).copy(driftWallMs = 5, driftValue = "")

        assertThat(doze("balanced").reason).isEqualTo(Reason.NEEDS_FIRST_APPLY)
        assertThat(center.applied).isEmpty()
    }

    /** What the caller sends is never stored: a row holds the contract's words and numbers only. */
    @Test
    fun aCallersOwnTextIsRefusedAndNotStored() {
        assertThat(doze("x".repeat(100_000)).reason).isEqualTo(Reason.BAD_PROFILE)

        val row = center.calls.single()
        assertThat(row.before).isNull()
        assertThat(row.after).isEqualTo(AutomationCalls.REFUSED + Reason.BAD_PROFILE)
    }

    /** A change that did not happen leaves no row that says it did. */
    @Test
    fun theCallsRowIsWrittenFirstAndTakenBackWhenTheChangeFails() {
        phone.failWrites = true
        val thrown = runCatching { call(AutomationCalls.SET_CHARGE_TARGET, target = 65) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(java.io.IOException::class.java)
        assertThat(phone.target).isEqualTo(80)
        assertThat(center.rows).isEmpty()
    }

    /** The reasons the Action Center gives are version 1's words too (`docs/automation-api.md`): a rename changes the API. */
    @Test
    fun theActionCentersReasonsAreTheWordsVersionOneNames() {
        // NO_LOG answers "failed", a version 1 word, rather than a word of its own.
        assertThat(ActionBlock.entries.filter { it != ActionBlock.NO_LOG }.map { it.name.lowercase() })
            .containsExactly(
                "off",
                "no_worker",
                "unreadable",
                "protected",
                "not_installed",
                "already",
                "not_plain_allow",
                "bucket_fixed",
                "system_listed",
                "charging",
                "managed_externally",
                "malformed",
                "nothing_to_change",
                "profile_active",
                "nothing_to_take_over",
                "in_force",
                "not_active",
            )
    }

    @Test
    fun theActionCentersRefusalIsTheCallsRefusal() {
        center.userApplied("balanced", before = null, after = BALANCED, reverted = true)

        center.preview = center.preview.copy(block = ActionBlock.OFF)
        assertThat(doze("balanced")).isEqualTo(AutomationAnswer(Result.BLOCKED, Reason.ACTION_CENTER_OFF))
        center.preview = center.preview.copy(block = ActionBlock.NO_WORKER)
        assertThat(doze("balanced")).isEqualTo(AutomationAnswer(Result.BLOCKED, "no_worker"))
        // A phone that already dozes sooner: the profile would change nothing, and the answer says why.
        center.preview = center.preview.copy(block = ActionBlock.NOTHING_TO_CHANGE)
        assertThat(doze("balanced")).isEqualTo(AutomationAnswer(Result.BLOCKED, "nothing_to_change"))
        assertThat(center.applied).isEmpty()

        center.preview = center.preview.copy(block = null)
        center.applyResult = ActionResult.Failed("exit 1")
        assertThat(doze("balanced")).isEqualTo(AutomationAnswer(Result.FAILED, Reason.FAILED))
        assertThat(center.rows.first().after).isEqualTo(AutomationCalls.REFUSED + Reason.FAILED)
        // The row that said "stock to balanced" is gone: it did not happen.
        assertThat(center.calls.map { it.after }).doesNotContain("balanced")

        // The command ran and could not be read back: the call's row stays, as the Action Center's does.
        center.applyResult = ActionResult.Unverified(9)
        assertThat(doze("balanced")).isEqualTo(AutomationAnswer(Result.UNVERIFIED, Reason.UNVERIFIED))
        assertThat(center.calls.first().after).isEqualTo("balanced")
    }

    @Test
    fun stockRevertsTheAppliedProfileAndNothingElse() {
        assertThat(doze("stock").code).isEqualTo(Result.UNCHANGED)
        assertThat(center.rows).isEmpty()

        center.userApplied("aggressive", before = null, after = "x", reverted = false)
        val applied = center.rows.single().id
        assertThat(doze("stock")).isEqualTo(AutomationAnswer(Result.DONE))
        assertThat(center.reverted).containsExactly(applied)
        val row = center.rows.first()
        assertThat(row.type).isEqualTo(ActionType.AUTOMATION_CALL)
        assertThat(row.before).isEqualTo("aggressive")
        assertThat(row.after).isEqualTo("stock")
    }

    /** SPEC 13.1: a value something else changed is not overwritten, by a call either. */
    @Test
    fun stockOverAValueChangedOutsideCoulombIsRefused() {
        center.userApplied("balanced", before = null, after = BALANCED, reverted = false)
        center.revertResult = ActionResult.Drifted(1, "other")

        assertThat(doze("stock")).isEqualTo(AutomationAnswer(Result.BLOCKED, Reason.DRIFTED))
        assertThat(center.calls.map { it.after }).containsExactly(AutomationCalls.REFUSED + Reason.DRIFTED)
    }

    @Test
    fun aCallerInALoopIsStoppedBeforeAnythingIsRead() {
        val answers =
            (0..AutomationEngine.BURST).map {
                // A second apart: within one minute.
                runBlocking { engine.handle(AutomationCall(AutomationCalls.SET_CHARGE_TARGET, chargeTargetPct = 80)) }.also {
                    phone.elapsed += SECOND
                }
            }

        assertThat(answers.take(AutomationEngine.BURST).map { it.code }.distinct()).containsExactly(Result.UNCHANGED)
        assertThat(answers.last().code).isEqualTo(Result.RATE_LIMITED)
        assertThat(answers.last().retryAfterS).isEqualTo(50)
        // "Already so" is answered without reading the log at all.
        assertThat(center.reads).isEqualTo(0)

        phone.elapsed += MINUTE
        assertThat(call(AutomationCalls.SET_CHARGE_TARGET, target = 80).code).isEqualTo(Result.UNCHANGED)
    }

    @Test
    fun theLogTakesAHundredRowsADayFromAutomationAndThenNothingIsDone() {
        // Alternating targets: every call changes the value, so every call is a row.
        repeat(AutomationEngine.DAILY_ROWS) { index ->
            assertThat(call(AutomationCalls.SET_CHARGE_TARGET, target = 60 + index % 2).code).isEqualTo(Result.DONE)
        }
        val before = phone.target

        val over = call(AutomationCalls.SET_CHARGE_TARGET, target = 90)
        assertThat(over.code).isEqualTo(Result.RATE_LIMITED)
        assertThat(phone.target).isEqualTo(before)
        assertThat(snapshot().code).isEqualTo(Result.RATE_LIMITED)
        assertThat(phone.snapshots).isEqualTo(0)
        assertThat(center.rows).hasSize(AutomationEngine.DAILY_ROWS)
    }

    private class Phone : AutomationPorts {
        var on = true
        var wall = 1_800_000_000_000L
        var elapsed = 0L
        var awake = true
        var target = 80
        var snapshots = 0
        var failWrites = false

        override suspend fun enabled(): Boolean = on

        override fun nowWallMs(): Long = wall

        override fun elapsedMs(): Long = elapsed

        override fun awakeOrPlugged(): Boolean = awake

        /** What the collector answers; null: the wait runs out. */
        var outcome: SnapshotOutcome? = SnapshotOutcome.HEAVY

        override suspend fun snapshot(): SnapshotOutcome? {
            snapshots++
            return outcome
        }

        override suspend fun chargeTarget(): Int = target

        override suspend fun setChargeTarget(pct: Int) {
            if (failWrites) throw java.io.IOException("disk full")
            target = pct
        }
    }

    private class Center(
        private val phone: Phone,
    ) : AutomationActions {
        /** Newest first, as the log is read. */
        val rows = ArrayList<ActionRecord>()
        val applied = ArrayList<DozeProfile>()
        val reverted = ArrayList<Long>()
        var reads = 0
        var preview =
            ActionPreview(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED), ActionRisk.MEDIUM, "put", "delete", null, BALANCED, null)
        var applyResult: ActionResult? = null
        var revertResult: ActionResult? = null
        private var nextId = 100L

        val calls: List<ActionRecord> get() = rows.filter { it.type == ActionType.AUTOMATION_CALL }

        fun userApplied(
            profile: String,
            before: String?,
            after: String,
            reverted: Boolean,
        ) {
            rows.add(0, record(nextId++, ActionType.DOZE_PROFILE, profile, before, after, ActionOrigin.USER, reverted, phone.wall - DAY))
        }

        override suspend fun automationSince(sinceWallMs: Long): List<ActionRecord> {
            reads++
            return rows.filter { it.origin == ActionOrigin.AUTOMATION && it.executedWallMs > sinceWallMs }
        }

        override suspend fun appliedProfile(): ActionRecord? = rows.firstOrNull { it.type == ActionType.DOZE_PROFILE && it.active }

        // Wider than the real query, which leaves automation's rows out: the engine must not lean on that.
        override suspend fun userProfiles(profile: String): List<ActionRecord> =
            rows.filter { it.type == ActionType.DOZE_PROFILE && it.target == profile }

        override suspend fun preview(profile: DozeProfile): ActionPreview = preview

        override suspend fun apply(profile: DozeProfile): ActionResult {
            applyResult?.let { return it }
            applied += profile
            val id = nextId++
            rows.add(
                0,
                record(
                    id,
                    ActionType.DOZE_PROFILE,
                    profile.name.lowercase(),
                    preview.before,
                    BALANCED,
                    ActionOrigin.AUTOMATION,
                    false,
                    phone.wall,
                ),
            )
            return ActionResult.Done(id, BALANCED)
        }

        override suspend fun revert(id: Long): ActionResult {
            revertResult?.let { return it }
            reverted += id
            val index = rows.indexOfFirst { it.id == id }
            rows[index] = rows[index].copy(revertedWallMs = phone.wall)
            return ActionResult.Done(id, "")
        }

        override suspend fun log(
            target: String,
            before: String?,
            after: String,
        ): Long {
            val id = nextId++
            rows.add(0, record(id, ActionType.AUTOMATION_CALL, target, before, after, ActionOrigin.AUTOMATION, false, phone.wall))
            return id
        }

        override suspend fun forget(id: Long) {
            rows.removeAll { it.id == id }
        }
    }

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60_000L
        const val DAY = 86_400_000L
        const val BALANCED = "light_after_inactive_to=180000,inactive_to=900000"

        @Suppress("LongParameterList") // One row of the log.
        fun record(
            id: Long,
            type: ActionType,
            target: String,
            before: String?,
            after: String,
            origin: ActionOrigin,
            reverted: Boolean,
            wall: Long = 1_799_000_000_000L,
        ) = ActionRecord(id, type, target, before, after, wall, if (reverted) wall + 1 else null, origin, null, null, null)
    }
}

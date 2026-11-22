// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.automation

import io.github.akane599.coulomb.core.analytics.DozeProfile
import io.github.akane599.coulomb.core.collect.SnapshotOutcome
import io.github.akane599.coulomb.core.collect.actions.ActionBlock
import io.github.akane599.coulomb.core.collect.actions.ActionPreview
import io.github.akane599.coulomb.core.collect.actions.ActionRecord
import io.github.akane599.coulomb.core.collect.actions.ActionResult
import io.github.akane599.coulomb.core.collect.actions.ActionType
import io.github.akane599.coulomb.core.collect.actions.AutomationCalls
import io.github.akane599.coulomb.core.common.AutomationContract
import io.github.akane599.coulomb.core.common.AutomationContract.Reason
import io.github.akane599.coulomb.core.common.AutomationContract.Result
import io.github.akane599.coulomb.core.datastore.UserSettings
import io.github.akane599.coulomb.core.model.ActionOrigin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One call as the receiver read it. [name] is one of [AutomationCalls]' names. */
internal data class AutomationCall(
    val name: String,
    val chargeTargetPct: Int? = null,
    val dozeProfile: String? = null,
    /** The sender's package where the sender shared it; null otherwise. */
    val caller: String? = null,
)

/** What goes back to the caller as the ordered broadcast's result. */
internal data class AutomationAnswer(
    val code: Int,
    val reason: String = Reason.OK,
    val retryAfterS: Long? = null,
)

/** The phone and the settings, as the engine needs them. */
internal interface AutomationPorts {
    /** SPEC 14.4 "Automation API". */
    suspend fun enabled(): Boolean

    fun nowWallMs(): Long

    /** Time since boot, for the limit on calls in a row: the wall clock can be set. */
    fun elapsedMs(): Long

    /** The screen is on or the phone is on a charger: a snapshot now costs no wake-up (SPEC 7.4). */
    fun awakeOrPlugged(): Boolean

    /**
     * Takes a snapshot with the automation trigger, behind the collector's own debounce, and waits
     * for it a few seconds at most. The caller's broadcast stays open meanwhile, so the process is
     * not frozen under the snapshot, and the provider's figures are new when the answer arrives.
     * Null when the wait ran out first.
     */
    suspend fun snapshot(): SnapshotOutcome?

    suspend fun chargeTarget(): Int

    suspend fun setChargeTarget(pct: Int)
}

/** The Action Center and its log, as the engine needs them. Every read is bounded: none loads the whole log. */
internal interface AutomationActions {
    /** The rows automation wrote after [sinceWallMs], newest first: its own calls and the Doze profiles it applied. */
    suspend fun automationSince(sinceWallMs: Long): List<ActionRecord>

    /** The Doze profile Coulomb applied that is still in force, if there is one. */
    suspend fun appliedProfile(): ActionRecord?

    /** The Doze profile rows for [profile] that automation did not write, newest first. */
    suspend fun userProfiles(profile: String): List<ActionRecord>

    suspend fun preview(profile: DozeProfile): ActionPreview

    /** Applies [profile] with the origin "automation". */
    suspend fun apply(profile: DozeProfile): ActionResult

    suspend fun revert(id: Long): ActionResult

    /** Writes an [ActionType.AUTOMATION_CALL] row and returns its id. */
    suspend fun log(
        target: String,
        before: String?,
        after: String,
    ): Long

    /** Removes the row of a call that was not carried out after all. */
    suspend fun forget(id: Long)
}

/**
 * SPEC 12.7's three actions (ADR-0065). One call at a time, and at most [BURST] in a minute:
 * a caller in a loop gets "rate limited" before anything is read. A call's row is written before
 * its change and taken back if the change does not happen, so nothing is changed off the record;
 * no text of the caller's is ever stored. A call never does more than the user could do, or has
 * done, in Coulomb itself:
 * - a snapshot is debounced like the user's own refresh, refused while it would wake the phone,
 *   and limited to [SNAPSHOTS] in [SNAPSHOT_WINDOW_MS];
 * - the charge target takes SPEC 14.4's range and nothing outside it;
 * - a Doze profile is applied only as a repeat of a change the user previewed and applied on
 *   this phone, from the same value to the same value; "stock" reverts the applied profile.
 */
internal class AutomationEngine(
    private val ports: AutomationPorts,
    private val actions: AutomationActions,
) {
    private val lock = Mutex()

    /** When the last calls arrived, by [AutomationPorts.elapsedMs]; kept for this process only. */
    private val arrivals = ArrayDeque<Long>()

    suspend fun handle(call: AutomationCall): AutomationAnswer {
        val answer = lock.withLock { decide(call) }
        if (call.name != AutomationCalls.SNAPSHOT_NOW || answer.code != Result.ACCEPTED) return answer
        // Outside the lock: the wait for the snapshot does not hold up the next call. "Accepted"
        // promises new figures, so a failed snapshot or a wait that ran out says so (audit BUG-001).
        // The row stays "accepted": it is the call's place in the limit, not its outcome.
        return when (ports.snapshot()) {
            null -> AutomationAnswer(Result.UNVERIFIED, Reason.TIMEOUT)
            SnapshotOutcome.FAILED -> AutomationAnswer(Result.FAILED, Reason.FAILED)
            else -> answer
        }
    }

    private suspend fun decide(call: AutomationCall): AutomationAnswer {
        val burst = burst()
        return when {
            burst != null -> burst

            // The components are disabled while the setting is off; this is the same answer in code.
            !ports.enabled() -> AutomationAnswer(Result.BLOCKED, Reason.OFF)

            call.name == AutomationCalls.SNAPSHOT_NOW -> snapshot(call)

            call.name == AutomationCalls.SET_CHARGE_TARGET -> chargeTarget(call)

            call.name == AutomationCalls.APPLY_DOZE_PROFILE -> doze(call)

            else -> AutomationAnswer(Result.INVALID, Reason.UNKNOWN_ACTION)
        }
    }

    /** The answer for a call beyond [BURST] in a minute, which is neither carried out nor logged; null for one within it. */
    private fun burst(): AutomationAnswer? {
        val now = ports.elapsedMs()
        while (arrivals.isNotEmpty() && now - arrivals.first() >= BURST_MS) arrivals.removeFirst()
        if (arrivals.size >= BURST) {
            val retry = (arrivals.first() + BURST_MS - now) / MS_PER_S
            return AutomationAnswer(Result.RATE_LIMITED, Reason.RATE_LIMITED, retry.coerceAtLeast(1))
        }
        arrivals.addLast(now)
        return null
    }

    private suspend fun log(): Log {
        val now = ports.nowWallMs()
        return Log(actions.automationSince(now - DAY_MS), now)
    }

    private suspend fun snapshot(call: AutomationCall): AutomationAnswer {
        val log = log()
        val taken =
            log.calls.filter {
                AutomationCalls.name(it.target) == AutomationCalls.SNAPSHOT_NOW &&
                    it.after == AutomationCalls.ACCEPTED &&
                    it.executedWallMs > log.now - SNAPSHOT_WINDOW_MS
            }
        return when {
            !ports.awakeOrPlugged() -> {
                refuse(call, log, null, AutomationAnswer(Result.BLOCKED, Reason.SCREEN_OFF))
            }

            taken.size >= SNAPSHOTS -> {
                val retry = (taken.minOf { it.executedWallMs } + SNAPSHOT_WINDOW_MS - log.now) / MS_PER_S
                refuse(call, log, null, AutomationAnswer(Result.RATE_LIMITED, Reason.RATE_LIMITED, retry.coerceAtLeast(1)))
            }

            log.full -> {
                log.limited
            }

            else -> {
                // The row first: the limit counts rows. [handle] takes the snapshot once the lock is free.
                actions.log(target(call), null, AutomationCalls.ACCEPTED)
                AutomationAnswer(Result.ACCEPTED)
            }
        }
    }

    private suspend fun chargeTarget(call: AutomationCall): AutomationAnswer {
        val pct = call.chargeTargetPct?.takeIf { it in UserSettings.CHARGE_TARGETS }
        val before = ports.chargeTarget()
        // Already so: answered before the log is read.
        if (pct == before) return AutomationAnswer(Result.UNCHANGED)
        val log = log()
        return when {
            // Refused whole: a value outside the range is never brought into it.
            pct == null -> {
                refuse(call, log, call.chargeTargetPct?.toString(), AutomationAnswer(Result.INVALID, Reason.BAD_TARGET))
            }

            log.full -> {
                log.limited
            }

            else -> {
                recorded(actions.log(target(call), before.toString(), pct.toString()), { true }) {
                    ports.setChargeTarget(pct)
                    AutomationAnswer(Result.DONE)
                }
            }
        }
    }

    private suspend fun doze(call: AutomationCall): AutomationAnswer {
        // Text that is not one of the contract's words is refused and not stored.
        val name =
            call.dozeProfile?.takeIf { it in AutomationContract.DOZE_PROFILES }
                ?: return refuse(call, log(), null, AutomationAnswer(Result.INVALID, Reason.BAD_PROFILE))
        val profile = PROFILES[name]
        val applied = actions.appliedProfile()
        return when {
            applied?.target == name -> {
                AutomationAnswer(Result.UNCHANGED)
            }

            // "stock": the applied profile is reverted, as its row's own button would; with none applied it is already so.
            profile == null -> {
                if (applied == null) {
                    AutomationAnswer(Result.UNCHANGED)
                } else {
                    change(call, log(), applied.target, AutomationCalls.STOCK) { actions.revert(applied.id) }
                }
            }

            // One profile over another is two changes; the caller returns to stock first, as the screen does.
            applied != null -> {
                refuse(call, log(), name, AutomationAnswer(Result.BLOCKED, Reason.PROFILE_ACTIVE))
            }

            else -> {
                apply(call, log(), profile, name)
            }
        }
    }

    private suspend fun apply(
        call: AutomationCall,
        log: Log,
        profile: DozeProfile,
        name: String,
    ): AutomationAnswer {
        val preview = actions.preview(profile)
        val block = preview.block
        return when {
            block != null -> {
                refuse(call, log, name, AutomationAnswer(Result.BLOCKED, reason(block)))
            }

            !consented(name, preview) -> {
                refuse(call, log, name, AutomationAnswer(Result.BLOCKED, Reason.NEEDS_FIRST_APPLY))
            }

            // The Action Center writes the change's own row, with the origin; the call's row says who asked.
            else -> {
                change(call, log, AutomationCalls.STOCK, name) { actions.apply(profile) }
            }
        }
    }

    /**
     * The user's own apply of this profile, from the value the phone holds now to the value it
     * would get now, in a change nothing else has altered since: SPEC 13.1 never silently
     * re-applies a value that was changed outside Coulomb.
     */
    private suspend fun consented(
        name: String,
        preview: ActionPreview,
    ): Boolean =
        actions.userProfiles(name).any {
            it.type == ActionType.DOZE_PROFILE &&
                it.origin != ActionOrigin.AUTOMATION &&
                it.target == name &&
                it.driftWallMs == null &&
                it.before.orEmpty() == preview.before.orEmpty() &&
                it.after == preview.after
        }

    /**
     * A change through the Action Center. The call's own row, from [before] to [after], says who
     * asked; it stays only if the change happened or may have.
     */
    private suspend fun change(
        call: AutomationCall,
        log: Log,
        before: String?,
        after: String,
        run: suspend () -> ActionResult,
    ): AutomationAnswer {
        if (log.full) return log.limited
        val row = actions.log(target(call), before, after)
        val answer = recorded(row, { it.code in CHANGED }) { answer(run()) }
        return if (answer.code in CHANGED || answer.code == Result.UNCHANGED) answer else refuse(call, log, after, answer)
    }

    /** Runs [block] for the call whose row is [row]; the row is taken back when the block throws or [kept] says no. */
    private suspend fun <T> recorded(
        row: Long,
        kept: (T) -> Boolean,
        block: suspend () -> T,
    ): T {
        var keep = false
        try {
            return block().also { keep = kept(it) }
        } finally {
            if (!keep) withContext(NonCancellable) { actions.forget(row) }
        }
    }

    /** The Action Center's reason as a word; its own switch being off is told apart from this API's. */
    private fun reason(block: ActionBlock): String = if (block == ActionBlock.OFF) Reason.ACTION_CENTER_OFF else block.name.lowercase()

    private fun answer(result: ActionResult): AutomationAnswer =
        when (result) {
            is ActionResult.Done -> AutomationAnswer(Result.DONE)

            is ActionResult.Unchanged -> AutomationAnswer(Result.UNCHANGED)

            // The log could not be written, so nothing ran: version 1 has "failed" for that (audit BUG-059).
            is ActionResult.Blocked if result.reason == ActionBlock.NO_LOG -> AutomationAnswer(Result.FAILED, Reason.FAILED)

            is ActionResult.Blocked -> AutomationAnswer(Result.BLOCKED, reason(result.reason))

            is ActionResult.Unverified -> AutomationAnswer(Result.UNVERIFIED, Reason.UNVERIFIED)

            is ActionResult.Drifted -> AutomationAnswer(Result.BLOCKED, Reason.DRIFTED)

            is ActionResult.Failed -> AutomationAnswer(Result.FAILED, Reason.FAILED)
        }

    /**
     * Writes a refusal, unless the call before it was the same one refused the same way within
     * the hour, or the day's rows are used up: a caller in a loop must not fill the log.
     * [argument] is one of the contract's words or a number, never the caller's own text.
     */
    private suspend fun refuse(
        call: AutomationCall,
        log: Log,
        argument: String?,
        answer: AutomationAnswer,
    ): AutomationAnswer {
        val target = target(call)
        val after = AutomationCalls.REFUSED + answer.reason
        val last = log.calls.firstOrNull()
        val repeated = last != null && last.target == target && last.after == after && last.executedWallMs > log.now - REPEAT_MS
        if (!repeated && !log.full) actions.log(target, argument, after)
        return answer
    }

    private fun target(call: AutomationCall): String = AutomationCalls.target(call.name, call.caller)

    /** The rows automation wrote in the 24 hours before [now], newest first. */
    private class Log(
        private val rows: List<ActionRecord>,
        val now: Long,
    ) {
        /** This API's own rows, newest first. */
        val calls = rows.filter { it.type == ActionType.AUTOMATION_CALL }

        /** The day's rows are used up: nothing that would need a row is done. */
        val full = rows.size >= DAILY_ROWS

        val limited: AutomationAnswer
            get() {
                val retry = (rows.minOf { it.executedWallMs } + DAY_MS - now) / MS_PER_S
                return AutomationAnswer(Result.RATE_LIMITED, Reason.RATE_LIMITED, retry.coerceAtLeast(1))
            }
    }

    companion object {
        /** Four snapshots in two hours: 48 a day at most, each also behind the collector's own debounce. */
        const val SNAPSHOTS = 4
        const val SNAPSHOT_WINDOW_MS = 2 * 3_600_000L

        /** The rows automation may add to the log in any 24 hours. */
        const val DAILY_ROWS = 100

        /** The calls taken in any minute, whatever they ask. */
        const val BURST = 10
        private const val BURST_MS = 60_000L
        private const val REPEAT_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        private const val MS_PER_S = 1_000L

        /** The answers after which the phone holds, or may hold, the new value. */
        private val CHANGED = setOf(Result.DONE, Result.UNVERIFIED)
        private val PROFILES =
            mapOf(AutomationContract.DOZE_BALANCED to DozeProfile.BALANCED, AutomationContract.DOZE_AGGRESSIVE to DozeProfile.AGGRESSIVE)
    }
}

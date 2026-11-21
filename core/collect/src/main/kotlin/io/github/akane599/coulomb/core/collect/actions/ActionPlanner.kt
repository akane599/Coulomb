// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import io.github.akane599.coulomb.core.analytics.DozeChange
import io.github.akane599.coulomb.core.analytics.DozePlan
import io.github.akane599.coulomb.core.analytics.DozeProfile
import io.github.akane599.coulomb.core.analytics.DozeProfiles
import io.github.akane599.coulomb.core.database.entity.ActionLogEntity
import io.github.akane599.coulomb.core.model.cmd.AppOpMode
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket
import io.github.akane599.coulomb.core.privileged.ActionCommands
import io.github.akane599.coulomb.core.privileged.PrivilegedAction

/** One action worked out against the phone as it is now: what to write, how to check it and how to undo it. */
@Suppress("LongParameterList") // One action's facts, each named at its call.
internal class Plan(
    val type: ActionType,
    val target: String,
    val risk: ActionRisk,
    /** The value now, in the log's form; null when unset. */
    val before: String?,
    val want: String,
    /** Null where nothing is written (a takeover), or where a block came before the command was known. */
    val write: PrivilegedAction?,
    val undo: PrivilegedAction?,
    val block: ActionBlock?,
    /** The value now, in the log's form, for the read-back. */
    val read: suspend () -> Got<String?> = { Got.Unreadable },
    val doze: DozePlan? = null,
) {
    val dozeChanges: List<DozeChange> get() = doze?.changes.orEmpty()

    /** The same plan, refused for [block]. */
    fun blockedFor(block: ActionBlock): Plan = Plan(type, target, risk, before, want, write, undo, block, read, doze)
}

/** How a stored row's value is read again and put back. */
internal class Handle(
    val read: suspend () -> Got<String?>,
    /** The command that restores the row's `beforeValue`; null when there is none. */
    val undo: PrivilegedAction?,
)

/** Who holds the Doze setting, from the value on the phone and the log's active rows. */
internal class DozeHold(
    val owner: DozeOwner,
    val value: String?,
    val profileRow: ActionLogEntity?,
    val takeoverRow: ActionLogEntity?,
)

/** A log value and a value read are the same; unset and empty are both "nothing set". */
internal fun same(
    a: String?,
    b: String?,
): Boolean = a.orEmpty() == b.orEmpty()

/**
 * Turns a request into a [Plan] by reading the phone (SPEC 13.1 "read the current value first"),
 * and a stored row into its [Handle]. It writes nothing.
 */
internal class ActionPlanner(
    private val io: ActionIo,
) {
    suspend fun plan(
        request: ActionRequest,
        hold: suspend () -> Got<DozeHold>,
    ): Plan =
        when (request) {
            is ActionRequest.ForceStop -> forceStop(request)
            is ActionRequest.RestrictBackground -> restrictBackground(request)
            is ActionRequest.SetStandbyBucket -> standbyBucket(request)
            is ActionRequest.DozeAllowlist -> dozeAllowlist(request)
            is ActionRequest.ApplyDozeProfile -> dozeProfile(request.profile, hold())
            is ActionRequest.TakeOverDoze -> takeOver(request.name, hold())
            is ActionRequest.BatterySaver -> batterySaver(request.on)
            ActionRequest.ResetBatteryStats -> resetStats()
        }

    /**
     * [request] as it cannot run. Without a worker the command is still given where it does not
     * depend on a read, so it can be copied and run over adb (SPEC 4.4).
     */
    fun blocked(
        request: ActionRequest,
        block: ActionBlock,
    ): Plan =
        Plan(
            request.type,
            target(request),
            risk(request.type),
            before = null,
            want = "",
            write = if (block == ActionBlock.NO_WORKER) fixedWrite(request) else null,
            undo = if (block == ActionBlock.NO_WORKER) fixedUndo(request) else null,
            block = block,
        )

    /**
     * The inverse of [fixedWrite] where it is the same whatever the value was: it puts back the
     * state the engine itself would have required before acting. A bucket's depends on the bucket before.
     */
    private fun fixedUndo(request: ActionRequest): PrivilegedAction? =
        when (request) {
            is ActionRequest.RestrictBackground -> PrivilegedAction.SetRunInBackground(request.packageName, allowed = true)
            is ActionRequest.DozeAllowlist -> PrivilegedAction.SetDozeAllowlisted(request.packageName, !request.listed)
            is ActionRequest.BatterySaver -> PrivilegedAction.SetBatterySaver(!request.on)
            else -> null
        }

    /** The command of a request whose arguments do not come from the phone's current value. */
    private fun fixedWrite(request: ActionRequest): PrivilegedAction? =
        when (request) {
            is ActionRequest.ForceStop -> PrivilegedAction.ForceStop(request.packageName)
            is ActionRequest.RestrictBackground -> PrivilegedAction.SetRunInBackground(request.packageName, allowed = false)
            is ActionRequest.SetStandbyBucket -> PrivilegedAction.SetStandbyBucket(request.packageName, request.bucket)
            is ActionRequest.DozeAllowlist -> PrivilegedAction.SetDozeAllowlisted(request.packageName, request.listed)
            is ActionRequest.BatterySaver -> PrivilegedAction.SetBatterySaver(request.on)
            ActionRequest.ResetBatteryStats -> PrivilegedAction.ResetBatteryStats
            is ActionRequest.ApplyDozeProfile, is ActionRequest.TakeOverDoze -> null
        }

    private fun target(request: ActionRequest): String =
        when (request) {
            is ActionRequest.ForceStop -> request.packageName
            is ActionRequest.RestrictBackground -> request.packageName
            is ActionRequest.SetStandbyBucket -> request.packageName
            is ActionRequest.DozeAllowlist -> request.packageName
            is ActionRequest.ApplyDozeProfile -> request.profile.name.lowercase()
            is ActionRequest.TakeOverDoze -> request.name
            is ActionRequest.BatterySaver, ActionRequest.ResetBatteryStats -> DEVICE
        }

    private fun risk(type: ActionType): ActionRisk =
        when (type) {
            ActionType.BATTERY_SAVER, ActionType.DOZE_TAKEOVER -> ActionRisk.LOW
            ActionType.RESET_STATS -> ActionRisk.HIGH
            else -> ActionRisk.MEDIUM
        }

    /** For an action that reads nothing first: without a worker it could only fail after the user confirmed it. */
    private fun noWorker(): ActionBlock? = if (io.canAct) null else ActionBlock.NO_WORKER

    private fun <T> Got<T>.block(): ActionBlock? =
        when (this) {
            is Got.Value -> null
            Got.NoWorker -> ActionBlock.NO_WORKER
            Got.Unreadable -> ActionBlock.UNREADABLE
        }

    private fun forceStop(request: ActionRequest.ForceStop): Plan =
        Plan(
            ActionType.FORCE_STOP,
            request.packageName,
            risk(ActionType.FORCE_STOP),
            before = null,
            want = ActionValues.STOPPED,
            write = PrivilegedAction.ForceStop(request.packageName),
            undo = null,
            block = io.appBlock(request.packageName) ?: noWorker(),
        )

    private suspend fun restrictBackground(request: ActionRequest.RestrictBackground): Plan {
        val name = request.packageName
        io.appBlock(name)?.let { return blocked(request, it) }
        val got = io.runInBackground(name)
        val reading = (got as? Got.Value)?.value ?: return blocked(request, got.block() ?: ActionBlock.UNREADABLE)
        // The revert writes "allow", so only a plain "allow" may be changed (ADR-0060 §9).
        val block =
            when {
                reading.uidMode == null && reading.effective == AppOpMode.IGNORE -> ActionBlock.ALREADY
                reading.uidMode != null || reading.effective != AppOpMode.ALLOW -> ActionBlock.NOT_PLAIN_ALLOW
                else -> null
            }
        return Plan(
            ActionType.RESTRICT_BACKGROUND,
            name,
            risk(ActionType.RESTRICT_BACKGROUND),
            before = reading.effective?.cliName,
            want = AppOpMode.IGNORE.cliName,
            write = PrivilegedAction.SetRunInBackground(name, allowed = false),
            undo = PrivilegedAction.SetRunInBackground(name, allowed = true),
            block = block,
            read = { readBackground(name) },
        )
    }

    private suspend fun readBackground(name: String): Got<String?> =
        when (val got = io.runInBackground(name)) {
            is Got.Value -> got.value.effective?.let { Got.Value(it.cliName) } ?: Got.Unreadable
            Got.NoWorker -> Got.NoWorker
            Got.Unreadable -> Got.Unreadable
        }

    private suspend fun standbyBucket(request: ActionRequest.SetStandbyBucket): Plan {
        val name = request.packageName
        io.appBlock(name)?.let { return blocked(request, it) }
        val got = io.bucket(name)
        val now = (got as? Got.Value)?.value ?: return blocked(request, got.block() ?: ActionBlock.UNREADABLE)
        val block =
            when {
                request.bucket != StandbyBucket.RARE && request.bucket != StandbyBucket.RESTRICTED -> ActionBlock.BUCKET_FIXED

                now == request.bucket -> ActionBlock.ALREADY

                // Exempted and Never are Android's to give, and an app further out than asked is not pulled back in.
                now !in PrivilegedAction.BUCKETS || now.value > request.bucket.value -> ActionBlock.BUCKET_FIXED

                else -> null
            }
        return Plan(
            ActionType.STANDBY_BUCKET,
            name,
            risk(ActionType.STANDBY_BUCKET),
            before = now.cliName,
            want = request.bucket.cliName,
            write = PrivilegedAction.SetStandbyBucket(name, request.bucket),
            undo = now.takeIf { it in PrivilegedAction.BUCKETS }?.let { PrivilegedAction.SetStandbyBucket(name, it) },
            block = block,
            read = { readBucket(name) },
        )
    }

    private suspend fun readBucket(name: String): Got<String?> =
        when (val got = io.bucket(name)) {
            is Got.Value -> Got.Value(got.value.cliName)
            Got.NoWorker -> Got.NoWorker
            Got.Unreadable -> Got.Unreadable
        }

    private suspend fun dozeAllowlist(request: ActionRequest.DozeAllowlist): Plan {
        val name = request.packageName
        io.appBlock(name)?.let { return blocked(request, it) }
        val got = io.allowlist()
        val lists = (got as? Got.Value)?.value ?: return blocked(request, got.block() ?: ActionBlock.UNREADABLE)
        val user = name in lists.user
        val system = name in lists.system || name in lists.systemExceptIdle
        val block =
            when {
                user == request.listed -> if (system && !user) ActionBlock.SYSTEM_LISTED else ActionBlock.ALREADY

                // The system already exempts it; a second entry would change nothing.
                request.listed && system -> ActionBlock.SYSTEM_LISTED

                else -> null
            }
        return Plan(
            ActionType.DOZE_ALLOWLIST,
            name,
            risk(ActionType.DOZE_ALLOWLIST),
            before = ActionValues.listed(user),
            want = ActionValues.listed(request.listed),
            write = PrivilegedAction.SetDozeAllowlisted(name, request.listed),
            undo = PrivilegedAction.SetDozeAllowlisted(name, user),
            block = block,
            read = { readListed(name) },
        )
    }

    private suspend fun readListed(name: String): Got<String?> =
        when (val got = io.allowlist()) {
            is Got.Value -> Got.Value(ActionValues.listed(name in got.value.user))
            Got.NoWorker -> Got.NoWorker
            Got.Unreadable -> Got.Unreadable
        }

    private suspend fun dozeProfile(
        profile: DozeProfile,
        got: Got<DozeHold>,
    ): Plan {
        val request = ActionRequest.ApplyDozeProfile(profile)
        val hold = (got as? Got.Value)?.value
        val held =
            when (hold?.owner) {
                null -> got.block() ?: ActionBlock.UNREADABLE
                DozeOwner.EXTERNAL -> ActionBlock.MANAGED_EXTERNALLY
                DozeOwner.PROFILE -> ActionBlock.PROFILE_ACTIVE
                DozeOwner.NOBODY, DozeOwner.BASELINE -> null
            }
        if (hold == null || held != null) return blocked(request, held ?: ActionBlock.UNREADABLE)
        val effective = io.dozeEffectiveMs()
        val values = (effective as? Got.Value)?.value ?: return blocked(request, effective.block() ?: ActionBlock.UNREADABLE)
        return profileOver(request, hold.value, values)
    }

    /** [request]'s profile over [baseline], on a phone whose values with that baseline are [values]. */
    private fun profileOver(
        request: ActionRequest.ApplyDozeProfile,
        baseline: String?,
        values: Map<String, Long>,
    ): Plan {
        // The baseline must be restorable and the new value writable, or nothing is written (ADR-0060 §6).
        val plan =
            DozeProfiles
                .plan(request.profile, baseline, values)
                ?.takeIf { ActionCommands.isDozeConstants(it.value) && ActionCommands.isDozeConstants(baseline.orEmpty()) }
                ?: return blocked(request, ActionBlock.MALFORMED)
        return Plan(
            ActionType.DOZE_PROFILE,
            request.profile.name.lowercase(),
            risk(ActionType.DOZE_PROFILE),
            before = baseline,
            want = plan.value,
            write = PrivilegedAction.PutDozeConstants(plan.value),
            // An empty value and an unset key are the same to the system; deleting needs no empty argument.
            undo = PrivilegedAction.PutDozeConstants(baseline?.ifEmpty { null }),
            block = if (plan.changes.isEmpty()) ActionBlock.NOTHING_TO_CHANGE else null,
            read = io::dozeConstants,
            doze = plan,
        )
    }

    private fun takeOver(
        name: String,
        got: Got<DozeHold>,
    ): Plan {
        val request = ActionRequest.TakeOverDoze(name)
        val hold = (got as? Got.Value)?.value ?: return blocked(request, got.block() ?: ActionBlock.UNREADABLE)
        val value = hold.value
        val block =
            when {
                hold.owner != DozeOwner.EXTERNAL || value == null -> ActionBlock.NOTHING_TO_TAKE_OVER
                !ActionCommands.isDozeConstants(value) || DozeProfiles.pairs(value) == null -> ActionBlock.MALFORMED
                else -> null
            }
        return Plan(
            ActionType.DOZE_TAKEOVER,
            name.trim().take(MAX_NAME).ifEmpty { IMPORTED },
            risk(ActionType.DOZE_TAKEOVER),
            before = value,
            want = value.orEmpty(),
            write = null,
            undo = null,
            block = block,
            read = io::dozeConstants,
        )
    }

    private fun batterySaver(on: Boolean): Plan {
        val now = io.batterySaver()
        val block =
            when {
                io.charging() -> ActionBlock.CHARGING
                now == on -> ActionBlock.ALREADY
                else -> noWorker()
            }
        return Plan(
            ActionType.BATTERY_SAVER,
            DEVICE,
            risk(ActionType.BATTERY_SAVER),
            before = ActionValues.on(now),
            want = ActionValues.on(on),
            write = PrivilegedAction.SetBatterySaver(on),
            undo = PrivilegedAction.SetBatterySaver(now),
            block = block,
            read = { Got.Value(ActionValues.on(io.batterySaver())) },
        )
    }

    private fun resetStats(): Plan =
        Plan(
            ActionType.RESET_STATS,
            DEVICE,
            risk(ActionType.RESET_STATS),
            before = null,
            want = ActionValues.RESET,
            write = PrivilegedAction.ResetBatteryStats,
            undo = null,
            block = noWorker(),
        )

    /** How [row]'s value is read again and put back; null for a row with no state, or one this build cannot read. */
    fun handle(row: ActionLogEntity): Handle? {
        val before = row.beforeValue
        return when (ActionType.of(row.actionType)) {
            ActionType.RESTRICT_BACKGROUND -> {
                Handle({ readBackground(row.target) }, PrivilegedAction.SetRunInBackground(row.target, allowed = true))
            }

            ActionType.STANDBY_BUCKET -> {
                val bucket = StandbyBucket.entries.firstOrNull { it.cliName == before && it in PrivilegedAction.BUCKETS }
                Handle({ readBucket(row.target) }, bucket?.let { PrivilegedAction.SetStandbyBucket(row.target, it) })
            }

            ActionType.DOZE_ALLOWLIST -> {
                Handle({ readListed(row.target) }, PrivilegedAction.SetDozeAllowlisted(row.target, before == ActionValues.LISTED))
            }

            ActionType.DOZE_PROFILE -> {
                val restorable = before == null || ActionCommands.isDozeConstants(before)
                Handle(io::dozeConstants, if (restorable) PrivilegedAction.PutDozeConstants(before?.ifEmpty { null }) else null)
            }

            ActionType.DOZE_TAKEOVER -> {
                Handle(io::dozeConstants, undo = null)
            }

            ActionType.BATTERY_SAVER -> {
                Handle({ Got.Value(ActionValues.on(io.batterySaver())) }, PrivilegedAction.SetBatterySaver(before == ActionValues.ON))
            }

            ActionType.FORCE_STOP, ActionType.RESET_STATS, ActionType.AUTOMATION_CALL, null -> {
                null
            }
        }
    }

    companion object {
        /** The log's target for an action on the phone itself. */
        const val DEVICE = "device"
        const val IMPORTED = "imported"
        const val MAX_NAME = 40
    }
}

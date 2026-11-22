// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import io.github.akane599.coulomb.core.analytics.DozeProfile
import io.github.akane599.coulomb.core.analytics.DozeProfiles
import io.github.akane599.coulomb.core.database.entity.ActionLogEntity
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.ActionOrigin
import io.github.akane599.coulomb.core.privileged.ActionCommands
import io.github.akane599.coulomb.core.privileged.PrivilegedAction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Action Center's engine (SPEC 13; ADR-0060). Every action is previewed from the phone's
 * current value, written through one typed worker call, read back, and logged with the value it
 * replaced. A row is written before its command and removed when the command changed nothing, so
 * no change exists without its row. Nothing here runs unless a caller asks; the collector's own
 * code never does.
 */
public class ActionCenter internal constructor(
    private val io: ActionIo,
    private val log: ActionLog,
    private val gate: ActionGate,
    private val nowWallMs: () -> Long,
) {
    private val planner = ActionPlanner(io)

    /** One action at a time: a screen and an automation call must not interleave their reads and writes. */
    private val lock = Mutex()

    /** The id of the last row a drift pass read; the next pass goes on from the row after it. */
    private var driftCursor = Long.MAX_VALUE

    /** The log, newest first. */
    public val records: Flow<List<ActionRecord>> = log.observe().map { rows -> rows.map(::record) }

    /** [request] against the phone as it is now. Reads; writes nothing. */
    public suspend fun preview(request: ActionRequest): ActionPreview = lock.withLock { plan(request).preview(request) }

    /**
     * [requests] against the phone as it is now, with each of the phone's values read once, and
     * the Doze setting [withDoze]. While the Action Center is off it reads nothing.
     */
    public suspend fun board(
        requests: List<ActionRequest>,
        withDoze: Boolean = false,
    ): ActionBoard =
        lock.withLock {
            val once = OnceIo(io)
            val on = gate.enabled()
            val planner = ActionPlanner(once)
            val active = if (on) activeRows() else emptyList()
            val previews =
                requests.map { request ->
                    val plan = if (on) planner.plan(request) { hold(once) }.over(active) else planner.blocked(request, ActionBlock.OFF)
                    plan.preview(request)
                }
            ActionBoard(previews, if (on && withDoze) dozeState(once) else null)
        }

    /** Runs [request] if nothing blocks it. [insightId] is the insight whose card offered it. */
    public suspend fun run(
        request: ActionRequest,
        origin: ActionOrigin = ActionOrigin.USER,
        insightId: Long? = null,
    ): ActionResult =
        lock.withLock {
            val plan = plan(request)
            val block = plan.block
            val prior = prior(plan, activeRows())
            when {
                block != null -> {
                    ActionResult.Blocked(block)
                }

                prior == null -> {
                    execute(plan, origin, insightId)
                }

                // The earlier change is no longer what the phone holds: that is drift, and this is a new change.
                !same(plan.before, prior.afterValue) -> {
                    drifted(prior, plan.before)
                    execute(plan, origin, insightId)
                }

                // What is asked is the earlier change undone ([over] refuses anything else): one row, reverted.
                else -> {
                    revert(prior)
                }
            }
        }

    /**
     * Puts back what row [id] replaced. Allowed while the Action Center is off: undoing is never
     * refused. A value that is no longer the one Coulomb left is not overwritten (SPEC 13.1).
     */
    public suspend fun revert(id: Long): ActionResult =
        lock.withLock {
            val row = log.row(id)?.takeIf { record(it).active } ?: return@withLock ActionResult.Blocked(ActionBlock.NOT_ACTIVE)
            revert(row)
        }

    /** SPEC 13.1 "Revert all": every active row, newest first. */
    public suspend fun revertAll(): List<ActionResult> =
        lock.withLock {
            val results = ArrayList<ActionResult>()
            // Read again before each: reverting a profile is what frees the baseline under it.
            for (id in log.unreverted().filter { record(it).active }.map { it.id }) {
                log.row(id)?.takeIf { record(it).active }?.let { results += revert(it) }
            }
            results
        }

    /**
     * SPEC 13.1 drift detection, after a heavy snapshot: re-reads the target of each active
     * watched row and records a value that is no longer Coulomb's. A read that fails says
     * nothing. With no such row it asks the phone nothing.
     */
    public suspend fun checkDrift() {
        lock.withLock {
            val rows = log.unreverted().filter { record(it).run { active && type?.watched == true } }
            val profile = rows.firstOrNull { it.actionType == ActionType.DOZE_PROFILE.id }
            // One read serves every row about the same value, such as the allowlist.
            val once = ActionPlanner(OnceIo(io))
            // A baseline under an applied profile is not what the setting holds; the profile's row is checked.
            driftBatch(rows.filterNot { it.actionType == ActionType.DOZE_TAKEOVER.id && profile != null }).forEach { row ->
                // Only a value that was read can differ: a read that failed says nothing (ADR-0060 §22).
                val read = once.handle(row)?.read?.invoke()
                if (read is Got.Value && !same(read.value, row.afterValue)) drifted(row, read.value)
            }
        }
    }

    /**
     * The rows one pass reads: at most [MAX_DRIFT_READS], starting after the last one the pass
     * before read, so with more rows than that every row still gets its turn.
     */
    private fun driftBatch(rows: List<ActionLogEntity>): List<ActionLogEntity> {
        val start = rows.indexOfFirst { it.id < driftCursor }.coerceAtLeast(0)
        val batch = (rows.drop(start) + rows.take(start)).take(MAX_DRIFT_READS)
        driftCursor = if (batch.size < rows.size) batch.last().id else Long.MAX_VALUE
        return batch
    }

    /** Who holds the Doze setting and how soon the phone idles; null when it cannot be read. */
    public suspend fun doze(): DozeState? = lock.withLock { dozeState(io) }

    private suspend fun dozeState(io: ActionIo): DozeState? {
        val hold = (hold(io) as? Got.Value)?.value ?: return null
        val effective = (io.dozeEffectiveMs() as? Got.Value)?.value
        return DozeState(
            owner = hold.owner,
            value = hold.value,
            profile = hold.profileRow?.takeIf { hold.owner == DozeOwner.PROFILE }?.let { row -> PROFILES[row.target] },
            importedName = hold.takeoverRow?.target,
            idleAfterMs = effective?.let(DozeProfiles::idleAfterMs),
        )
    }

    private suspend fun plan(request: ActionRequest): Plan =
        if (gate.enabled()) planner.plan(request) { hold(io) }.over(activeRows()) else planner.blocked(request, ActionBlock.OFF)

    private suspend fun activeRows(): List<ActionLogEntity> = log.unreverted().filter { record(it).active }

    /** The change of the same kind on the same target that is still in force, for the kinds one row describes. */
    private fun prior(
        plan: Plan,
        active: List<ActionLogEntity>,
    ): ActionLogEntity? = active.firstOrNull { plan.type in ONE_ROW && it.actionType == plan.type.id && it.target == plan.target }

    /**
     * A second change over one still in force is refused unless it undoes that one: two rows about
     * one value could not both be put back, and the older would read as changed by something else.
     */
    private fun Plan.over(active: List<ActionLogEntity>): Plan {
        val prior = prior(this, active) ?: return this
        val stacks = block == null && same(before, prior.afterValue) && !same(want, prior.beforeValue)
        return if (stacks) blockedFor(ActionBlock.IN_FORCE) else this
    }

    private fun Plan.preview(request: ActionRequest): ActionPreview =
        ActionPreview(
            request = request,
            risk = risk,
            command = write?.let(io::commandLine),
            revertCommand = undo?.let(io::commandLine),
            before = before,
            after = want.takeIf { block != ActionBlock.OFF },
            block = block,
            dozeChanges = dozeChanges,
        )

    private suspend fun hold(io: ActionIo): Got<DozeHold> {
        val value =
            when (val got = io.dozeConstants()) {
                is Got.Value -> got.value
                Got.NoWorker -> return Got.NoWorker
                Got.Unreadable -> return Got.Unreadable
            }
        val active = log.unreverted().filter { record(it).active }
        val profile = active.firstOrNull { it.actionType == ActionType.DOZE_PROFILE.id }
        val takeover = active.firstOrNull { it.actionType == ActionType.DOZE_TAKEOVER.id }
        val owner =
            when {
                profile != null && same(value, profile.afterValue) -> DozeOwner.PROFILE
                takeover != null && same(value, takeover.afterValue) -> DozeOwner.BASELINE
                value.isNullOrEmpty() -> DozeOwner.NOBODY
                else -> DozeOwner.EXTERNAL
            }
        return Got.Value(DozeHold(owner, value, profile, takeover))
    }

    /**
     * The row, the command and the read-back, as one piece that leaving the screen cannot cut: a
     * write without its read-back would leave a row that says what was asked, not what happened.
     */
    private suspend fun execute(
        plan: Plan,
        origin: ActionOrigin,
        insightId: Long?,
    ): ActionResult =
        withContext(NonCancellable) {
            val row =
                ActionLogEntity(
                    actionType = plan.type.id,
                    target = plan.target,
                    beforeValue = plan.before,
                    afterValue = plan.want,
                    // Every action runs through the worker (SPEC 13.1).
                    tier = AccessTier.T2_PRIVILEGED_WORKER,
                    executedWall = nowWallMs(),
                    revertedWall = null,
                    origin = origin,
                    insightId = insightId,
                    externalDriftWall = null,
                    externalDriftValue = null,
                )
            // The row first: a command whose row was never written could not be found again.
            val stored = row.copy(id = log.insert(row))
            val ran = plan.write?.let { io.act(it) }
            when {
                // Never sent.
                ran?.noWorker == true -> {
                    log.delete(stored)
                    ActionResult.Blocked(ActionBlock.NO_WORKER)
                }

                plan.type == ActionType.RESET_STATS -> {
                    resetDone(stored, ran)
                }

                // Android confirms nothing for a force stop, and there is no value to read.
                !plan.type.stateful -> {
                    if (ran?.ok == false) {
                        log.delete(stored)
                        ActionResult.Failed(ran.output)
                    } else {
                        ActionResult.Done(stored.id, plan.want)
                    }
                }

                // Also after a command that reported a failure: a timeout does not say the change did not land.
                else -> {
                    readBack(plan, stored, failed = ran?.takeIf { !it.ok })
                }
            }
        }

    private suspend fun resetDone(
        row: ActionLogEntity,
        ran: Ran?,
    ): ActionResult {
        if (ran?.output?.contains(ActionCommands.RESET_DONE) == true) return ActionResult.Done(row.id, row.afterValue)
        // A reply lost to a timeout or a dead worker does not say the reset did not run: the
        // record of an irreversible command stays (ADR-0060 §14; audit DATA-019).
        if (ran?.unclear == true) return ActionResult.Unverified(row.id)
        log.delete(row)
        return ActionResult.Failed(ran?.output)
    }

    private suspend fun readBack(
        plan: Plan,
        row: ActionLogEntity,
        failed: Ran?,
    ): ActionResult {
        var read = plan.read()
        // A change that lands a moment after its command must not be taken for no change.
        if (read is Got.Value && same(read.value, plan.before) && !same(plan.before, plan.want)) {
            delay(SETTLE_MS)
            read = plan.read()
        }
        val value = (read as? Got.Value)?.value
        return when {
            read !is Got.Value -> {
                ActionResult.Unverified(row.id)
            }

            same(value, plan.before) && !same(plan.before, plan.want) -> {
                log.delete(row)
                if (failed != null) ActionResult.Failed(failed.output) else ActionResult.Unchanged(value)
            }

            plan.doze != null && !inForce(plan) -> {
                rollBack(plan, row)
            }

            else -> {
                // What the phone holds, which Android may have set short of what was asked.
                if (!same(value, plan.want)) log.update(row.copy(afterValue = value.orEmpty()))
                ActionResult.Done(row.id, value.orEmpty())
            }
        }
    }

    /** ADR-0060 §20: the setting took the string; did the controller take the values? */
    private suspend fun inForce(plan: Plan): Boolean {
        val doze = plan.doze ?: return true
        if (!same((plan.read() as? Got.Value)?.value, plan.want)) return false
        // The controller hears of the setting through an observer, a moment after the write.
        repeat(IN_FORCE_READS) { attempt ->
            if (attempt > 0) delay(SETTLE_MS)
            val effective = (io.dozeEffectiveMs() as? Got.Value)?.value
            if (effective != null && DozeProfiles.inForce(doze, effective)) return true
        }
        return false
    }

    /**
     * A profile that is written but not in force is undone at once. The undo goes only over the
     * value Coulomb wrote, and the row goes only when the baseline is read back: a read that fails
     * is not the baseline, so a change never exists without its row (ADR-0060 §21, §22b; audit
     * DATA-004, CONC-007).
     */
    private suspend fun rollBack(
        plan: Plan,
        row: ActionLogEntity,
    ): ActionResult {
        val held = plan.read()
        return when {
            held !is Got.Value -> ActionResult.Unverified(row.id)

            same(held.value, plan.before) -> forget(row)

            // Another manager wrote the setting in the meantime: its value is not overwritten.
            !same(held.value, plan.want) -> drifted(row, held.value)

            else -> undo(plan, row)
        }
    }

    /** Writes the baseline back over Coulomb's value; the row goes once the baseline reads back. */
    private suspend fun undo(
        plan: Plan,
        row: ActionLogEntity,
    ): ActionResult {
        plan.undo?.let { io.act(it) }
        val now = plan.read()
        return if (now is Got.Value && same(now.value, plan.before)) forget(row) else ActionResult.Unverified(row.id)
    }

    private suspend fun forget(row: ActionLogEntity): ActionResult {
        log.delete(row)
        return ActionResult.Failed(null)
    }

    private suspend fun revert(row: ActionLogEntity): ActionResult {
        val handle = planner.handle(row) ?: return ActionResult.Blocked(ActionBlock.NOT_ACTIVE)
        if (row.actionType == ActionType.DOZE_TAKEOVER.id) return release(row)
        val undo = handle.undo ?: return ActionResult.Blocked(ActionBlock.NOT_ACTIVE)
        return when (val now = handle.read()) {
            Got.NoWorker -> ActionResult.Blocked(ActionBlock.NO_WORKER)

            Got.Unreadable -> ActionResult.Blocked(ActionBlock.UNREADABLE)

            // Not the value Coulomb left: something else changed it, and it is not overwritten.
            is Got.Value -> if (same(now.value, row.afterValue)) undo(row, handle, undo) else drifted(row, now.value)
        }
    }

    private suspend fun undo(
        row: ActionLogEntity,
        handle: Handle,
        undo: PrivilegedAction,
    ): ActionResult =
        withContext(NonCancellable) {
            val ran = io.act(undo)
            val after = if (ran.noWorker) null else handle.read()
            val value = (after as? Got.Value)?.value
            when {
                ran.noWorker -> {
                    ActionResult.Blocked(ActionBlock.NO_WORKER)
                }

                after !is Got.Value -> {
                    ActionResult.Unverified(row.id)
                }

                // Also after a command that reported a failure: what counts is what the phone holds.
                same(value, row.beforeValue) -> {
                    log.update(row.copy(revertedWall = nowWallMs()))
                    ActionResult.Done(row.id, value.orEmpty())
                }

                same(value, row.afterValue) -> {
                    if (ran.ok) ActionResult.Unchanged(value) else ActionResult.Failed(ran.output)
                }

                else -> {
                    drifted(row, value)
                }
            }
        }

    /** Releasing a baseline writes nothing: the value stays, and is "managed externally" again. */
    private suspend fun release(row: ActionLogEntity): ActionResult {
        val profile = log.unreverted().any { it.actionType == ActionType.DOZE_PROFILE.id && record(it).active }
        if (profile) return ActionResult.Blocked(ActionBlock.PROFILE_ACTIVE)
        log.update(row.copy(revertedWall = nowWallMs()))
        return ActionResult.Done(row.id, row.afterValue)
    }

    private suspend fun drifted(
        row: ActionLogEntity,
        now: String?,
    ): ActionResult {
        log.update(row.copy(externalDriftWall = nowWallMs(), externalDriftValue = now.orEmpty()))
        return ActionResult.Drifted(row.id, now)
    }

    private fun record(row: ActionLogEntity): ActionRecord {
        val type = ActionType.of(row.actionType)
        val record =
            ActionRecord(
                id = row.id,
                type = type,
                target = row.target,
                before = row.beforeValue,
                after = row.afterValue,
                executedWallMs = row.executedWall,
                revertedWallMs = row.revertedWall,
                origin = row.origin,
                driftWallMs = row.externalDriftWall,
                driftValue = row.externalDriftValue,
                revertCommand = null,
            )
        return if (record.active) record.copy(revertCommand = planner.handle(row)?.undo?.let(io::commandLine)) else record
    }

    private companion object {
        /** Per-app rows read in one pass; the rest wait for the next heavy snapshot. */
        const val MAX_DRIFT_READS = 24
        const val SETTLE_MS = 300L
        const val IN_FORCE_READS = 3

        /** The kinds where one row describes the value: a second row over the first could not be put back with it. */
        val ONE_ROW =
            setOf(ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET, ActionType.DOZE_ALLOWLIST, ActionType.BATTERY_SAVER)
        val PROFILES = DozeProfile.entries.associateBy { it.name.lowercase() }
    }
}

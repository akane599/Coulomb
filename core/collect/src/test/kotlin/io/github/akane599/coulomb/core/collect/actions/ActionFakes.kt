// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import io.github.akane599.coulomb.core.analytics.DozeProfiles
import io.github.akane599.coulomb.core.database.entity.ActionLogEntity
import io.github.akane599.coulomb.core.model.cmd.AppOpMode
import io.github.akane599.coulomb.core.model.cmd.AppOpReading
import io.github.akane599.coulomb.core.model.cmd.DozeAllowlist
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket
import io.github.akane599.coulomb.core.privileged.ActionCommands
import io.github.akane599.coulomb.core.privileged.PrivilegedAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A phone in memory: commands change its state the way the AOSP sources say the real ones do. */
internal class FakePhone : ActionIo {
    var worker = true

    /** False: reads come back as text Coulomb does not understand. */
    var readable = true

    /** False: commands run and change nothing. */
    var obeys = true

    /** False: commands report a failure, whatever they did. */
    var reports = true
    val installed = mutableSetOf(APP, OTHER, SYSTEM_APP)
    val protectedApps = mutableSetOf(SYSTEM_APP)
    val appOps = mutableMapOf<String, AppOpReading>()
    val buckets = mutableMapOf(APP to StandbyBucket.ACTIVE, OTHER to StandbyBucket.WORKING_SET)

    /** The furthest bucket Android lets an app reach; an exempt app's is its own. */
    val furthest = mutableMapOf<String, StandbyBucket>()
    val userListed = mutableSetOf<String>()
    val systemListed = mutableSetOf<String>()
    var doze: String? = null

    /** The device's own values, before the setting overrides them. */
    var stockMs = SLOW

    /** False: the setting takes a string and the controller's values do not follow. */
    var dozeTakesEffect = true
    var saver = false
    var plugged = false
    var resetOutput = ActionCommands.RESET_DONE

    /** True: the reset's reply is lost, as to a timeout or a dead worker. */
    var resetUnclear = false

    /** True: a force stop's reply is lost the same way. */
    var stopUnclear = false
    val acts = mutableListOf<PrivilegedAction>()
    var reads = 0

    /** Runs after each command; a test changes the phone there, as a worker drop or another manager would. */
    var afterAct: (PrivilegedAction) -> Unit = {}

    /** Runs at each read of the controller's values. */
    var onEffectiveRead: () -> Unit = {}

    private fun <T> got(value: () -> T): Got<T> {
        reads++
        return when {
            !worker -> Got.NoWorker
            !readable -> Got.Unreadable
            else -> Got.Value(value())
        }
    }

    override suspend fun runInBackground(packageName: String): Got<AppOpReading> =
        got { appOps[packageName] ?: AppOpReading(uidMode = null, packageMode = null, defaultMode = AppOpMode.ALLOW) }

    override suspend fun bucket(packageName: String): Got<StandbyBucket> = got { buckets[packageName] ?: StandbyBucket.ACTIVE }

    override suspend fun allowlist(): Got<DozeAllowlist> = got { DozeAllowlist(emptySet(), systemListed.toSet(), userListed.toSet()) }

    override suspend fun dozeConstants(): Got<String?> = got { doze }

    override suspend fun dozeEffectiveMs(): Got<Map<String, Long>> {
        onEffectiveRead()
        return got {
            val overrides = if (dozeTakesEffect) DozeProfiles.pairs(doze).orEmpty() else emptyMap()
            stockMs + overrides.mapNotNull { (key, value) -> value.toLongOrNull()?.let { key to it } }
        }
    }

    override val canAct: Boolean get() = worker

    override fun batterySaver(): Boolean = saver

    override fun charging(): Boolean = plugged

    override fun appBlock(packageName: String): ActionBlock? =
        when (packageName) {
            !in installed -> ActionBlock.NOT_INSTALLED
            in protectedApps -> ActionBlock.PROTECTED
            else -> null
        }

    override suspend fun act(action: PrivilegedAction): Ran {
        if (!worker) return Ran(ok = false, noWorker = true, output = null)
        acts += action
        if (obeys) apply(action)
        afterAct(action)
        val lost = (action == PrivilegedAction.ResetBatteryStats && resetUnclear) || (action is PrivilegedAction.ForceStop && stopUnclear)
        if (lost) {
            return Ran(ok = false, noWorker = false, output = null, unclear = true)
        }
        return Ran(ok = reports, noWorker = false, output = if (action == PrivilegedAction.ResetBatteryStats) resetOutput else "")
    }

    private fun apply(action: PrivilegedAction) {
        when (action) {
            is PrivilegedAction.SetRunInBackground -> {
                val mode = if (action.allowed) AppOpMode.ALLOW else AppOpMode.IGNORE
                appOps[action.packageName] =
                    (appOps[action.packageName] ?: AppOpReading(null, null, null)).copy(packageMode = mode, defaultMode = null)
            }

            is PrivilegedAction.SetStandbyBucket -> {
                val limit = furthest[action.packageName]
                buckets[action.packageName] = if (limit != null && action.bucket.value > limit.value) limit else action.bucket
            }

            is PrivilegedAction.SetDozeAllowlisted -> {
                if (action.listed) userListed += action.packageName else userListed -= action.packageName
            }

            is PrivilegedAction.PutDozeConstants -> {
                doze = action.value
            }

            is PrivilegedAction.SetBatterySaver -> {
                // PowerManagerService refuses in either direction while powered.
                if (!plugged) saver = action.on
            }

            // Neither leaves a value to read.
            is PrivilegedAction.ForceStop, PrivilegedAction.ResetBatteryStats -> {}
        }
    }

    override fun commandLine(action: PrivilegedAction): String = ActionCommands.adbLine(ActionCommands.argv(action, 0))

    companion object {
        const val APP = "com.example.app"
        const val OTHER = "com.example.other"
        const val SYSTEM_APP = "com.example.system"
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE

        /** A phone that waits an hour before deep idle. */
        val SLOW =
            mapOf(
                "light_after_inactive_to" to 4 * MINUTE,
                "inactive_to" to 30 * MINUTE,
                "idle_after_inactive_to" to 30 * MINUTE,
                "motion_inactive_to" to 10 * MINUTE,
                "idle_to" to HOUR,
                "max_idle_to" to 6 * HOUR,
            )
    }
}

internal class FakeLog : ActionLog {
    private val rows = MutableStateFlow<List<ActionLogEntity>>(emptyList())
    private var nextId = 1L

    val all: List<ActionLogEntity> get() = rows.value

    /** Thrown by the next insert or update, as Room throws a full disk or a locked file. */
    var failing: Exception? = null

    private fun fail() {
        failing?.let { throw it }
    }

    override suspend fun insert(row: ActionLogEntity): Long {
        fail()
        val id = nextId++
        rows.value = listOf(row.copy(id = id)) + rows.value
        return id
    }

    override suspend fun update(row: ActionLogEntity) {
        fail()
        rows.value = rows.value.map { if (it.id == row.id) row else it }
    }

    override suspend fun delete(row: ActionLogEntity) {
        rows.value = rows.value.filterNot { it.id == row.id }
    }

    override suspend fun row(id: Long): ActionLogEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun unreverted(): List<ActionLogEntity> = rows.value.filter { it.revertedWall == null }

    override fun observe(limit: Int): Flow<List<ActionLogEntity>> = rows.map { it.take(limit) }

    override fun observeUnreverted(): Flow<List<ActionLogEntity>> = rows.map { all -> all.filter { it.revertedWall == null } }

    override fun observeLatestAction(): Flow<ActionLogEntity?> = rows.map { all -> all.firstOrNull { it.actionType != "automation_call" } }
}

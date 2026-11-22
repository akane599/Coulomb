// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.PowerManager
import io.github.akane599.coulomb.core.database.dao.InsightDao
import io.github.akane599.coulomb.core.database.entity.ActionLogEntity
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.WorkerStatus
import io.github.akane599.coulomb.core.model.cmd.AppOpReading
import io.github.akane599.coulomb.core.model.cmd.DozeAllowlist
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket
import io.github.akane599.coulomb.core.parsing.cmd.CmdOutputs
import io.github.akane599.coulomb.core.parsing.dumpsys.DeviceIdleParser
import io.github.akane599.coulomb.core.privileged.ActionCommands
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.FailureReason
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.privileged.PrivilegedAction
import io.github.akane599.coulomb.core.privileged.PrivilegedQuery
import io.github.akane599.coulomb.core.privileged.PrivilegedResult
import io.github.akane599.coulomb.core.privileged.SettingsNamespace
import io.github.akane599.coulomb.core.privileged.SystemService
import kotlinx.coroutines.flow.Flow

/** The phone as the engine reaches it: reads and writes through the worker, two states through public APIs. */
internal class WorkerActionIo(
    private val context: Context,
    private val access: PrivilegedAccess,
) : ActionIo {
    override suspend fun runInBackground(packageName: String): Got<AppOpReading> =
        query(PrivilegedQuery.RUN_ANY_IN_BACKGROUND, packageName) { CmdOutputs.appOp(it, PrivilegedAction.RUN_ANY_IN_BACKGROUND) }

    override suspend fun bucket(packageName: String): Got<StandbyBucket> =
        query(PrivilegedQuery.STANDBY_BUCKET, packageName, CmdOutputs::standbyBucket)

    override suspend fun allowlist(): Got<DozeAllowlist> = query(PrivilegedQuery.DEVICE_IDLE_WHITELIST, null, CmdOutputs::dozeAllowlist)

    private suspend fun <T : Any> query(
        query: PrivilegedQuery,
        packageName: String?,
        parse: (String) -> T?,
    ): Got<T> {
        val result = access.runQuery(query, packageName, READ_TIMEOUT_MS) { parse(it.readBytes().toString(Charsets.UTF_8)) }
        return when (result) {
            is DumpResult.Complete -> result.value?.let { Got.Value(it) } ?: Got.Unreadable
            is DumpResult.Partial -> Got.Unreadable
            is DumpResult.Unavailable -> if (result.reason == FailureReason.NO_ACCESS) Got.NoWorker else Got.Unreadable
        }
    }

    /** `settings get` prints "null" for an unset key, which the worker returns as null (ADR-0060, context 6). */
    override suspend fun dozeConstants(): Got<String?> =
        when (val result = access.getSetting(SettingsNamespace.GLOBAL, PrivilegedAction.DOZE_CONSTANTS_KEY)) {
            is PrivilegedResult.Success -> Got.Value(result.value)
            is PrivilegedResult.Failure -> if (result.reason == FailureReason.NO_ACCESS) Got.NoWorker else Got.Unreadable
        }

    override suspend fun dozeEffectiveMs(): Got<Map<String, Long>> {
        val result = access.dumpService(SystemService.DEVICE_IDLE, READ_TIMEOUT_MS) { DeviceIdleParser.parse(it) }
        return when (result) {
            is DumpResult.Complete -> {
                // A dump cut short, or one without its settings, is no ground for a profile.
                val settings =
                    result.value
                        .takeIf { it.report.complete }
                        ?.value
                        ?.settings
                settings?.let(CmdOutputs::dozeDurationsMs)?.takeIf { it.isNotEmpty() }?.let { Got.Value(it) } ?: Got.Unreadable
            }

            is DumpResult.Partial -> {
                Got.Unreadable
            }

            is DumpResult.Unavailable -> {
                if (result.reason == FailureReason.NO_ACCESS) Got.NoWorker else Got.Unreadable
            }
        }
    }

    /** The manager runs and lets Coulomb in, and the worker has not given up for this session (SPEC 4.3). */
    override val canAct: Boolean
        get() {
            val state = access.state.value
            val allowed = state.manager == ManagerStatus.RUNNING && state.permission == ManagerPermission.GRANTED
            return allowed && state.worker != WorkerStatus.UNAVAILABLE
        }

    /** The state `cmd power set-mode` changes, by the public API (ADR-0060 §13). */
    override fun batterySaver(): Boolean = context.getSystemService(PowerManager::class.java).isPowerSaveMode

    /** Plugged in, as `PowerManagerService` counts it when it refuses a battery saver change: the sticky broadcast's plug type. */
    override fun charging(): Boolean {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    override fun appBlock(packageName: String): ActionBlock? {
        val uid =
            try {
                context.packageManager.getPackageUid(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return ActionBlock.NOT_INSTALLED
            }
        // Stopping the manager would stop the worker under the action; a system UID's package is not an app to tune.
        val guarded = packageName == context.packageName || packageName == access.managerLaunchIntent()?.`package`
        return if (guarded || uid % PER_USER_RANGE < FIRST_APPLICATION_UID) ActionBlock.PROTECTED else null
    }

    override suspend fun act(action: PrivilegedAction): Ran =
        when (val result = access.act(action)) {
            is PrivilegedResult.Success -> {
                Ran(ok = true, noWorker = false, output = result.value)
            }

            is PrivilegedResult.Failure -> {
                Ran(
                    ok = false,
                    noWorker = result.reason == FailureReason.NO_ACCESS,
                    output = result.detail,
                    unclear = result.reason == FailureReason.TIMEOUT || result.reason == FailureReason.WORKER_DIED,
                )
            }
        }

    override fun commandLine(action: PrivilegedAction): String = ActionCommands.adbLine(access.actionArgv(action))

    private companion object {
        const val READ_TIMEOUT_MS = 5_000L
        const val PER_USER_RANGE = 100_000
        const val FIRST_APPLICATION_UID = 10_000
    }
}

/** The action log in the database. */
internal class DaoActionLog(
    private val dao: InsightDao,
) : ActionLog {
    override suspend fun insert(row: ActionLogEntity): Long = dao.insertAction(row)

    override suspend fun update(row: ActionLogEntity) = dao.updateAction(row)

    override suspend fun delete(row: ActionLogEntity) = dao.deleteAction(row)

    override suspend fun row(id: Long): ActionLogEntity? = dao.action(id)

    override suspend fun unreverted(): List<ActionLogEntity> = dao.unreverted()

    override fun observe(limit: Int): Flow<List<ActionLogEntity>> = dao.observeActions(limit)

    override fun observeUnreverted(): Flow<List<ActionLogEntity>> = dao.observeUnreverted()

    override fun observeLatestAction(): Flow<ActionLogEntity?> = dao.observeLatestAction()
}

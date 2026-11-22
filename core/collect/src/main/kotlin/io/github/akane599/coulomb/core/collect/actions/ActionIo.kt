// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import io.github.akane599.coulomb.core.database.entity.ActionLogEntity
import io.github.akane599.coulomb.core.model.cmd.AppOpReading
import io.github.akane599.coulomb.core.model.cmd.DozeAllowlist
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket
import io.github.akane599.coulomb.core.privileged.PrivilegedAction
import kotlinx.coroutines.flow.Flow

/** A read of the phone: a value, or why there is none. A failed read is never taken for a value. */
internal sealed interface Got<out T> {
    data class Value<T>(
        val value: T,
    ) : Got<T>

    /** No worker to ask. */
    data object NoWorker : Got<Nothing>

    /** Asked, and the answer was not one Coulomb understands. */
    data object Unreadable : Got<Nothing>
}

/** A command's end: [ok] when it ran, with what it printed. */
internal data class Ran(
    val ok: Boolean,
    val noWorker: Boolean,
    val output: String?,
    /** The reply was lost (a timeout, the worker died): the command may have run. */
    val unclear: Boolean = false,
)

/** Everything the engine reads from and writes to the phone, so it can be tested without one. */
internal interface ActionIo {
    suspend fun runInBackground(packageName: String): Got<AppOpReading>

    suspend fun bucket(packageName: String): Got<StandbyBucket>

    suspend fun allowlist(): Got<DozeAllowlist>

    /** `device_idle_constants`; a value of null is an unset key. */
    suspend fun dozeConstants(): Got<String?>

    /** The duration keys of `dumpsys deviceidle` in milliseconds. */
    suspend fun dozeEffectiveMs(): Got<Map<String, Long>>

    /** Whether a command could run now: a worker is bound or can be. Reads say so themselves; this is for actions that read nothing. */
    val canAct: Boolean

    fun batterySaver(): Boolean

    fun charging(): Boolean

    /**
     * Why [packageName] cannot be acted on: not installed, or Coulomb itself, the manager it runs
     * through or a package of a system UID. Null for an app an action may target.
     */
    fun appBlock(packageName: String): ActionBlock?

    suspend fun act(action: PrivilegedAction): Ran

    /** [action] as one `adb shell` line. */
    fun commandLine(action: PrivilegedAction): String
}

/**
 * [io] with each read made once: several previews of one screen ask the phone the same things.
 * It lives for one call; a write goes straight through, and nothing read before it is reused after.
 */
internal class OnceIo(
    private val io: ActionIo,
) : ActionIo by io {
    private val appOps = HashMap<String, Got<AppOpReading>>()
    private val buckets = HashMap<String, Got<StandbyBucket>>()
    private var lists: Got<DozeAllowlist>? = null
    private var constants: Got<String?>? = null
    private var effective: Got<Map<String, Long>>? = null

    override suspend fun runInBackground(packageName: String): Got<AppOpReading> =
        appOps[packageName] ?: io.runInBackground(packageName).also { appOps[packageName] = it }

    override suspend fun bucket(packageName: String): Got<StandbyBucket> =
        buckets[packageName] ?: io.bucket(packageName).also { buckets[packageName] = it }

    override suspend fun allowlist(): Got<DozeAllowlist> = lists ?: io.allowlist().also { lists = it }

    override suspend fun dozeConstants(): Got<String?> = constants ?: io.dozeConstants().also { constants = it }

    override suspend fun dozeEffectiveMs(): Got<Map<String, Long>> = effective ?: io.dozeEffectiveMs().also { effective = it }
}

/** The action log's rows, so the engine can be tested without a database. */
internal interface ActionLog {
    suspend fun insert(row: ActionLogEntity): Long

    suspend fun update(row: ActionLogEntity)

    suspend fun delete(row: ActionLogEntity)

    suspend fun row(id: Long): ActionLogEntity?

    /** Not reverted, newest first. */
    suspend fun unreverted(): List<ActionLogEntity>

    /** The newest [limit] rows, newest first. */
    fun observe(limit: Int): Flow<List<ActionLogEntity>>

    /** [unreverted], observed. */
    fun observeUnreverted(): Flow<List<ActionLogEntity>>

    /** The newest row that is not an automation call's own. */
    fun observeLatestAction(): Flow<ActionLogEntity?>
}

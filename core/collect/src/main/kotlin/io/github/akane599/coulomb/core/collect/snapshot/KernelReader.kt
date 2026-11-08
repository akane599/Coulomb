// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import io.github.akane599.coulomb.core.model.kernel.KernelPaths
import io.github.akane599.coulomb.core.model.kernel.LastWakeup
import io.github.akane599.coulomb.core.model.kernel.SuspendStats
import io.github.akane599.coulomb.core.model.kernel.WakeupSourceInstance
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.parsing.kernel.KernelFiles
import io.github.akane599.coulomb.core.parsing.kernel.KernelFilesParser
import io.github.akane599.coulomb.core.parsing.kernel.KernelLogParser
import io.github.akane599.coulomb.core.parsing.kernel.KernelLogRead
import io.github.akane599.coulomb.core.parsing.kernel.KernelSources
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.privileged.WorkerOrigin

/**
 * The S25, S26 and S9 read of a heavy snapshot (ADR-0029 §2, §5). A value is set only for a source
 * read `ok`; a source cut short or unreadable keeps nothing.
 */
internal data class KernelFilesRead(
    /** `KernelBoot.key` of the `boot_id` the read began with; null when it had none. */
    val bootKey: Long?,
    val suspendStats: SuspendStats?,
    val lastWakeup: LastWakeup?,
    val wakeupSources: List<WakeupSourceInstance>?,
    /** S25, S26 and S9, each always present. */
    val statuses: Map<String, SourceStatus>,
    /** The connection the worker read them under; null when no worker read ran. */
    val origin: WorkerOrigin?,
) {
    /** [bootKey] when the read kept a value to store under it; null otherwise. */
    val storedBoot: Long?
        get() = bootKey.takeIf { suspendStats != null || lastWakeup != null || wakeupSources != null }

    /** The CPU slept during the read: its counters cannot be placed in time (ADR-0018). */
    fun slept(): KernelFilesRead =
        copy(
            suspendStats = null,
            lastWakeup = null,
            wakeupSources = null,
            statuses = statuses.mapValues { (_, s) -> if (s == SourceStatus.OK) SourceStatus.SLEPT else s },
        )

    /** Not kernel data after all, such as a read from another connection (ADR-0029 §1). */
    fun discarded(): KernelFilesRead =
        copy(suspendStats = null, lastWakeup = null, wakeupSources = null, statuses = all(SourceStatus.FAILED).statuses)

    companion object {
        fun all(status: SourceStatus): KernelFilesRead =
            KernelFilesRead(null, null, null, null, KernelReader.FILE_SOURCES.associateWith { status }, null)
    }
}

/** The S27 read of a heavy snapshot (ADR-0029 §2, §5). */
internal data class KernelLogResult(
    /** What to store: the whole read, or the exact prefix of one cut short; null when nothing usable came. */
    val read: KernelLogRead?,
    val status: SourceStatus,
    /** The connection the worker read it under; null when no worker read ran. */
    val origin: WorkerOrigin?,
) {
    fun discarded(): KernelLogResult = copy(read = null, status = SourceStatus.FAILED)

    companion object {
        fun of(status: SourceStatus): KernelLogResult = KernelLogResult(null, status, null)
    }
}

/** The root kernel reads through the worker, each parsed into what may be stored (ADR-0029). */
internal class KernelReader(
    private val access: PrivilegedAccess,
) {
    suspend fun files(timeoutMs: Long): KernelFilesRead =
        filesOf(
            access.readKernelFiles<Parsed<KernelFiles>>(ALL_FILES, timeoutMs, isComplete = { it.report.complete }) {
                KernelFilesParser.parse(it)
            },
        )

    suspend fun log(
        bootKey: Long,
        afterSeq: Long,
        timeoutMs: Long,
    ): KernelLogResult =
        logOf(
            access.readKernelLog<Parsed<KernelLogRead>>(bootKey, afterSeq, timeoutMs, isComplete = { it.report.complete }) {
                KernelLogParser.parse(it)
            },
        )

    companion object {
        const val S9: String = "S9"
        const val S25: String = "S25"
        const val S26: String = "S26"
        const val S27: String = "S27"

        /** The sources [files] reads, in the worker's order. */
        val FILE_SOURCES: List<String> = listOf(S25, S26, S9)

        private const val ALL_FILES = KernelPaths.READ_SUSPEND_STATS or KernelPaths.READ_WAKEUP_REASONS or KernelPaths.READ_WAKEUP_SOURCES

        /**
         * Each source of one bulk read: `ok` only when its parser found it whole, `partial` when
         * some of it came, `failed` otherwise. Without a `boot_id` nothing can be stored, since
         * every kernel row is keyed by its kernel boot.
         */
        fun filesOf(result: DumpResult<Parsed<KernelFiles>>): KernelFilesRead {
            val (parsed, origin) = valueAndOrigin(result)
            val split = parsed?.value?.let(KernelSources::split)
            val bootKey = split?.bootKey ?: return KernelFilesRead.all(SourceStatus.FAILED).copy(origin = origin)
            val s25 = status(split.suspendStats)
            val s26 = status(split.lastWakeup)
            val s9 = split.wakeupSources.let { p -> if (p == null) SourceStatus.FAILED else listStatus(p) }
            return KernelFilesRead(
                bootKey = bootKey,
                suspendStats = split.suspendStats?.value?.takeIf { s25 == SourceStatus.OK },
                lastWakeup = split.lastWakeup?.value?.takeIf { s26 == SourceStatus.OK },
                wakeupSources = split.wakeupSources?.value?.takeIf { s9 == SourceStatus.OK },
                statuses = mapOf(S25 to s25, S26 to s26, S9 to s9),
                origin = origin,
            )
        }

        /**
         * A whole read is `ok`. One cut short is `partial` and keeps its exact prefix, which the parser
         * vouches for up to its last kept record. One without a header names no kernel boot and is
         * `failed`.
         */
        fun logOf(result: DumpResult<Parsed<KernelLogRead>>): KernelLogResult {
            val (parsed, origin) = valueAndOrigin(result)
            val read = parsed?.value?.takeIf { it.bootKey != null } ?: return KernelLogResult(null, SourceStatus.FAILED, origin)
            val whole = result is DumpResult.Complete && parsed.report.complete
            return KernelLogResult(read, if (whole) SourceStatus.OK else SourceStatus.PARTIAL, origin)
        }

        private fun <R> valueAndOrigin(result: DumpResult<R>): Pair<R?, WorkerOrigin?> =
            when (result) {
                is DumpResult.Complete -> result.value to result.origin
                is DumpResult.Partial -> result.value to result.origin
                is DumpResult.Unavailable -> null to null
            }

        private fun status(parsed: Parsed<*>?): SourceStatus =
            when {
                parsed == null -> SourceStatus.FAILED
                parsed.value != null && parsed.report.complete -> SourceStatus.OK
                parsed.value != null || parsed.report.bytesRead > 0 -> SourceStatus.PARTIAL
                else -> SourceStatus.FAILED
            }

        private fun listStatus(parsed: Parsed<List<*>>): SourceStatus =
            when {
                parsed.report.complete -> SourceStatus.OK
                parsed.value.isNotEmpty() || parsed.report.bytesRead > 0 -> SourceStatus.PARTIAL
                else -> SourceStatus.FAILED
            }
    }
}

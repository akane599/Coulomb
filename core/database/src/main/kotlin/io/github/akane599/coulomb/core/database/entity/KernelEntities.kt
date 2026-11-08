// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.akane599.coulomb.core.model.kernel.LastWakeup
import io.github.akane599.coulomb.core.model.kernel.SeqGap
import io.github.akane599.coulomb.core.model.kernel.SuspendStats
import io.github.akane599.coulomb.core.model.kernel.SuspendStep
import io.github.akane599.coulomb.core.model.kernel.WakeupSourceInstance
import io.github.akane599.coulomb.core.model.kernel.WakeupSourceStats

/**
 * SPEC 8 `KernelRead` (ADR-0029 §3): what one snapshot read from the root kernel sources, under
 * the kernel boot [kernelBoot]. Each source's columns are all null when that source was not read
 * `ok`, never zero:
 * - S25 ([suspendSuccess] and the rest of `suspend_stats`) and S26 ([resumeReason] and the two
 *   times) are counters and readings like the per-UID ones, and are cleared when the snapshot's
 *   counters are pruned;
 * - S27 ([kmsgGaps] and the sequence numbers) says which kernel log records the snapshot's read
 *   covered. It stays as long as the events that read stored: the 7-day S27 ring.
 *
 * The row goes once every source is cleared, and with its snapshot.
 */
@Entity(
    tableName = "kernel_read",
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
public data class KernelReadEntity(
    @PrimaryKey val snapshotId: Long,
    /** `KernelBoot.key` of the `boot_id` read with the sources. */
    val kernelBoot: Long,
    // S25: `/sys/power/suspend_stats`.
    val suspendSuccess: Long? = null,
    val suspendFail: Long? = null,
    val failedFreeze: Long? = null,
    val failedPrepare: Long? = null,
    val failedSuspend: Long? = null,
    val failedSuspendLate: Long? = null,
    val failedSuspendNoirq: Long? = null,
    val failedResume: Long? = null,
    val failedResumeEarly: Long? = null,
    val failedResumeNoirq: Long? = null,
    val lastFailedDev: String? = null,
    val lastFailedErrno: Int? = null,
    val lastFailedStep: String? = null,
    /**
     * S26 (`/sys/kernel/wakeup_reasons`): the latest resume cause in the kernel's own format
     * ([KernelCodes.encodeCause]); "" when none was recorded.
     */
    val resumeReason: String? = null,
    val suspendResumeNs: Long? = null,
    val sleepNs: Long? = null,
    /** S27 (`/dev/kmsg`): the cursor the read applied; null when it read from the ring's start. */
    val kmsgAfterSeq: Long? = null,
    /** The first record past the cursor, kept or not; null when there was none. */
    val kmsgFirstSeq: Long? = null,
    /**
     * The last record the read covered, kept or not. A read that found nothing past its cursor
     * covered up to the cursor, so this is then [kmsgAfterSeq].
     */
    val kmsgLastSeq: Long? = null,
    /** The read's gaps as `from-to` pairs separated by commas ([KernelCodes.encodeGaps]); "" for none. */
    val kmsgGaps: String? = null,
) {
    /** S25 as read, or null when it was not read `ok`. */
    val suspendStats: SuspendStats?
        get() {
            val success = suspendSuccess ?: return null
            val fail = suspendFail ?: return null
            val steps =
                listOf(
                    SuspendStep.FREEZE to failedFreeze,
                    SuspendStep.PREPARE to failedPrepare,
                    SuspendStep.SUSPEND to failedSuspend,
                    SuspendStep.SUSPEND_LATE to failedSuspendLate,
                    SuspendStep.SUSPEND_NOIRQ to failedSuspendNoirq,
                    SuspendStep.RESUME to failedResume,
                    SuspendStep.RESUME_EARLY to failedResumeEarly,
                    SuspendStep.RESUME_NOIRQ to failedResumeNoirq,
                ).mapNotNull { (step, value) -> value?.let { step to it } }.toMap()
            return SuspendStats(success, fail, steps, lastFailedDev, lastFailedErrno, lastFailedStep)
        }

    /** S26 as read, or null when it was not read `ok`. */
    val lastWakeup: LastWakeup?
        get() = resumeReason?.let { LastWakeup(KernelCodes.decodeCause(it), suspendResumeNs, sleepNs) }

    /** S27's gaps, or null when the log was not read `ok`. */
    val kmsgGapList: List<SeqGap>?
        get() = kmsgGaps?.let(KernelCodes::decodeGaps)

    public companion object {
        /**
         * A row for [snapshotId] (ignored on insert through `SnapshotDao`). Pass null for a source
         * that was not read `ok`.
         */
        public fun of(
            snapshotId: Long,
            kernelBoot: Long,
            suspendStats: SuspendStats? = null,
            lastWakeup: LastWakeup? = null,
            kmsg: KmsgSpan? = null,
        ): KernelReadEntity {
            val steps = suspendStats?.failedSteps.orEmpty()
            return KernelReadEntity(
                snapshotId = snapshotId,
                kernelBoot = kernelBoot,
                suspendSuccess = suspendStats?.success,
                suspendFail = suspendStats?.fail,
                failedFreeze = steps[SuspendStep.FREEZE],
                failedPrepare = steps[SuspendStep.PREPARE],
                failedSuspend = steps[SuspendStep.SUSPEND],
                failedSuspendLate = steps[SuspendStep.SUSPEND_LATE],
                failedSuspendNoirq = steps[SuspendStep.SUSPEND_NOIRQ],
                failedResume = steps[SuspendStep.RESUME],
                failedResumeEarly = steps[SuspendStep.RESUME_EARLY],
                failedResumeNoirq = steps[SuspendStep.RESUME_NOIRQ],
                lastFailedDev = suspendStats?.lastFailedDev,
                lastFailedErrno = suspendStats?.lastFailedErrno,
                lastFailedStep = suspendStats?.lastFailedStep,
                resumeReason = lastWakeup?.let { KernelCodes.encodeCause(it.reason) },
                suspendResumeNs = lastWakeup?.suspendResumeNs,
                sleepNs = lastWakeup?.sleepNs,
                kmsgAfterSeq = kmsg?.afterSeq,
                kmsgFirstSeq = kmsg?.firstSeq,
                kmsgLastSeq = kmsg?.let { it.lastSeq ?: it.afterSeq },
                kmsgGaps = kmsg?.let { KernelCodes.encodeGaps(it.gaps) },
            )
        }
    }
}

/** The sequence span of one S27 read, as `KernelLogRead` reports it. */
public data class KmsgSpan(
    val afterSeq: Long?,
    val firstSeq: Long?,
    val lastSeq: Long?,
    val gaps: List<SeqGap>,
)

/**
 * SPEC 8 `WakeupSourceStat` (ADR-0029 §3): one S9 source instance at a heavy snapshot. Rows are per
 * `wakeupN` instance, never summed by name, so a destroyed and recreated source shows as a reset.
 * The name is text with no foreign key, so the `wake_tag` prune stays valid. Pruned with the
 * snapshot's other counters.
 */
@Entity(
    tableName = "wakeup_source_stat",
    primaryKeys = ["snapshotId", "instance"],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
public data class WakeupSourceStatEntity(
    val snapshotId: Long,
    /** The N of `wakeupN`. */
    val instance: Int,
    val name: String,
    val activeCount: Long,
    val eventCount: Long,
    val wakeupCount: Long,
    val expireCount: Long,
    val activeTimeMs: Long,
    val totalTimeMs: Long,
    val maxTimeMs: Long,
    val lastChangeMs: Long,
    val preventSuspendTimeMs: Long,
) {
    val source: WakeupSourceInstance
        get() =
            WakeupSourceInstance(
                instance,
                WakeupSourceStats(
                    name,
                    activeCount,
                    eventCount,
                    wakeupCount,
                    expireCount,
                    activeTimeMs,
                    totalTimeMs,
                    maxTimeMs,
                    lastChangeMs,
                    preventSuspendTimeMs,
                ),
            )

    public companion object {
        public fun of(
            snapshotId: Long,
            source: WakeupSourceInstance,
        ): WakeupSourceStatEntity =
            with(source.stats) {
                WakeupSourceStatEntity(
                    snapshotId,
                    source.instance,
                    name,
                    activeCount,
                    eventCount,
                    wakeupCount,
                    expireCount,
                    activeTimeMs,
                    totalTimeMs,
                    maxTimeMs,
                    lastChangeMs,
                    preventSuspendTimeMs,
                )
            }
    }
}

/**
 * SPEC 8 `KernelLogEvent` (ADR-0029 §3): one S27 record. Unique per `(kernelBoot, seq)` and inserted
 * with `INSERT OR IGNORE`, so reading a record twice stores it once. [kind] is a [KernelCodes]
 * event code; [tagId] a `history_tag` id without a foreign key, like `history_event`'s, whose
 * prune also checks this table. The row goes with the snapshot whose read first stored it, or at
 * the S27 ring's 7-day cut.
 */
@Entity(
    tableName = "kernel_log_event",
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["readSnapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("kernelBoot", "seq", unique = true), Index("readSnapshotId")],
)
public data class KernelLogEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kernelBoot: Long,
    val seq: Long,
    /** `local_clock` µs since boot; it stops in suspend, like CLOCK_MONOTONIC. */
    val monotonicUs: Long,
    val kind: Int,
    val irq: Int?,
    val tagId: Long?,
    val errno: Int?,
    val readSnapshotId: Long,
)

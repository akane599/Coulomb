// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.kernel

/**
 * S25: `/sys/power/suspend_stats` (kernel/power/main.c, `suspend_attrs`, kernel/common
 * android16-6.12 and android15-6.6). Every counter counts since boot; a reboot starts them at zero.
 */
public data class SuspendStats(
    /** Suspends that reached sleep and resumed: the real deep-sleep count. */
    val success: Long,
    /** Suspend attempts that failed at any step. */
    val fail: Long,
    /** Failures per step; a step the kernel did not report is absent, never zero. */
    val failedSteps: Map<SuspendStep, Long>,
    /** Device of the latest failure, or null when none was recorded (the kernel prints an empty line). */
    val lastFailedDev: String?,
    /** Errno of the latest failure, or null when none was recorded (the kernel prints 0). */
    val lastFailedErrno: Int?,
    /** Step name of the latest failure, as the kernel prints it, or null when none was recorded. */
    val lastFailedStep: String?,
)

/** The `failed_*` files of `suspend_stats`, in the kernel's step order. Stored by name: add, never rename. */
public enum class SuspendStep(
    public val file: String,
) {
    FREEZE("failed_freeze"),
    PREPARE("failed_prepare"),
    SUSPEND("failed_suspend"),
    SUSPEND_LATE("failed_suspend_late"),
    SUSPEND_NOIRQ("failed_suspend_noirq"),
    RESUME("failed_resume"),
    RESUME_EARLY("failed_resume_early"),
    RESUME_NOIRQ("failed_resume_noirq"),
}

/**
 * S26: `/sys/kernel/wakeup_reasons` (kernel/power/wakeup_reason.c, `last_resume_reason_show` and
 * `last_suspend_time_show`, kernel/common android16-6.12). Only the latest resume is kept.
 */
public data class LastWakeup(
    val reason: WakeupCause,
    /** Time spent suspending and resuming around the latest sleep, in ns; null when unreadable. */
    val suspendResumeNs: Long?,
    /** Time asleep in the latest suspend, in ns; null when unreadable. */
    val sleepNs: Long?,
)

/** Why the phone last woke, or why a suspend was abandoned. */
public sealed interface WakeupCause {
    /** One or more interrupts, leaf IRQs first as the kernel lists them. */
    public data class Irqs(
        val irqs: List<WakeupIrq>,
    ) : WakeupCause

    /** The suspend was abandoned before sleeping (`Abort: <reason>`). */
    public data class Abort(
        val reason: String,
    ) : WakeupCause

    /** A wakeup the kernel could not tie to an interrupt (`-1 <reason>`). */
    public data class Abnormal(
        val reason: String,
    ) : WakeupCause

    /** Nothing recorded yet, such as before the first suspend after boot. */
    public data object None : WakeupCause
}

public data class WakeupIrq(
    val irq: Int,
    val name: String,
)

/**
 * S9: one `/sys/class/wakeup/wakeupN` directory (drivers/base/power/wakeup_stats.c,
 * `wakeup_source_attrs`, kernel/common android16-6.12). Counters run since the source was created;
 * a source can be destroyed and a new one take its name.
 */
public data class WakeupSourceStats(
    val name: String,
    val activeCount: Long,
    val eventCount: Long,
    /** Times this source aborted a suspend or woke the phone. */
    val wakeupCount: Long,
    val expireCount: Long,
    /** How long the source has been active right now; 0 when inactive. */
    val activeTimeMs: Long,
    val totalTimeMs: Long,
    val maxTimeMs: Long,
    val lastChangeMs: Long,
    val preventSuspendTimeMs: Long,
)

/** S27: one kernel log record the worker let through (`/dev/kmsg`, Documentation/ABI/testing/dev-kmsg). */
public data class KernelLogRecord(
    /** The kernel's 64-bit record sequence number; unique within a boot. */
    val seq: Long,
    /** `local_clock` time in µs since boot; it stops during suspend, like CLOCK_MONOTONIC. */
    val monotonicUs: Long,
    val event: KernelLogEvent,
)

/** The suspend and resume lines S27 keeps, by the kernel line that prints each. */
public sealed interface KernelLogEvent {
    /** `PM: suspend entry (<state>)` (kernel/power/suspend.c `pm_suspend`). */
    public data class SuspendEntry(
        val state: String,
    ) : KernelLogEvent

    /** `PM: suspend exit` (kernel/power/suspend.c `pm_suspend`). */
    public data object SuspendExit : KernelLogEvent

    /** `Resume caused by IRQ <n>, <name>` (kernel/power/wakeup_reason.c). */
    public data class ResumeIrq(
        val irq: WakeupIrq,
    ) : KernelLogEvent

    /** `Resume caused by <reason>`: a wakeup not tied to an interrupt. */
    public data class ResumeOther(
        val reason: String,
    ) : KernelLogEvent

    /** `Resume cause unknown`. */
    public data object ResumeUnknown : KernelLogEvent

    /** `Abort: <reason>`: a suspend abandoned before sleeping. */
    public data class Abort(
        val reason: String,
    ) : KernelLogEvent

    /** `PM: Some devices failed to suspend, or early wake event detected` (kernel/power/suspend.c). */
    public data object DevicesFailed : KernelLogEvent

    /** `<driver> <device>: PM: failed to <verb><phase>: error <n>` (drivers/base/power/main.c `pm_dev_err`). */
    public data class DeviceFailed(
        val device: String,
        val step: String,
        val errno: Int,
    ) : KernelLogEvent

    /** `Freezing <what> failed|aborted after …` (kernel/power/process.c `try_to_freeze_tasks`). */
    public data class FreezeFailed(
        val what: String,
        val aborted: Boolean,
    ) : KernelLogEvent
}

/**
 * One S9 source as one `wakeupN` directory. The kernel reuses a freed N for a new source
 * (`ida_alloc` in drivers/base/power/wakeup.c `wakeup_source_create`), so a window compares
 * instances and treats any counter that fell as a new source (ADR-0029 §4).
 */
public data class WakeupSourceInstance(
    /** The N of `wakeupN`. */
    val instance: Int,
    val stats: WakeupSourceStats,
)

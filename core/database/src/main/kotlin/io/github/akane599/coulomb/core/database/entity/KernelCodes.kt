// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.TypeConverter
import io.github.akane599.coulomb.core.model.PrivilegeMode
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage
import io.github.akane599.coulomb.core.model.kernel.KernelLogEvent
import io.github.akane599.coulomb.core.model.kernel.SeqGap
import io.github.akane599.coulomb.core.model.kernel.WakeupCause
import io.github.akane599.coulomb.core.model.kernel.WakeupIrq

/**
 * Storage forms of the schema v3 values (ADR-0029). Codes are frozen: append, never renumber.
 * `KernelCodesTest` round-trips every form.
 */
public object KernelCodes {
    @TypeConverter
    public fun privilegeModeCode(mode: PrivilegeMode?): Int? = mode?.code

    @TypeConverter
    public fun privilegeMode(code: Int?): PrivilegeMode? = code?.let(PrivilegeMode::ofCode)

    @TypeConverter
    public fun coverageCode(coverage: KernelCoverage?): Int? = coverage?.code

    @TypeConverter
    public fun coverage(code: Int?): KernelCoverage? = code?.let(KernelCoverage::ofCode)

    /** `from-to` pairs separated by commas; "" for none. */
    public fun encodeGaps(gaps: List<SeqGap>): String = gaps.joinToString(",") { "${it.from}-${it.to}" }

    public fun decodeGaps(text: String): List<SeqGap> =
        if (text.isEmpty()) {
            emptyList()
        } else {
            text.split(',').map { pair ->
                val dash = pair.indexOf('-')
                check(dash > 0) { "Bad stored gap $pair" }
                SeqGap(pair.substring(0, dash).toLong(), pair.substring(dash + 1).toLong())
            }
        }

    /**
     * A resume cause in the format of `last_resume_reason` (kernel/power/wakeup_reason.c,
     * `last_resume_reason_show`): one `<irq> <name>` line per interrupt, `Abort: <reason>`, or
     * `-1 <reason>`; "" when nothing was recorded.
     */
    public fun encodeCause(cause: WakeupCause): String =
        when (cause) {
            is WakeupCause.Irqs -> cause.irqs.joinToString("\n") { "${it.irq} ${it.name}" }
            is WakeupCause.Abort -> "$ABORT${cause.reason}"
            is WakeupCause.Abnormal -> "$ABNORMAL${cause.reason}"
            WakeupCause.None -> ""
        }

    public fun decodeCause(text: String): WakeupCause =
        when {
            text.isEmpty() -> {
                WakeupCause.None
            }

            text.startsWith(ABORT) -> {
                WakeupCause.Abort(text.removePrefix(ABORT))
            }

            text.startsWith(ABNORMAL) -> {
                WakeupCause.Abnormal(text.removePrefix(ABNORMAL))
            }

            else -> {
                WakeupCause.Irqs(
                    text.split('\n').map { line ->
                        val space = line.indexOf(' ')
                        check(space > 0) { "Bad stored resume cause $line" }
                        WakeupIrq(line.substring(0, space).toInt(), line.substring(space + 1))
                    },
                )
            }
        }

    /** One S27 event as stored: a code and the optional columns of `kernel_log_event`, with the tag as text. */
    public data class StoredEvent(
        val kind: Int,
        val irq: Int?,
        val tag: String?,
        val errno: Int?,
    )

    /**
     * The stored form of [event], or null for the suspend entry and exit lines. Suspends are counted
     * from S25, never from S27 (ADR-0029 §4), so those two lines would add two rows per suspend
     * that no window or screen reads.
     */
    public fun encodeEvent(event: KernelLogEvent): StoredEvent? =
        when (event) {
            is KernelLogEvent.SuspendEntry, KernelLogEvent.SuspendExit -> null

            is KernelLogEvent.ResumeIrq -> StoredEvent(RESUME_IRQ, event.irq.irq, event.irq.name, null)

            is KernelLogEvent.ResumeOther -> StoredEvent(RESUME_OTHER, null, event.reason, null)

            KernelLogEvent.ResumeUnknown -> StoredEvent(RESUME_UNKNOWN, null, null, null)

            is KernelLogEvent.Abort -> StoredEvent(ABORT_REASON, null, event.reason, null)

            KernelLogEvent.DevicesFailed -> StoredEvent(DEVICES_FAILED, null, null, null)

            // The kernel log escapes control characters (kernel/printk/printk.c, `msg_add_ext_text`),
            // so a record's text never holds a raw tab.
            is KernelLogEvent.DeviceFailed -> StoredEvent(DEVICE_FAILED, null, "${event.device}\t${event.step}", event.errno)

            is KernelLogEvent.FreezeFailed -> StoredEvent(if (event.aborted) FREEZE_ABORTED else FREEZE_FAILED, null, event.what, null)
        }

    public fun decodeEvent(stored: StoredEvent): KernelLogEvent {
        val tag = stored.tag
        return when (stored.kind) {
            RESUME_IRQ -> KernelLogEvent.ResumeIrq(WakeupIrq(checkNotNull(stored.irq), checkNotNull(tag)))
            RESUME_OTHER -> KernelLogEvent.ResumeOther(checkNotNull(tag))
            RESUME_UNKNOWN -> KernelLogEvent.ResumeUnknown
            ABORT_REASON -> KernelLogEvent.Abort(checkNotNull(tag))
            DEVICES_FAILED -> KernelLogEvent.DevicesFailed
            DEVICE_FAILED -> deviceFailed(checkNotNull(tag), checkNotNull(stored.errno))
            FREEZE_FAILED -> KernelLogEvent.FreezeFailed(checkNotNull(tag), aborted = false)
            FREEZE_ABORTED -> KernelLogEvent.FreezeFailed(checkNotNull(tag), aborted = true)
            else -> error("Unknown kernel event code ${stored.kind}")
        }
    }

    private fun deviceFailed(
        tag: String,
        errno: Int,
    ): KernelLogEvent.DeviceFailed {
        val tab = tag.indexOf('\t')
        check(tab > 0) { "Bad stored device failure $tag" }
        return KernelLogEvent.DeviceFailed(tag.substring(0, tab), tag.substring(tab + 1), errno)
    }

    private const val ABORT = "Abort: "
    private const val ABNORMAL = "-1 "

    internal const val RESUME_IRQ = 0
    internal const val RESUME_OTHER = 1
    internal const val RESUME_UNKNOWN = 2
    internal const val ABORT_REASON = 3
    internal const val DEVICES_FAILED = 4
    internal const val DEVICE_FAILED = 5
    internal const val FREEZE_FAILED = 6
    internal const val FREEZE_ABORTED = 7
}

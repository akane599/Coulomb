// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.kernel

/**
 * The S27 line allowlist (SPEC 19.1, ADR-0029 §2): the only kernel log text the worker lets out.
 * Each prefix is the format string of one kernel line, cited at its event in [KernelLogEvent].
 * Everything else in the kernel log, which can hold addresses, serials and process names, stays in
 * the worker.
 */
public object KernelLogAllowlist {
    public const val SUSPEND_ENTRY: String = "PM: suspend entry ("
    public const val SUSPEND_EXIT: String = "PM: suspend exit"

    // The next four come from `print_wakeup_sources` in kernel/power/wakeup_reason.c (kernel/common
    // android16-6.12 L274-325, android15-6.6 L283-295), which defines no `pr_fmt`, so the lines carry
    // no prefix. It runs once per suspend attempt at PM_POST_SUSPEND and prints one "Abort" line, or
    // one "Resume caused by IRQ" line per leaf interrupt, or one of the other two.
    public const val RESUME_IRQ: String = "Resume caused by IRQ "
    public const val RESUME_BY: String = "Resume caused by "
    public const val RESUME_UNKNOWN: String = "Resume cause unknown"
    public const val ABORT: String = "Abort: "
    public const val DEVICES_FAILED: String = "PM: Some devices failed to suspend, or early wake event detected"
    public const val FREEZING: String = "Freezing "
    public const val FREEZE_FAILED: String = " failed after "
    public const val FREEZE_ABORTED: String = " aborted after "
    public const val DEVICE_FAILED: String = ": PM: failed to "

    /** Whether the worker may pass on a record whose message text is [text]. */
    public fun keeps(text: String): Boolean =
        text.startsWith(SUSPEND_ENTRY) ||
            text.startsWith(SUSPEND_EXIT) ||
            text.startsWith(RESUME_BY) ||
            text.startsWith(RESUME_UNKNOWN) ||
            text.startsWith(ABORT) ||
            text.startsWith(DEVICES_FAILED) ||
            freezeFailure(text) ||
            DEVICE_FAILED in text

    private fun freezeFailure(text: String): Boolean = text.startsWith(FREEZING) && (FREEZE_FAILED in text || FREEZE_ABORTED in text)
}

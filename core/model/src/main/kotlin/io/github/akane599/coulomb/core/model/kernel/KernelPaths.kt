// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.kernel

/**
 * The kernel paths of the root sources (ADR-0028, SPEC 19.1), shared by the worker that reads them
 * and the parsers that read its stream. The worker reads only these.
 */
public object KernelPaths {
    /** S25, kernel/power/main.c `suspend_attrs` (kernel/common android16-6.12 L464-482). */
    public const val SUSPEND_STATS: String = "/sys/power/suspend_stats"

    /** S26, kernel/power/wakeup_reason.c `attr_group` (android16-6.12 L327-399). */
    public const val WAKEUP_REASONS: String = "/sys/kernel/wakeup_reasons"
    public const val LAST_RESUME_REASON: String = "last_resume_reason"
    public const val LAST_SUSPEND_TIME: String = "last_suspend_time"

    /** S9, drivers/base/power/wakeup_stats.c: one `wakeupN` directory per wakeup source. */
    public const val WAKEUP_CLASS: String = "/sys/class/wakeup"

    /**
     * The attribute files of each `wakeupN` directory, `name` first (drivers/base/power/wakeup_stats.c
     * `wakeup_source_attrs`, android16-6.12 L116-128; the same list on android15-6.6).
     */
    public val WAKEUP_ATTRIBUTES: List<String> =
        listOf(
            "name",
            "active_count",
            "event_count",
            "wakeup_count",
            "expire_count",
            "active_time_ms",
            "total_time_ms",
            "max_time_ms",
            "last_change_ms",
            "prevent_suspend_time_ms",
        )

    /** S27, kernel/printk/printk.c `devkmsg_fops` (android16-6.12: open L948, read L829-896). */
    public const val KMSG: String = "/dev/kmsg"

    /** A random id per kernel boot (drivers/char/random.c `proc_do_uuid`, ctl_table `boot_id`, android16-6.12 L1614-1691). */
    public const val BOOT_ID: String = "/proc/sys/kernel/random/boot_id"

    /** The `sources` bits of the worker's bulk kernel-file read. */
    public const val READ_SUSPEND_STATS: Int = 1
    public const val READ_WAKEUP_REASONS: Int = 2
    public const val READ_WAKEUP_SOURCES: Int = 4
}

/**
 * The kernel boot key: the first 64 bits of `boot_id`, which changes on every boot. Kernel counters
 * and log sequence numbers are compared only under the same key (ADR-0029 §2).
 */
public object KernelBoot {
    /** The key of a `boot_id` text (a UUID, `8-4-4-4-12` hex digits), or null when it is not one. */
    public fun key(bootId: String): Long? {
        val text = bootId.trim()
        val groups = text.split('-')
        val shape = groups.map { it.length } == UUID_GROUPS
        val hex = groups.joinToString("")
        if (!shape || !hex.all(::isHex)) return null
        return java.lang.Long.parseUnsignedLong(hex.substring(0, HEX_DIGITS_PER_KEY), HEX)
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c.lowercaseChar() in 'a'..'f'

    private val UUID_GROUPS = listOf(8, 4, 4, 4, 12)
    private const val HEX_DIGITS_PER_KEY = 16
    private const val HEX = 16
}

/** Kernel log records `from..to` (inclusive) that a read never saw, because the ring overwrote them. */
public data class SeqGap(
    val from: Long,
    val to: Long,
) {
    init {
        require(from in 0..to) { "gap $from..$to" }
    }

    val size: Long get() = to - from + 1
}

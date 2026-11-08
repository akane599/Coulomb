// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.kernel

import io.github.akane599.coulomb.core.model.kernel.KernelPaths
import io.github.akane599.coulomb.core.model.kernel.LastWakeup
import io.github.akane599.coulomb.core.model.kernel.SuspendStats
import io.github.akane599.coulomb.core.model.kernel.SuspendStep
import io.github.akane599.coulomb.core.model.kernel.WakeupCause
import io.github.akane599.coulomb.core.model.kernel.WakeupIrq
import io.github.akane599.coulomb.core.model.kernel.WakeupSourceStats
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.parsing.ParseContext

/**
 * S25: the files of `/sys/power/suspend_stats`, by file name, into [SuspendStats]. Never throws.
 *
 * The kernel prints each counter as `%u\n` (android16-6.12) or `%d\n` (android15-6.6), the last
 * failure's device and step as `%s\n` and its errno as `%d\n` (kernel/power/main.c, `suspend_attr`,
 * `suspend_step_attr`, `last_failed_*_show`). The value is null unless `success` and `fail` both
 * read: without them the read cannot end a window. A missing or unreadable step file leaves that
 * step out; it never becomes zero.
 */
public object SuspendStatsParser {
    public const val SOURCE: String = "sysfs.suspend_stats"

    public fun parse(files: Map<String, String>): Parsed<SuspendStats?> {
        val ctx = ParseContext(SOURCE)
        val known = setOf(SUCCESS, FAIL, LAST_DEV, LAST_ERRNO, LAST_STEP) + SuspendStep.entries.map { it.file }
        files.keys.filterNot { it in known }.forEach { ctx.unknown(it) }
        val success = counter(files, SUCCESS, ctx)
        val fail = counter(files, FAIL, ctx)
        val steps =
            SuspendStep.entries
                .mapNotNull { step ->
                    files[step.file]?.let { counter(files, step.file, ctx) }?.let { step to it }
                }.toMap()
        val dev = files[LAST_DEV]?.trimEnd('\n')?.takeIf { it.isNotBlank() }
        val errno = files[LAST_ERRNO]?.trim()?.toIntOrNull()?.takeIf { it != 0 }
        val step = files[LAST_STEP]?.trim()?.takeIf { it.isNotEmpty() }
        val value =
            if (success != null && fail != null) {
                SuspendStats(success, fail, steps, dev, errno, step)
            } else {
                null
            }
        val bytes = files.values.sumOf { it.length.toLong() }
        return Parsed(value, ctx.report(bytes, complete = value != null && steps.size == SuspendStep.entries.size))
    }

    private fun counter(
        files: Map<String, String>,
        name: String,
        ctx: ParseContext,
    ): Long? {
        val text = files[name] ?: return null.also { ctx.issue(0, name, "missing") }
        return text.trim().toLongOrNull()?.takeIf { it >= 0 } ?: null.also { ctx.issue(0, name, "not a counter") }
    }

    private const val SUCCESS = "success"
    private const val FAIL = "fail"
    private const val LAST_DEV = "last_failed_dev"
    private const val LAST_ERRNO = "last_failed_errno"
    private const val LAST_STEP = "last_failed_step"
}

/**
 * S26: `last_resume_reason` and `last_suspend_time` of `/sys/kernel/wakeup_reasons` into
 * [LastWakeup]. Never throws.
 *
 * Formats (kernel/power/wakeup_reason.c, android16-6.12 `last_resume_reason_show`,
 * `last_suspend_time_show`):
 * - `Abort: <reason>` with no newline;
 * - one `<irq> <name>\n` line per leaf interrupt;
 * - `-1 <reason>` for a wakeup not tied to an interrupt;
 * - empty when nothing was recorded.
 *
 * `last_suspend_time` is `<s>.<ns> <s>.<ns>\n`: suspend and resume time, then sleep time.
 */
public object WakeupReasonParser {
    public const val SOURCE: String = "sysfs.wakeup_reasons"

    public fun parse(
        lastResumeReason: String,
        lastSuspendTime: String?,
    ): Parsed<LastWakeup?> {
        val ctx = ParseContext(SOURCE)
        val cause = cause(lastResumeReason, ctx)
        val times = lastSuspendTime?.let { times(it, ctx) }
        val bytes = lastResumeReason.length.toLong() + (lastSuspendTime?.length ?: 0)
        val value = cause?.let { LastWakeup(it, times?.first, times?.second) }
        return Parsed(value, ctx.report(bytes, complete = value != null && times != null))
    }

    private fun cause(
        text: String,
        ctx: ParseContext,
    ): WakeupCause? {
        if (text.isBlank()) return WakeupCause.None
        if (text.startsWith(ABORT)) return WakeupCause.Abort(text.removePrefix(ABORT).trimEnd('\n'))
        if (text.startsWith(ABNORMAL)) return WakeupCause.Abnormal(text.removePrefix(ABNORMAL).trimEnd('\n'))
        val irqs = ArrayList<WakeupIrq>()
        var offset = 0L
        for (line in text.split('\n')) {
            if (line.isNotEmpty()) {
                val irq = irqLine(line)
                if (irq == null) {
                    ctx.issue(offset, "last_resume_reason", "not an IRQ line")
                } else {
                    irqs += irq
                }
            }
            offset += line.length + 1
        }
        return if (irqs.isEmpty()) null else WakeupCause.Irqs(irqs)
    }

    private fun times(
        text: String,
        ctx: ParseContext,
    ): Pair<Long, Long>? {
        val parts = text.trim().split(' ')
        val a = parts.getOrNull(0)?.let(::secondsToNs)
        val b = parts.getOrNull(1)?.let(::secondsToNs)
        if (parts.size != 2 || a == null || b == null) {
            ctx.issue(0, "last_suspend_time", "not two durations")
            return null
        }
        return a to b
    }

    private const val ABORT = "Abort: "
    private const val ABNORMAL = "-1 "
}

/** `<irq> <name>`: the IRQ number, one space, then the name, which is kept whole. */
internal fun irqLine(line: String): WakeupIrq? {
    val space = line.indexOf(' ')
    if (space <= 0) return null
    val irq = line.substring(0, space).toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val name = line.substring(space + 1).trim()
    return if (name.isEmpty()) null else WakeupIrq(irq, name)
}

/** `<s>.<9-digit ns>` into ns; null for anything else. */
internal fun secondsToNs(text: String): Long? {
    val dot = text.indexOf('.')
    val shaped = dot > 0 && text.length - dot - 1 == NS_DIGITS && text.removeRange(dot, dot + 1).all { it in '0'..'9' }
    val s = if (shaped) text.substring(0, dot).toLongOrNull()?.takeIf { it < Long.MAX_VALUE / NS_PER_S } else null
    val ns = if (shaped) text.substring(dot + 1).toLongOrNull() else null
    return if (s != null && ns != null) s * NS_PER_S + ns else null
}

/**
 * S9: the attribute files of one `/sys/class/wakeup/wakeupN` directory, by file name, into
 * [WakeupSourceStats]. Never throws. Every attribute in `wakeup_source_attrs`
 * (drivers/base/power/wakeup_stats.c, android16-6.12) must be present and readable: a source with
 * a missing counter is dropped whole, never filled with zero.
 */
public object WakeupSourceParser {
    public const val SOURCE: String = "sysfs.wakeup"

    public fun parse(attributes: Map<String, String>): Parsed<WakeupSourceStats?> {
        val ctx = ParseContext(SOURCE)
        attributes.keys.filterNot { it in ATTRIBUTES }.forEach { ctx.unknown(it) }
        val name = attributes[NAME]?.trimEnd('\n')?.takeIf { it.isNotEmpty() }
        if (name == null) ctx.issue(0, NAME, "missing")
        val values =
            COUNTERS.associateWith { key ->
                attributes[key]?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: null.also { ctx.issue(0, key, "missing or not a counter") }
            }
        val value =
            if (name != null && values.values.all { it != null }) {
                fun v(key: String) = values.getValue(key)!!
                WakeupSourceStats(
                    name = name,
                    activeCount = v("active_count"),
                    eventCount = v("event_count"),
                    wakeupCount = v("wakeup_count"),
                    expireCount = v("expire_count"),
                    activeTimeMs = v("active_time_ms"),
                    totalTimeMs = v("total_time_ms"),
                    maxTimeMs = v("max_time_ms"),
                    lastChangeMs = v("last_change_ms"),
                    preventSuspendTimeMs = v("prevent_suspend_time_ms"),
                )
            } else {
                null
            }
        return Parsed(value, ctx.report(attributes.values.sumOf { it.length.toLong() }, complete = value != null))
    }

    private const val NAME = "name"

    /** The attribute files the worker reads in each directory, `name` included. */
    public val ATTRIBUTES: List<String> = KernelPaths.WAKEUP_ATTRIBUTES

    private val COUNTERS = ATTRIBUTES - NAME
}

private const val NS_DIGITS = 9
private const val NS_PER_S = 1_000_000_000L

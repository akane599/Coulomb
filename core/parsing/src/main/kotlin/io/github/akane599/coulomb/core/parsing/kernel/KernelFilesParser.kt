// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.kernel

import io.github.akane599.coulomb.core.model.kernel.KernelBoot
import io.github.akane599.coulomb.core.model.kernel.KernelPaths
import io.github.akane599.coulomb.core.model.kernel.LastWakeup
import io.github.akane599.coulomb.core.model.kernel.SuspendStats
import io.github.akane599.coulomb.core.model.kernel.WakeupSourceInstance
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.parsing.ParseContext
import io.github.akane599.coulomb.core.parsing.io.MeteredSource
import java.io.IOException
import java.io.InputStream

/** The files of one bulk kernel read, by absolute path. */
public data class KernelFiles(
    val files: Map<String, String>,
    /** The errno of each file or directory the worker could not read. */
    val errors: Map<String, Int>,
) {
    /** [KernelBoot.key] of the `boot_id` the worker read first; null when it is missing. */
    val bootKey: Long? get() = files[KernelPaths.BOOT_ID]?.let(KernelBoot::key)

    /** Files directly inside [dir], by name. */
    public fun filesIn(dir: String): Map<String, String> {
        val prefix = "$dir/"
        return files
            .filterKeys { it.startsWith(prefix) && '/' !in it.substring(prefix.length) }
            .mapKeys { it.key.substring(prefix.length) }
    }

    /** Whether the worker reported an error for [path] or anything below it. */
    public fun failedAt(path: String): Boolean = errors.keys.any { it == path || it.startsWith("$path/") }

    /** Whether the stream holds anything for [path] or below it. */
    public fun touches(path: String): Boolean = failedAt(path) || files.keys.any { it.startsWith("$path/") }
}

/**
 * The worker's bulk kernel-file stream (ADR-0029 §2) into [KernelFiles]. Never throws.
 *
 * The stream is a sequence of frames, each starting with a header line:
 * - `@<path> <n>`, then exactly `n` bytes of the file, then `\n`;
 * - `!<path> <errno>` for a file or directory the worker could not read;
 * - `#end`, the last frame.
 *
 * Without `#end` the read was cut short and [Parsed.report] says incomplete; the frames before the
 * cut are whole, since each carries its length.
 */
public object KernelFilesParser {
    public const val SOURCE: String = "kernel_files"

    public fun parse(input: InputStream): Parsed<KernelFiles> {
        val ctx = ParseContext(SOURCE)
        val frames = Frames(MeteredSource(input), ctx)
        val ended = frames.readAll()
        val complete = ended && ctx.issueCountIsZero()
        return Parsed(KernelFiles(frames.files, frames.errors), ctx.report(frames.offset, complete))
    }

    private enum class Step { NEXT, END, STOP }

    private class Frames(
        private val meter: MeteredSource,
        private val ctx: ParseContext,
    ) {
        val files = LinkedHashMap<String, String>()
        val errors = LinkedHashMap<String, Int>()
        val offset: Long get() = meter.offset

        /** Reads every frame; true when the end frame arrived. */
        @Suppress("TooGenericExceptionCaught") // The parser boundary: bad input must never throw (SPEC 5.1).
        fun readAll(): Boolean =
            try {
                var step = Step.NEXT
                while (step == Step.NEXT) step = next()
                if (step == Step.END && !meter.source.exhausted()) ctx.issue(meter.offset, "end", "data after the end")
                step == Step.END
            } catch (e: IOException) {
                ctx.issue(meter.offset, "frame", "ended early: ${e.javaClass.simpleName}")
                false
            } catch (e: RuntimeException) {
                ctx.issue(meter.offset, "frame", "malformed: ${e.javaClass.simpleName}")
                false
            }

        private fun next(): Step {
            val offset = meter.offset
            val header = header(meter, ctx) ?: return Step.STOP
            return when {
                header == END -> {
                    Step.END
                }

                files.size + errors.size >= MAX_FRAMES -> {
                    ctx.issue(offset, "frame", "too many frames")
                    Step.STOP
                }

                header.startsWith(FILE) -> {
                    if (file(header, offset, meter, ctx, files)) Step.NEXT else Step.STOP
                }

                header.startsWith(ERROR) -> {
                    error(header, offset, ctx, errors)
                    Step.NEXT
                }

                else -> {
                    ctx.issue(offset, "frame", "unknown frame")
                    Step.STOP
                }
            }
        }
    }

    /** The next header line without its newline, or null at the end of the stream or on a bad line. */
    private fun header(
        meter: MeteredSource,
        ctx: ParseContext,
    ): String? {
        val source = meter.source
        if (source.exhausted()) {
            ctx.issue(meter.offset, "end", "no end frame")
            return null
        }
        val newline = source.indexOf(NEWLINE, 0, MAX_HEADER_BYTES)
        if (newline < 0) {
            ctx.issue(meter.offset, "frame", "header without a newline")
            return null
        }
        val text = source.readUtf8(newline)
        source.skip(1)
        return text
    }

    /** Reads one file frame; false when the stream cannot be trusted past it. */
    private fun file(
        header: String,
        offset: Long,
        meter: MeteredSource,
        ctx: ParseContext,
        files: MutableMap<String, String>,
    ): Boolean {
        val space = header.lastIndexOf(' ')
        val path = if (space > 1) header.substring(1, space) else ""
        val length = if (space > 1) header.substring(space + 1).toLongOrNull() else null
        if (!known(path) || length == null || length !in 0..MAX_FILE_BYTES) {
            // Without a trusted length the next header cannot be found.
            ctx.issue(offset, "frame", "bad file header")
            return false
        }
        val source = meter.source
        source.require(length + 1)
        val body = source.readUtf8(length)
        if (source.readByte() != NEWLINE) {
            ctx.issue(offset, "frame", "file not followed by a newline")
            return false
        }
        if (files.put(path, body) != null) ctx.issue(offset, "frame", "file repeated")
        return true
    }

    private fun error(
        header: String,
        offset: Long,
        ctx: ParseContext,
        errors: MutableMap<String, Int>,
    ) {
        val space = header.lastIndexOf(' ')
        val path = if (space > 1) header.substring(1, space) else ""
        val errno = if (space > 1) header.substring(space + 1).toIntOrNull() else null
        if (!known(path) || errno == null) {
            ctx.issue(offset, "frame", "bad error header")
        } else {
            errors[path] = errno
        }
    }

    private fun known(path: String): Boolean = path == KernelPaths.BOOT_ID || ROOTS.any { path == it || path.startsWith("$it/") }

    private val ROOTS = listOf(KernelPaths.SUSPEND_STATS, KernelPaths.WAKEUP_REASONS, KernelPaths.WAKEUP_CLASS)
    private const val FILE = "@"
    private const val ERROR = "!"
    private const val END = "#end"
    private const val NEWLINE = '\n'.code.toByte()
    private const val MAX_HEADER_BYTES = 4096L

    /** sysfs `show` output is at most one page; 64 KiB covers 16 KiB pages with room to spare. */
    private const val MAX_FILE_BYTES = 64L * 1024

    /** About 10 files per S9 source; phones list a few hundred sources. */
    private const val MAX_FRAMES = 50_000
}

/** One bulk read split into its three sources. A source the stream does not touch is null. */
public data class KernelSourceReads(
    val bootKey: Long?,
    val suspendStats: Parsed<SuspendStats?>?,
    val lastWakeup: Parsed<LastWakeup?>?,
    val wakeupSources: Parsed<List<WakeupSourceInstance>>?,
)

/** Splits [KernelFiles] into S25, S26 and S9 with their own parsers. */
public object KernelSources {
    public fun split(files: KernelFiles): KernelSourceReads =
        KernelSourceReads(
            bootKey = files.bootKey,
            suspendStats =
                KernelPaths.SUSPEND_STATS.takeIf(files::touches)?.let { dir ->
                    if (dir in files.errors) failed(SuspendStatsParser.SOURCE) else SuspendStatsParser.parse(files.filesIn(dir))
                },
            lastWakeup =
                KernelPaths.WAKEUP_REASONS.takeIf(files::touches)?.let { dir ->
                    val reason = files.files["$dir/${KernelPaths.LAST_RESUME_REASON}"]
                    if (reason ==
                        null
                    ) {
                        failed(WakeupReasonParser.SOURCE)
                    } else {
                        WakeupReasonParser.parse(reason, files.files["$dir/${KernelPaths.LAST_SUSPEND_TIME}"])
                    }
                },
            wakeupSources = KernelPaths.WAKEUP_CLASS.takeIf(files::touches)?.let { WakeupSourcesParser.parse(files) },
        )

    private fun failed(source: String): Parsed<Nothing?> {
        val ctx = ParseContext(source)
        ctx.issue(0, source, "not readable")
        return Parsed(null, ctx.report(0, complete = false))
    }
}

/**
 * S9: every `wakeupN` directory of a bulk read. Complete only when the class directory listed and
 * every listed source parsed whole; a source that vanished between the listing and its read is left
 * out and makes the read partial, never zero.
 */
public object WakeupSourcesParser {
    public const val SOURCE: String = "sysfs.wakeup_class"

    public fun parse(files: KernelFiles): Parsed<List<WakeupSourceInstance>> {
        val ctx = ParseContext(SOURCE)
        val root = KernelPaths.WAKEUP_CLASS
        if (root in files.errors) {
            ctx.issue(0, root, "not listable")
            return Parsed(emptyList(), ctx.report(0, complete = false))
        }
        val prefix = "$root/"
        val dirs = LinkedHashMap<String, MutableMap<String, String>>()
        for ((path, text) in files.files) {
            if (!path.startsWith(prefix)) continue
            val rest = path.substring(prefix.length).split('/')
            if (rest.size == 2) dirs.getOrPut(rest[0]) { LinkedHashMap() }[rest[1]] = text
        }
        files.errors.keys.filter { it.startsWith(prefix) }.forEach { path ->
            dirs.getOrPut(path.substring(prefix.length).substringBefore('/')) { LinkedHashMap() }
        }
        val out = ArrayList<WakeupSourceInstance>(dirs.size)
        var bytes = 0L
        for ((dir, attributes) in dirs) {
            bytes += attributes.values.sumOf { it.length.toLong() }
            val instance = dir.removePrefix(WAKEUP).toIntOrNull()?.takeIf { dir.startsWith(WAKEUP) && it >= 0 }
            val parsed = WakeupSourceParser.parse(attributes)
            val stats = parsed.value
            when {
                instance == null -> ctx.unknown("directory")
                stats == null -> ctx.issue(0, dir, "source unreadable or vanished")
                else -> out += WakeupSourceInstance(instance, stats)
            }
        }
        return Parsed(out, ctx.report(bytes, complete = ctx.issueCountIsZero()))
    }

    private const val WAKEUP = "wakeup"
}
